// This script is used to execute test suites in a Ceph CI environment.


def call(Map parameters) {
    // Check if Ceph CI directory present
    def gitDir = parameters.get("gitDir")
    if ( ! gitDir || ! fileExists(gitDir) ) {
        throw new RuntimeException(
            "Ceph CI directory ${gitDir} does not exists"
        )
    }

    // Check if virtual environment directory present
    def venvDir = parameters.get("venvDir")
    if ( ! venvDir || ! fileExists("${gitDir}/${venvDir}") ) {
        throw new RuntimeException(
            "Ceph CI virtual environment directory ${venvDir} does not exists"
        )
    }

    // Check for mandatory parameters
    def cloudType = parameters.get("cloudType")
    if ( ! cloudType ) {
        throw new RuntimeException(
            "Ceph CI requires cloudType parameter to execute tests"
        )
    }

    def instanceName = parameters.get("instanceName")
    if ( ! instanceName ) {
        throw new RuntimeException(
            "CephCI requires 'instances-name' parameter to execute tests"
        )
    }

    def release = parameters.get("release")
    def rhbuild = release
    if ( ! release ) {
        throw new RuntimeException(
            "CephCI requires 'release' parameter to execute tests"
        )
    }

    def platform = parameters.get("platform")
    if ( ! platform ) {
        throw new RuntimeException(
            "CephCI requires 'platform' parameter to execute tests"
        )
    }

    def globalConf = parameters.get("globalConf")
    if ( ! globalConf ) {
        throw new RuntimeException(
            "CephCI requires 'globalConf' parameter to execute tests"
        )
    }

    // Create log and result directories
    def logDir = parameters.get("logDir")
    if ( ! logDir ) {
        throw new RuntimeException(
            "CephCI requires 'logDir' parameter to execute tests"
        )
    }

    def suite = parameters.get("suite")
    if (! suite ) {
        throw new RuntimeException(
            "No test suite passed for execution"
        )
    }

    // Check for cloud credentials file
    def ibmcCredFile = parameters.get("ibmcCredFile")
    if ( ! ibmcCredFile ) {
        throw new RuntimeException(
            "Ceph CI requires IBMC credential file to execute tests"
        )
    }

    def inventory = parameters.get("inventory")
    if ( ! inventory ) {
        throw new RuntimeException(
            "Ceph CI requires inventory file to execute tests"
        )
    }

    // Create arguments for run.py
    def args = [
        "${venvDir}/bin/python run.py",
        "--log-dir ${logDir}",
        "--cloud ${cloudType}",
        "--osp-cred ${ibmcCredFile}",
        "--inventory ${inventory}",
        "--instances-name ${instanceName}",
        "--release ${release}",
        "--platform ${platform}",
        "--build ${parameters.manifestSection}",
        "--global-conf ${globalConf}",
        "--suite ${suite}"
    ]

    def logLevel = parameters.get("logLevel")
    args += logLevel ? ["--log-level ${logLevel}"] : []

    // Check for custom-config parameter
    def customConfig = parameters.get("customConfig")
    if ( customConfig ) {
        customConfig.each { val ->
            if (val) {
                args += ["--custom-config ${val.replace('@sha256', '')}"]
            }
        }
    }

    // Check for custom-config parameter
    if ( parameters.tag == "callhome" ) {
        def siTenantCredentials = parameters.get("siTenant")
        args += ["--custom-config-file ${siTenantCredentials}"]
    }

    // Check for IBM build type
    def buildType = parameters.get("buildType")
    if ( buildType.toLowerCase() == "ibm" ) {
        args += ["--product ibm"]
        args += ["--custom-config ibm-build=True"]
    } else {
        args += ["--product redhat"]
    }

    // Check for FIPS mode
    args += parameters.enableFipsMode ? ["--custom-config enable-fips-mode=True"] : []

    // Check for Firewall mode
    args += parameters.enableFirewall ? ["--custom-config enable-firewall=True"] : []

    // Check for downstream build details
    def rhsCephRepo = parameters.get("rhsCephRepo")
    args += rhsCephRepo ? ["--rhs-ceph-repo ${rhsCephRepo}"] : []

    // Check for hotfix repo
    def hotfixRepo = parameters.get("hotfixRepo")
    args += hotfixRepo ? ["--hotfix-repo ${hotfixRepo}"] : []

    if ( ! rhsCephRepo && ! hotfixRepo ) {
        throw new RuntimeException(
            "Ceph CI requires 'rhsCephRepo' or 'hotfixRepo' " +
            "parameters to execute tests"
        )
    }

    // Check for container image
    def containerImage = parameters.get("containerImage")
    if ( ! containerImage ) {
        throw new RuntimeException(
            "Ceph CI requires container image to execute tests"
        )
    }

    // We cannot use matcher due serialization issues
    def containerImageList = containerImage.tokenize('/')
    def registry = containerImageList.removeAt(0)
    def imageNameAndTag = containerImageList.pop()
    def (imageName, imageTag) = imageNameAndTag.tokenize(':')
    def fullPathToImage = containerImageList.join("/")

    // Remove sha256 from tag if exists
    imageName = imageName.replace("@sha256", "")

    args += [
        "--docker-registry ${registry}",
        "--docker-image ${fullPathToImage}/${imageName}",
        "--docker-tag ${imageTag}"
    ]

    // Check for additional arguments
    def additionalArgs = parameters.get("additionalArgs")
    if ( additionalArgs ) {
        additionalArgs.each { val ->
            if ( val.contains("=") ) {
                def arg = val.split("=")
                args += ["--${arg[0]} ${arg[1]}"]
            } else {
                args += ["--${val}"]
            }
        }
    }

    // Check for xunit results
    def xunitResults = parameters.get("xunitResults", true)
    args += xunitResults ? ["--xunit-results"] : []

    // Check for store parameter
    def store = parameters.get("store", false)
    args += store ? ["--store"] : []

    // Check for reuse parameter
    def reuse = parameters.get("reuse")
    args += reuse ? ["--reuse ${reuse}"] : []

    // Check for rhel repo
    def addRepo = parameters.get("addRepo")
    args += addRepo ? ["--add-repo ${addRepo}"] : []

    // Check for rpm parameters
    def skipEnablingRhelRpms = parameters.get("skipEnablingRhelRpms")
    args += skipEnablingRhelRpms ? ["--skip-enabling-rhel-rpms"] : []

    def skipSubscription = parameters.get("skipSubscription")
    args += skipSubscription ? ["--skip-subscription"] : []

    // Add IBM Cloud instance configs
    if ( cloudType == "ibmc" ) {
        // Check the VM profile type
        def vmFlavor = parameters.get("vmFlavor")
        if (! vmFlavor ) {
            throw new RuntimeException(
                "Ceph CI requires 'vmFlavor' parameter to execute tests"
            )
        }

        // Gather the vpc Name
        def vpcName = parameters.get("vpcName")
        if ( ! vpcName ) {
            throw new RuntimeException(
                "Ceph CI requires the VPC name to execute tests"
            )
        }

        args += [
            "--custom-config ibmc_vpc=${vpcName}",
            "--custom-config ibmc_profile=${vmFlavor}"
        ]
    }

    // Add AWS instance config (instance_type passed via custom-config)
    if ( cloudType == "aws" ) {
        def instanceType = parameters.get("vmFlavor")
        args += ["--custom-config instance_type=${instanceType}"]
    }

    // Check for sos report
    args += parameters.get("skipSosReport") ? ["--skip-sos-report"] : []

    // Start cephci run
    dir(gitDir) {
        def cmdStr = args.join(" ")
        sh script: cmdStr

        // Check result file, exit 1 in case no results are generated
        if ( ! fileExists("${logDir}/xunit.xml") ) {
            sh "exit 1"
        }
    }
}
