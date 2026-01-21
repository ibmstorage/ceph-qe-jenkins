// Job configuration holder
def jobsConfiguration = []

// Supported Ceph versions
def CEPH_VERSIONS = ["Tentacle", "Squid", "Reef"]

// FG → VPC mapping
def FG_VPC = [
    "DMFG"       : ["ci-vpc-01"],
    "callhome"   : ["ci-vpc-01"],
    "smb"        : ["ci-vpc-01"],
    "RGW"        : ["ci-vpc-01"],
    "CephFS"     : ["vpc-cephfs"],
    "RADOS"      : ["vpc-cephfs"],
    "NFS-Ganesha": ["vpc-nfsg"],
    "RBD"        : ["vpc-block"],
    "NvmeOF"     : ["vpc-block"]
]

// Ceph release specific configuration
def CEPH_RELEASE_CONFIG = [
    "reef": [
        buildVersion: ["7.1", "7.0"],
        rhelVersion : ["rhel-9"]
    ],
    "squid": [
        buildVersion: ["8.1", "8.0"],
        rhelVersion : ["rhel-9"]
    ],
    "tentacle": [
        buildVersion: ["9.0"],
        rhelVersion : ["rhel-10", "rhel-9"]
    ]
]

// Method to generate job metadata
def generate_jobs(data) {

    def logLevel  = ["ERROR", "DEBUG", "INFO"]
    def vmFlavor  = ["cx2-2x4", "bx2-2x8", "cx2-4x8", "cx2-8x16"]
    def buildType = ["IBM", "RH"]
    def release   = "stable"

    def cephVersion = data.cephVersion.toLowerCase()
    def releaseConfig = data.cephReleaseConfig[cephVersion]

    def rhelVersion  = releaseConfig.rhelVersion
    def buildVersion = releaseConfig.buildVersion

    // CephCI repo defaults
    def cephciRepo        = "https://github.com/red-hat-storage/cephci.git"
    def cephciBranch      = "main"
    def buildDescription  = "IBM or RH build"

    // Add job configuration
    data.jobsConfiguration.add([
        "name"            : "${cephVersion}-${data.fg.toLowerCase()}-upgrade-ci",
        "displayName"     : "${data.cephVersion} - ${data.fg} - Upgrade Test Pipeline",
        "fg"              : [data.fg.toLowerCase()],
        "cephciRepo"      : cephciRepo,
        "cephciBranch"    : cephciBranch,
        "logLevel"        : logLevel,
        "vmFlavor"        : vmFlavor,
        "vpc"             : data.vpc,
        "buildVersion"    : buildVersion,
        "rhelVersion"     : rhelVersion,
        "release"         : release,
        "buildType"       : buildType,
        "buildDescription": buildDescription
    ])
}

// Generate jobs for all Ceph versions & FGs
def SKIP_FG_BY_CEPH = ["reef": ["smb"]]

CEPH_VERSIONS.each { cephVersion ->
    def skipFgs = SKIP_FG_BY_CEPH[cephVersion.toLowerCase()] ?: []

    FG_VPC.each { fg, vpc ->
        if (skipFgs.contains(fg)) {
            return
        }

        generate_jobs(
            jobsConfiguration: jobsConfiguration,
            cephVersion      : cephVersion,
            fg               : fg,
            vpc              : vpc,
            cephReleaseConfig: CEPH_RELEASE_CONFIG
        )
    }
}

// Create Jenkins pipeline jobs
jobsConfiguration.each { jobConfig ->

    pipelineJob(jobConfig.name) {
        // Job display name
        displayName(jobConfig.displayName)

        // Get job definition from git repo
        definition {
            cpsScm {
                scm {
                    git {
                        remote {
                            url("https://github.ibm.com/ceph-qe-ng/jenkins.git")
                            credentials("ceph-qe-sa-pat")
                        }
                        branch("*/main")
                    }
                }
                scriptPath("jobs/pipelines/upgrade_ci/Jenkinsfile")
                lightweight(false)
            }
        }

        parameters {
            stringParam("PREFIX", "${jobConfig.fg[0]}-upgrade", "Prefix for instance name")
            choiceParam("FG", jobConfig.fg, "Ceph Component")
            choiceParam("LOG_LEVEL", jobConfig.logLevel, "Ceph CI log level")

            stringParam("CEPHCI_REPO", jobConfig.cephciRepo, "CephCI git repo")
            stringParam("BRANCH", jobConfig.cephciBranch, "CephCI branch")

            choiceParam("BUILD_VERSION", jobConfig.buildVersion, "Ceph version")
            choiceParam("RHEL_VERSION", jobConfig.rhelVersion, "RHEL version")
            choiceParam("BUILD_TYPE", jobConfig.buildType, jobConfig.buildDescription)
            stringParam("RELEASE", jobConfig.release, "Release type")
            stringParam("REPO", "", "Repository for RPM builds")
            stringParam("HOTFIX_REPO", "", "Hotfix repository")
            stringParam("CONTAINER_IMAGE", "", "Container image URL")
            stringParam("CUSTOM_CONFIG", "", "Custom config key=value pairs")
            stringParam("CUSTOM_IMAGES", "", "Custom images")

            choiceParam("VM_FLAVOR", jobConfig.vmFlavor, "IBM Cloud VM flavor")
            choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC")

            booleanParam("ENABLE_FIPS_MODE", false, "Enable FIPS mode")
            booleanParam("ENABLE_FIREWALL", false, "Enable firewall")
            booleanParam("SKIP_SUBSCRIPTION", true, "Skip RHEL subscription")
            booleanParam("SKIP_SOS_REPORT", true, "Skip SOS report collection")
            stringParam("ADDITIONAL_ARGS", "", "Additional cephci args")
            stringParam("EMAIL_ADDRESS", "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com", "Notification email")
        }
    }
}
