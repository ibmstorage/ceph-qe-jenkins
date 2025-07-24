// Method to generate job configurations for QE test executor


def call(Map parameters) {
    // Define required variables
    def jobConfig = [:]
    def gitDir = "cephci"
    def logDir = "logs"
    
    // Set default values for job configuration
    def jobDir = ["/home/jenkins/ceph-builds"]
    jobConfig.job = "qe-test"

    // Check for repository and container image
    if (!params.REPO || !params.CONTAINER_IMAGE) {
        currentBuild.result = "ABORTED"
        error("Mandatory parameters 'REPO' & 'CONTAINER_IMAGE' are not provided")
    }

    // Get ceph downstream repo
    jobConfig.rhsCephRepo = params.REPO

    // Get hotfix repo
    jobConfig.hotfixRepo = params.HOTFIX_REPO

    // Get container image
    jobConfig.containerImage = params.CONTAINER_IMAGE

    // Get prefix for resources
    jobConfig.prefix = params.PREFIX

    // Get Ceph CI git repo details
    jobConfig.gitUrl = params.CEPHCI_REPO
    jobConfig.gitBranch = params.BRANCH

    // Get cephci log level
    jobConfig.logLevel = params.LOG_LEVEL ?: "DEBUG"

    // Update dir paths based on cloud type
    jobConfig.cloudType = params.CLOUD_TYPE
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

    // Check for IBM Cloud credentials
    if (jobConfig.cloudType.toLowerCase() == "ibmc") {
        jobConfig.ospCredPrefix = "ibmc"
        jobConfig.ospCredId = "${jobConfig.ospCredPrefix}-${params.OSP_CRED}"
    }

    // Set base job directory
    jobConfig.jobDir = jobDir.join("/")

    // Set cephci config directory
    jobConfig.gitDir = [jobConfig.jobDir, gitDir].join("/")
    jobConfig.logDir = [jobConfig.jobDir, logDir].join("/")

    // Check for OSP Credential ID
    if (jobConfig.ospCredId) {
        jobConfig.ospCredFile = [jobConfig.gitDir, jobConfig.ospCredId].join("/")
    }

    // Return the job configuration
    return jobConfig
}
