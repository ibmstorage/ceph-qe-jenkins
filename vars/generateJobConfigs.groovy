// Method to generate job configurations for QE test executor

import main.groovy.cephJobLib.cephciConfigs
import main.groovy.cephJobLib.jobConfigs


def call(Map parameters) {
    // Define required variables
    def jobConfig = [:]

    // Set job type parameters
    jobConfig.job = parameters.get("job")

    // Set default values for job configuration
    def jobDir = [jobConfigs.JOB_DIR_PATH]

    // Check for repository and container image
    if (!params.REPO || !params.CONTAINER_IMAGE) {
        currentBuild.result = "ABORTED"
        error("Mandatory parameters 'REPO' & 'CONTAINER_IMAGE' are not provided")
    }

    // Get ceph downstream repo
    jobConfig.rhsCephRepo = params.REPO

    // Get container image
    jobConfig.containerImage = params.CONTAINER_IMAGE

    // Get prefix for resources
    if (!params.PREFIX) {
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
    jobConfig.ibmcProfile = params.IBMC_PROFILE ?: cephciConfigs.IBMC_PROFILE
    jobConfig.ospCred = params.OSP_CRED ?: cephciConfigs.OSP_CRED
    jobConfig.python = cephciConfigs.PYTHON
    jobConfig.venvDir = cephciConfigs.VENV_DIR

    // Update job directory based on cloud type
    jobDir += [jobConfig.cloudType]

    // Update dir paths based on build type
    jobConfig.buildType = params.BUILD_TYPE
    jobDir += [jobConfig.buildType]

    // Update dir paths based on build version
    jobConfig.rhBuild = params.BUILD_VERSION
    jobDir += [jobConfig.rhBuild]

    // Get RHEL version
    jobConfig.platform = params.RHEL_VERSION
    jobDir += [jobConfig.platform]

    // Fetch ceph version
    jobConfig.cephVersion = fetchCephVersion(jobConfig)

    // Update job path with ceph version & build number
    jobDir += [jobConfig.job, jobConfig.cephVersion, env.BUILD_NUMBER]

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
    if (params.ADDITIONAL_ARGS) {
        jobConfig.additionalArgs = params.ADDITIONAL_ARGS.split(",") as List
    }

    // Get custom config
    jobConfig.customConfig = []
    if (params.CUSTOM_CONFIG) {
        jobConfig.customConfig = params.CUSTOM_CONFIG.split(",") as List
    }

    // Set inventory file
    if (params.INVENTORY) {
        jobConfig.inventory = params.INVENTORY
    } else if (jobConfig.cloudType == "ibmc") {
        jobConfig.inventory = cephciConfigs.IBMC_INVENTORY[jobConfig.platform]
    } else {
        abort("Inventory file not provided for cloud type: ${jobConfig.cloudType}")
    }

    // Set base job directory
    jobConfig.jobDir = jobDir.join("/")

    // Set cephci config directory
    jobConfig.gitDir = [jobConfig.jobDir, cephciConfigs.GIT_DIR].join("/")
    jobConfig.logDir = [jobConfig.jobDir, cephciConfigs.LOG_DIR].join("/")

    // Check for IBMC Credential ID
    jobConfig.ibmcCredFile = [jobConfig.gitDir, "ibmc-api-creds"].join("/")

    // Return the job configuration
    return jobConfig
}
