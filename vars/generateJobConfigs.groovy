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
    jobConfig.instanceType = params.INSTANCE_TYPE ?: cephciConfigs.AWS_INSTANCE_TYPE
    jobConfig.python = cephciConfigs.PYTHON
    jobConfig.venvDir = cephciConfigs.VENV_DIR

    // Update dir paths based on build type
    jobConfig.buildType = params.BUILD_TYPE

    // AWS supports IBM build only
    if ( jobConfig.cloudType == "aws" && jobConfig.buildType?.toLowerCase() == "rh" ) {
        currentBuild.result = "ABORTED"
        error("AWS supports IBM build only. Please select BUILD_TYPE=IBM.")
    }
    jobDir += [jobConfig.buildType]
    jobConfig.rpLaunchName = "[${jobConfig.buildType}]"

    // Update dir paths based on build version
    jobConfig.rhBuild = params.BUILD_VERSION

    // Adding support for newer parameters
    jobConfig.release = params.BUILD_VERSION
    jobDir += [jobConfig.release]
    jobConfig.rpLaunchName += "[${jobConfig.release}]"

    // Get RHEL version
    jobConfig.platform = params.RHEL_VERSION
    jobDir += [jobConfig.platform]
    jobConfig.rpLaunchName += "[${jobConfig.platform}]"

    // Get FIPS config
    jobConfig.enableFipsMode = params.ENABLE_FIPS_MODE

    // Get FIREWALL config
    jobConfig.enableFirewall = params.ENABLE_FIREWALL

    // Get Update Report Portal config
    jobConfig.enableReportPortal = params.ENABLE_REPORT_PORTAL ?: false
    
    // Get Update Google Sheet config
    jobConfig.enableGoogleSheet = params.ENABLE_GSHEET ?: false
    
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
    } else if ( jobConfig.cloudType == "aws" ) {
        jobConfig.inventory = cephciConfigs.AWS_INVENTORY[jobConfig.platform] ?: cephciConfigs.AWS_INVENTORY["rhel-9"]
    } else {
        abort("Inventory file not provided for cloud type: ${jobConfig.cloudType}")
    }

    // Get the VPC Name (IBM Cloud only; AWS uses credentials)
    if ( jobConfig.cloudType == "ibmc" ) {
        jobConfig.vpcName = params.VPC_NAME ?: cephciConfigs.VPC_NAME
    } else if ( jobConfig.cloudType == "aws" ) {
        jobConfig.vpcName = null  // Not used for AWS cleanup
    }

    // Get subscription manager execution
    jobConfig.skipSubscription = params.SKIP_SUBSCRIPTION

    // Get Sos Report config parameter
    jobConfig.skipSosReport = params.SKIP_SOS_REPORT ?: true

    // Support coverage for a particular build by reading the manifest before a
    // change.
    def release = "${jobConfig.buildType.toLowerCase()}"
    if ( release == "rh" ) {
        release = "redhat"
    }
    def manifestFile = "${release}/${jobConfig.rhBuild}.yaml"
    recipeContent = readFromManifest(recipe: manifestFile, job: jobConfig.job)

    if ( ! recipeContent ) {
        currentBuild.result = "ABORTED"
        error("Failed to retrieve build details for - ${manifestFile}")
    }

    // Check if release is valid
    if ( ! recipeContent."${params.RELEASE}" ) {
        currentBuild.result = "ABORTED"
        error("No build info found for the provided release: ${params.RELEASE}")
    }

    // FixMe: This has to be dynamic
    def repoDC = "eu-de"
    // Get ceph downstream repo based on DC & platform
    jobConfig.rhsCephRepo = recipeContent."${params.RELEASE}".repositories."${repoDC}"."${jobConfig.platform}"

    // Fetch ceph version
    jobConfig.cephVersion = recipeContent."${params.RELEASE}".version

    //Set the manifest section
    jobConfig.manifestSection = params.RELEASE

    /* -----------------------------------------------------------------------------
        Configure custom container images

        The images defined in the job's custom configuration take precedence
        over those specified in the release manifest.

        Precedence order:
            1) Job configuration (highest priority)
            2) Manifest defaults (fallback)
    ----------------------------------------------------------------------------- */

    // Collect images that need to be synced
    def imagesToSync = []

    recipeContent."${params.RELEASE}".images.each { image, url ->
        // Check if image exists in registry
        if ( ! checkImageInRegistry(url) ) {
            println("Image ${image}=${url} is not available in source registry, will sync...")
            imagesToSync << url
        }

        // Skip adding ceph-base to customConfig as it's already set as containerImage
        if ( image == "ceph-base" ) {
            jobConfig.containerImage = "${url}"
            return
        }

        // Skip adding crimson_image and ceph_csi_image to customConfig
        if ( image == "crimson_image" || image == "ceph_csi_image" ) {
            return
        }

        def exists = jobConfig.customConfig.any { it.startsWith("${image}=") }
        if ( ! exists ) {
            jobConfig.customConfig << "${image}=${url}"
            println("Added custom-config for ${image} from qe-ceph-manifest")
        }
    }

    // Sync all missing images in batch using syncBlueImages
    if ( imagesToSync.size() > 0 ) {
        println("Syncing ${imagesToSync.size()} missing image(s) to local registry...")
        
        try {
            def imagesList = imagesToSync.join("\n")
            syncBlueImages(images: imagesList)
            
            println("Successfully synced ${imagesToSync.size()} image(s) to local registry")
        } catch (Exception e) {
            currentBuild.result = "ABORTED"
            error("Failed to sync images to local registry: ${e.getMessage()}")
        }
    }

    // If repo is provided, use that repo instead of the manifestFile one.
    if ( params.REPO ) {
        jobConfig.rhsCephRepo = params.REPO
    }

    // if CONTAINER_IMAGE is provided, use the provided image
    if ( params.CONTAINER_IMAGE ) {
        jobConfig.containerImage = params.CONTAINER_IMAGE
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
    
    // Update path with suite name for MTE jobs
    if (params.SUITE) {
        def suite = params.SUITE.trim().split("/")[-2]

        jobConfig.fgSuite = suite
        jobDir += [jobConfig.fgSuite]
        jobConfig.rpLaunchName += "[${jobConfig.fgSuite}]"
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

    // Set credential file path and credential ID based on cloud type
    def credFileName = ( jobConfig.cloudType == "aws" ) ? "osp-cred-aws" : "osp-cred"
    jobConfig.ibmcCredFile = [jobConfig.gitDir, credFileName].join("/")
    jobConfig.credentialId = ( jobConfig.cloudType == "aws" ) ? "osp-cred-aws" : "osp-cred"

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
