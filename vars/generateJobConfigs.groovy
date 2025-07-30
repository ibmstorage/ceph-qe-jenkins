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
    if (!parameters.REPO || !parameters.CONTAINER_IMAGE) {
        currentBuild.result = "ABORTED"
        error("Mandatory parameters 'REPO' & 'CONTAINER_IMAGE' are not provided")
    }

    // Get ceph downstream repo
    jobConfig.rhsCephRepo = parameters.REPO

    // Get container image
    jobConfig.containerImage = parameters.CONTAINER_IMAGE

    // Get prefix for resources
    if (!parameters.PREFIX) {
        currentBuild.result = "ABORTED"
        error("Mandatory parameter 'PREFIX' is not provided")
    }
    jobConfig.prefix = parameters.PREFIX

    // Get hotfix repo
    jobConfig.hotfixRepo = parameters.HOTFIX_REPO

    // Get Ceph CI git repo defaults
    jobConfig.gitUrl = parameters.CEPHCI_REPO ?: cephciConfigs.GIT_URL
    jobConfig.gitBranch = parameters.BRANCH ?: cephciConfigs.GIT_BRANCH
    jobConfig.logLevel = parameters.LOG_LEVEL ?: cephciConfigs.LOG_LEVEL
    jobConfig.cloudType = parameters.CLOUD_TYPE ?: cephciConfigs.CLOUD_TYPE
    jobConfig.vmFlavor = parameters.VM_FLAVOR ?: cephciConfigs.VM_FLAVOR
    jobConfig.python = cephciConfigs.PYTHON
    jobConfig.venvDir = cephciConfigs.VENV_DIR

    // Update job directory based on cloud type
    jobDir += [jobConfig.cloudType]

    // Update dir paths based on build type
    jobConfig.buildType = parameters.BUILD_TYPE
    jobDir += [jobConfig.buildType]

    // Update dir paths based on build version
    jobConfig.rhBuild = parameters.BUILD_VERSION
    jobDir += [jobConfig.rhBuild]

    // Get RHEL version
    jobConfig.platform = parameters.RHEL_VERSION
    jobDir += [jobConfig.platform]

    // Fetch ceph version
    jobConfig.cephVersion = fetchCephVersion(jobConfig)

    // Update job path with ceph version & build number
    jobDir += [jobConfig.job, jobConfig.cephVersion, env.BUILD_NUMBER]

    // Get FIPS config
    jobConfig.enableFipsMode = parameters.ENABLE_FIPS_MODE

    // Get FIREWALL config
    jobConfig.enableFirewall = parameters.ENABLE_FIREWALL

    // Get additional rhel repo
    jobConfig.addRepo = parameters.RHEL_REPO

    // Get test execution tag
    jobConfig.executeTests = parameters.EXECUTE_TESTS

    // Get additional cephci arguments
    jobConfig.additionalArgs = []
    if (parameters.ADDITIONAL_ARGS) {
        jobConfig.additionalArgs = parameters.ADDITIONAL_ARGS.split(",") as List
    }

    // Get custom config
    jobConfig.customConfig = []
    if (parameters.CUSTOM_CONFIG) {
        jobConfig.customConfig = parameters.CUSTOM_CONFIG.split(",") as List
    }

    // Set inventory file
    if (parameters.INVENTORY) {
        jobConfig.inventory = parameters.INVENTORY
    } else if (jobConfig.cloudType == "ibmc") {
        jobConfig.vpcName = paparametersrams.VPC_NAME ?: cephciConfigs.VPC_NAME
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
    jobConfig.ibmcCredFile = [jobConfig.gitDir, "osp-cred"].join("/")

    // Return the job configuration
    return jobConfig
}
