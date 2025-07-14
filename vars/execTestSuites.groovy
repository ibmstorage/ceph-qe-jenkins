// This script is used to execute test suites in a Ceph CI environment.

import java.util.regex.Pattern


def call(Map parameters) {
    // Check if Ceph CI directory present
    def gitDir = parameters.get("gitDir", "cephci")

    // Get Ceph CI optional parameters
    def logLevel = parameters.get("logLevel", "DEBUG")
    def cloudType = parameters.get("cloudType", "ibmc").toLowerCase()

    // Check for cephci repo
    if (!fileExists(gitDir)) {
        throw new RuntimeException("Ceph CI directory '${gitDir}' does not exists")
    }

    // Check for mandatory parameters
    def instanceName = parameters.get("instanceName")
    if (!instanceName) {
        throw new RuntimeException("CephCI requires 'instances-name' parameter to execute tests")
    }

    def rhbuild = parameters.get("rhBuild")
    if (!rhbuild) {
        throw new RuntimeException("CephCI requires 'rhbuild' parameter to execute tests")
    }

    def platform = parameters.get("platform")
    if (!platform) {
        throw new RuntimeException("CephCI requires 'platform' parameter to execute tests")
    }

    def globalConf = parameters.get("globalConf")
    if (!globalConf) {
        throw new RuntimeException("CephCI requires 'globalConf' parameter to execute tests")
    }

    def logDir = parameters.get("logDir", "logs")
    if (!logDir) {
        throw new RuntimeException("CephCI requires 'logDir' parameter to execute tests")
    }

    def suite = parameters.get("suite")
    if (!suite) {
        throw new RuntimeException("No test suite passed for execution")
    }

    // Create arguments for run.py
    def args = [
        "cd", gitDir, "&&",
        ".venv/bin/python", "run.py",
        "--log-level", "${logLevel}",
        "--log-dir", "${logDir}",
        "--cloud","${cloudType}",
        "--instances-name", "${instanceName}",
        "--rhbuild", "${rhbuild}",
        "--platform", "${platform}",
        "--global-conf", "${globalConf}",
        "--suite", "${suite}"
    ]

    // Check for cloud credentials file
    def ospCredFile = parameters.get("ospCredFile")
    if (!ospCredFile) {
        throw new RuntimeException("Ceph CI requires OSP credential file to execute tests")
    }

    // Update command with OSP credential file
    args += ["--osp-cred", ospCredFile]

    // Get inventory file
    def inventory = parameters.get("inventory")
    if (!inventory) {
        if (platform == "rhel-8") {
            inventory = "conf/inventory/ibm-rhel-8.10-server-bx2-4x16.yaml"
        } else if (platform == "rhel-9") {
            inventory = "conf/inventory/ibm-rhel-9.6-server-bx2-4x16.yaml"
        }
    }
    args += ["--inventory", "${inventory}"]

    // Check for build tag
    def build = parameters.get("build")
    if (build) {
        args += ["--build", "${build}"]
    }

    // Check for custom-config parameter
    def customConfig = parameters.get("customConfig")
    if (customConfig) {
        customConfig.each { val ->
            if (val) {
                args += ["--custom-config", "${val}"]
            }
        }
    }

    // Check for custom-config parameter
    if (parameters.tag == "callhome") {
        def siTenantCredentials = parameters.get("siTenant")
        args += ["--custom-config-file", "${siTenantCredentials}"]
    }

    // Check for IBM build type
    def buildType = parameters.get("buildType")
    if (buildType.toLowerCase() == "ibm") {
        args += ["--custom-config", "ibm-build=True"]
    }

    // Check for FIPS mode
    def enableFipsMode = parameters.get("enableFipsMode")
    if (enableFipsMode) {
        args += ["--custom-config", "enable-fips-mode=True"]
    }

    // Check for Firewall mode
    def enableFirewall = parameters.get("enableFirewall")
    if (enableFirewall) {
        args += ["--custom-config", "enable-firewall=True"]
    }

    // Check for downstream build details
    def rhsCephRepo = parameters.get("rhsCephRepo")
    if (rhsCephRepo) {
        args += ["--rhs-ceph-repo", "${rhsCephRepo}"]
    }

    // Check for hotfix repo
    def hotfixRepo = parameters.get("hotfixRepo")
    if (hotfixRepo) {
        args += ["--hotfix-repo", "${hotfixRepo}"]
    }

    def containerImage = parameters.get("containerImage")
    if (containerImage) {
        // Get container registry, image namd and tag
        def tag = containerImage =~ /(.*(.com|.io))\/(.*):(.*)/
        if (!tag.find()) {
            throw new RuntimeException("Failed to get image tag from URL: ${containerImage}")
        }
        args += ["--docker-registry", tag[0][1], "--docker-image", tag[0][3], "--docker-tag", tag[0][4]]
    }

    // Check for additional arguments
    def additionalArgs = parameters.get("additionalArgs")
    if (additionalArgs) {
        additionalArgs.each { val ->
            if (val.contains("=")) {
                def arg = val.split("=")
                args += ["--${arg[0]} ${arg[1]}"]
            } else {
                args += ["--${val}"]
            }
        }
    }

    // Check for xunit results
    def xunitResults = parameters.get("xunitResults", true)
    if (xunitResults) {
        args += ["--xunit-results"]
    }

    // Check for store parameter
    def store = parameters.get("store", true)
    if (store) {
        args += ["--store"]
    }

    // Check for reuse parameter
    def reuse =  parameters.get("reuse")
    if (reuse) {
        args += ["--reuse", reuse]
    }

    // Create log and result directories
    dir(logDir) {
        sh "pwd -P"
    }

    // Check for rhel repo
    def addRepo = parameters.get("addRepo")
    if (addRepo) {
        args += ["--add-repo", "${addRepo}"]
    }

    // Check for repm parameters
    def skipEnablingRhelRpms = parameters.get("skipEnablingRhelRpms")
    if (skipEnablingRhelRpms) {
        args += "--skip-enabling-rhel-rpms"
    }

    def skipSubscription = parameters.get("skipSubscription")
    if (skipSubscription) {
        args += "--skip-subscription"
    }

    // Check for sos reports
    def skipSosReport = parameters.get("skipSosReport")
    if (skipSosReport) {
        args += "--skip-sos-report"
    }

    // Add exit status
    args += [
        "||", "exit 0"  // exit 0 in case test case failed for unexpected errors
    ]

    // Set result file path
    def xunit = [logDir, "xunit.xml"].join("/")

    // Execute command
    sh args.join(" ")

    // Check result file, exit 1 in case no results are generated
    if (!fileExists(xunit)) {
        sh "exit 1"
    }
}