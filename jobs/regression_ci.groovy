// Job configuration placeholder
def jobsConfiguration = []

def LOG_LEVELS = ["ERROR", "INFO", "DEBUG"]
def VM_FLAVORS = ["cx2-2x4", "bx2-2x8", "cx2-4x8", "cx2-8x16"]

// FG → VPC mapping
def FG_VPC = [
    "DMFG"       : ["ci-vpc-01"],
    "callhome"   : ["ci-vpc-01"],
    "smb"        : ["ci-vpc-01"],
    "CephFS"     : ["vpc-cephfs"],
    "RADOS"      : ["vpc-cephfs"],
    "NFS-Ganesha": ["vpc-nfsg"],
    "RBD"        : ["vpc-block"],
    "NvmeOF"     : ["vpc-block"],
    "RGW"        : ["vpc-rgw"]
]

// Ceph release specific configuration
def CEPH_RELEASE_CONFIG = [
    "Reef": [
        buildVersion : ["7.1", "7.0"],
        rhelVersion  : ["rhel-9"],
        releaseExtra : ["rc", "released", "z0","z1","z2","z3","z4","z5","z6","z7","z8"]
    ],
    "Squid": [
        buildVersion : ["8.1", "8.0"],
        rhelVersion  : ["rhel-9"],
        releaseExtra : ["rc", "released", "z0","z1","z2","z3","z4"]
    ],
    "Tentacle": [
        buildVersion : ["9.1", "9.0"],
        rhelVersion  : ["rhel-10", "rhel-9"],
        releaseExtra : ["rc", "released", "z0"]
    ]
]

def generate_jobs(cfg, data) {

    def ceph     = data.ceph
    def fg       = data.fg
    def config   = data.config
    def vpc      = data.vpc
    def logLevel = data.logLevel
    def vmFlavor = data.vmFlavor


    def cephciRepo   = "https://github.com/red-hat-storage/cephci.git"
    def cephciBranch = "main"

    def buildType = ["IBM", "RH"]
    def description  = "IBM or RH build"
    if (fg == "callhome") {
        cephciRepo   = "https://github.ibm.com/ceph-qe-ng/cephci.git"
        cephciBranch = "callhome"
        buildType    = ["IBM"]
        description  = "IBM build"
    }

    cfg.add([
        name            : "${ceph.toLowerCase()}-${fg.toLowerCase()}-regression-ci",
        displayName     : "${ceph} - ${fg} - Regression Test Pipeline",
        fg              : [fg.toLowerCase()],
        cephciRepo      : cephciRepo,
        cephciBranch    : cephciBranch,
        logLevel        : logLevel,
        vmFlavor        : vmFlavor,
        vpc             : vpc,
        buildVersion    : config.buildVersion,
        rhelVersion     : config.rhelVersion,
        release         : ["stable", "nightly"] + config.releaseExtra,
        buildType       : buildType,
        buildDescription: description
    ])
}

CEPH_RELEASE_CONFIG.each { ceph, config ->
    FG_VPC.each { fg, vpc ->
        generate_jobs(jobsConfiguration, [
            ceph: ceph,
            fg  : fg,
            vpc : vpc,
            config: config,
            logLevel  : LOG_LEVELS,
            vmFlavor  : VM_FLAVORS
        ])
    }
}

// Create job for each configuration created
for (jobConfig in jobsConfiguration) {
    def job = pipelineJob(jobConfig.name) {
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
                scriptPath("jobs/pipelines/regression_ci/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                stringParam("PREFIX", "${jobConfig.fg[0]}-regression", "Prefix for instance name")
                choiceParam("FG", jobConfig.fg, "Ceph Component")
                stringParam("CEPHCI_REPO", jobConfig.cephciRepo, "CephCI git/gitlab repo")
                stringParam("BRANCH", jobConfig.cephciBranch, "CephCI git/gitlab branch")
                choiceParam("LOG_LEVEL", jobConfig.logLevel , "Ceph CI log level")
                choiceParam("BUILD_VERSION", jobConfig.buildVersion, "Ceph Version")
                choiceParam("RHEL_VERSION", jobConfig.rhelVersion, "RHEL OS Version")
                choiceParam("BUILD_TYPE", jobConfig.buildType , jobConfig.buildDescription)
                choiceParam("RELEASE", jobConfig.release, "Release type")
                stringParam("REPO", "", "Repository for RPM builds")
                stringParam("HOTFIX_REPO", "", "Hotfix repo")
                stringParam("CONTAINER_IMAGE", "", "Container image URL")
                choiceParam("VM_FLAVOR", jobConfig.vmFlavor, "IBM Cloud virtual machine profile")
                booleanParam("ENABLE_FIPS_MODE", false, "Enable FIPS mode")
                booleanParam("ENABLE_FIREWALL", false, "Enable firewall")
                booleanParam("SKIP_SUBSCRIPTION", true, "Disable registering VMs with RHEL CDN")
                booleanParam("SKIP_SOS_REPORT", true, "Uncheck to enable SOS Report collection")
                stringParam("CUSTOM_CONFIG", "", "Custom configs with comma separated 'key=value' values")
                stringParam("CUSTOM_IMAGES", "", "Custom images with comma separated values")
                stringParam("ADDITIONAL_ARGS", "", "Additional cephci arguments with comma separated 'key=value' values")
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com", "Email Address")
            }
        }
    }
}
