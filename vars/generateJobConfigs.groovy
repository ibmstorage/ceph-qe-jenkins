// Method to generate job configurations for QE test executor

import main.groovy.cephJobLib.cephciConfigs
import main.groovy.cephJobLib.ibmcConfigs
import main.groovy.cephJobLib.jobConfigs
import main.groovy.cephJobLib.rpConfigs
import main.groovy.cephJobLib.manifestConfigs


def call(Map parameters) {
    // Define required variables
    def jobConfig = [:]

    // Set job type parameters
    jobConfig.job = parameters.get("job")

    // Set default values for job configuration
    def jobDir = [jobConfigs.JOB_DIR_PATH]

    // Get prefix for resources
    if ( ! params.PREFIX ) {
        currentBuild.result = "ABORTED"
        error("Mandatory parameter 'PREFIX' is not provided")
    }
    jobConfig.prefix = params.PREFIX

    // Get hotfix repo
    jobConfig.hotfixRepo = params.HOTFIX_REPO

    // Get Ceph CI git repo defaults
    jobConfig.gitUrl = params.CEPHCI_REPO ?: cephciConfigs.GIT_URL
    jobConfig.gitBranch = params.BRANCH ?: cephciConfigs.GIT_BRANCH
    jobConfig.logLevel = params.LOG_LEVEL ?: cephciConfigs.LOG_LEVEL
    jobConfig.cloudType = params.CLOUD_TYPE ?: cephciConfigs.CLOUD_TYPE
    jobConfig.vmFlavor = params.VM_FLAVOR ?: cephciConfigs.VM_FLAVOR
    jobConfig.python = cephciConfigs.PYTHON
    jobConfig.venvDir = cephciConfigs.VENV_DIR

    // Update dir paths based on build type
    jobConfig.buildType = params.BUILD_TYPE
    jobDir += [jobConfig.buildType]
    jobConfig.rpLaunchName = "[${jobConfig.buildType}]"

    // Update dir paths based on build version
    jobConfig.rhBuild = params.BUILD_VERSION
    jobDir += [jobConfig.rhBuild]
    jobConfig.rpLaunchName += "[${jobConfig.rhBuild}]"

    // Get RHEL version
    jobConfig.platform = params.RHEL_VERSION
    jobDir += [jobConfig.platform]
    jobConfig.rpLaunchName += "[${jobConfig.platform}]"

    // Get FIPS config
    jobConfig.enableFipsMode = params.ENABLE_FIPS_MODE

    // Get FIREWALL config
    jobConfig.enableFirewall = params.ENABLE_FIREWALL

    // Get additional rhel repo
    jobConfig.addRepo = params.RHEL_REPO

    // Get test execution tag
    jobConfig.executeTests = params.EXECUTE_TESTS

    // Get additional cephci arguments
    jobConfig.additionalArgs = []
    if ( params.ADDITIONAL_ARGS ) {
        jobConfig.additionalArgs = params.ADDITIONAL_ARGS.split(",") as List
    }

    // Get custom config
    jobConfig.customConfig = []
    if ( params.CUSTOM_CONFIG ) {
        jobConfig.customConfig = params.CUSTOM_CONFIG.split(",") as List
    }

    // Set inventory file
    if ( params.INVENTORY ) {
        jobConfig.inventory = params.INVENTORY
    } else if ( jobConfig.cloudType == "ibmc" ) {
        jobConfig.inventory = cephciConfigs.IBMC_INVENTORY[jobConfig.platform]
    } else {
        abort("Inventory file not provided for cloud type: ${jobConfig.cloudType}")
    }

    // Get the VPC Name
    if ( jobConfig.cloudType == "ibmc" ) {
        jobConfig.vpcName = params.VPC_NAME ?: cephciConfigs.VPC_NAME
    }

    // Get subscription manager execution
    jobConfig.skipSubscription = params.SKIP_SUBSCRIPTION

    // Check for repository and container image
    if ( params.REPO && params.CONTAINER_IMAGE ) {
        // Get ceph downstream repo & container image
        jobConfig.rhsCephRepo = params.REPO
        jobConfig.containerImage = params.CONTAINER_IMAGE

        // Fetch ceph version
        jobConfig.cephVersion = fetchCephVersion(jobConfig)
    } else if ( params.RELEASE ) {
        // Set recipe path
        def recipe = "${jobConfig.buildType.toLowerCase()}"
        if (jobConfig.buildType == "RH") {
            recipe = "redhat"
        }
        recipe += "/${jobConfig.rhBuild}.yaml"

        // Read recipe file from manifest repo
        recipeContent = readFromManifest(recipe: recipe, job: jobConfig.job)

        // Check if recipe content is valid
        if ( ! recipeContent ) {
            currentBuild.result = "ABORTED"
            error("No recipe found for the provided recipe: ${recipe}")
        }

        // Check if release is valid
        if ( ! recipeContent."${params.RELEASE}" ) {
            currentBuild.result = "ABORTED"
            error("No build info found for the provided release: ${params.RELEASE}")
        }

        // Get repo data center
        /*def repoDc = ""
        if (jobConfig.buildType == "RH") {
            repoDc = "eu-de"
        } else if (jobConfig.buildType == "IBM") {
            repoDc = manifestConfigs.DEFAULT_DC
        }*/
        def repoDc = "eu-de"

        // Get ceph downstream repo based on DC & platform
        jobConfig.rhsCephRepo = recipeContent."${params.RELEASE}".repositories."${repoDc}"."${jobConfig.platform}"

        // Fetch ceph version
        jobConfig.cephVersion = recipeContent."${params.RELEASE}".version

        // Set container image
        if ( ! params.CONTAINER_IMAGE ) {
            jobConfig.containerImage = recipeContent."${params.RELEASE}".images."ceph-base"
        }

        // -----------------------------------------------------------------------------
        // Configure custom container images
        //
        // The images defined in the job’s custom configuration take precedence
        // over those specified in the release manifest.
        //
        // Precedence order:
        //   1) Job configuration (highest priority)
        //   2) Manifest defaults (fallback)
        // -----------------------------------------------------------------------------
        recipeContent."${params.RELEASE}".images.each { image, url ->
            if (image == "ceph-base") {
                return
            }

            if ( image == "crimson_image") {
                return
            }

            if ( image == "nvmeof_cli_image" ) {
                return
            }

            def exists = jobConfig.customConfig.any { it.startsWith("${image}=") }
            if (!exists) {
                jobConfig.customConfig << "${image}=${url}"
                println("Added custom-config for ${image} from qe-ceph-manifest")
            }

        }

    } else {
        // Abort if mandatory parameters are not provided
        currentBuild.result = "ABORTED"
        error("Unable to get parameters 'REPO' & 'CONTAINER_IMAGE'")
    }

    // Debug info
    println("Ceph downstream repo: ${jobConfig.rhsCephRepo}")
    println("Container image: ${jobConfig.containerImage}")
    println("Ceph version: ${jobConfig.cephVersion}")

    // Update job path with ceph version
    jobDir += [jobConfig.job, jobConfig.cephVersion]

    // Update path with FG for regression & weekly jobs
    if (params.FG) {
        jobConfig.fg = params.FG
        jobDir += [jobConfig.fg]
        jobConfig.rpLaunchName += "[${jobConfig.fg}]"
    }

    // Update job path with build number
    jobDir += [env.BUILD_NUMBER]

    // Set base job directory
    jobConfig.jobDir = jobDir.join("/")

    // Set cephci config directory
    jobConfig.gitDir = [jobConfig.jobDir, cephciConfigs.GIT_DIR].join("/")
    jobConfig.logDir = [jobConfig.jobDir, cephciConfigs.LOG_DIR].join("/")

    // Set job config directories
    jobConfig.resultsDir = [jobConfig.jobDir, jobConfigs.RESULTS_DIR].join("/")
    jobConfig.resultsSummary = [jobConfig.jobDir, jobConfigs.RESULTS_SUMMARY].join("/")
    jobConfig.artifacts = [jobConfig.jobDir, jobConfigs.ARTIFACTS].join("/")
    jobConfig.emailBody = [jobConfig.jobDir, jobConfigs.EMAIL_BODY].join("/")

    // Check for IBMC Credential ID
    jobConfig.ibmcCredFile = [jobConfig.gitDir, "osp-cred"].join("/")

    // Set report portal configs
    jobConfig.rpLaunchName += " ${jobConfig.job} Test Run"

    // Update launch description with JOB_URL
    jobConfig.rpLaunchDesc  = "JOB_URL: ${env.BUILD_URL}\\n"

    // Set project name & url based on build type
    if (jobConfig.buildType.toLowerCase() == "ibm"){
        jobConfig.rpProjectName = rpConfigs.IBM_PROJECT_NAME
        jobConfig.rpLaunchUrl = rpConfigs.IBM_LANUCH_URL
    } else if (jobConfig.buildType.toLowerCase() == "upstream") {
        jobConfig.rpProjectName = rpConfigs.UPSTREAM_PROJECT_NAME
        jobConfig.rpLaunchUrl = rpConfigs.UPSTREAM_LANUCH_URL
    } else if (jobConfig.buildType.toLowerCase() == "rh") {
        jobConfig.rpProjectName = rpConfigs.RHCS_PROJECT_NAME
        jobConfig.rpLaunchUrl = rpConfigs.RHCS_LANUCH_URL
    } 

    // Launch attributes
    jobConfig.rpLaunchAttributes = """[
        {"key": "BUILD_TYPE", "value": "${jobConfig.buildType}"},
        {"key": "CEPH_VERSION", "value": "${jobConfig.cephVersion}"},
        {"key": "RELEASE", "value": "${jobConfig.rhBuild}"},
        {"key": "PLATFORM", "value": "${jobConfig.cloudType}"},
        {"key": "RHEL", "value": "${jobConfig.platform}"},
        {"key": "TAG", "value": "${jobConfig.tag}"}
    ]"""

    // Return the job configuration
    return jobConfig
}
