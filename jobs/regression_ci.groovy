// Job configuration placeholder
def jobsConfiguration = []

def LOG_LEVELS = ["ERROR", "INFO", "DEBUG"]

// FG → VPC mapping (Jenkins default = only entry). Quota / ownership matrix:
// Sydney ci-vpc-01 (Madhavi/RGW),
// Frankfurt vpc-cephci (Shantha/CephCI),
// Dallas vpc-vivek-dallas (Vivek/DMFG),
// London vpc-neha-london (Neha/CephFS, RADOS, NFS-Ganesha),
// Madrid vpc-manohar-madrid (Manohar/Block, RBD, NvmeOF).
def FG_VPC = [
    "DMFG"       : ["vpc-vivek-dallas"],
    "callhome"   : ["vpc-vivek-dallas"],
    "smb"        : ["vpc-vivek-dallas"],
    "CephFS"     : ["vpc-neha-london"],
    "RADOS"      : ["vpc-neha-london"],
    "NFS-Ganesha": ["vpc-neha-london"],
    "RBD"        : ["vpc-manohar-madrid"],
    "NvmeOF"     : ["vpc-manohar-madrid"],
    "RGW"        : ["ci-vpc-01"]
]

// Ceph release specific configuration
def CEPH_RELEASE_CONFIG = [
    "Reef": [
        buildVersion : ["7.1", "7.0"],
        rhelVersion  : ["rhel-9"],
        releaseExtra : ["rc", "released", "z0","z1","z2","z3","z4","z5","z6","z7","z8","z9"]
    ],
    "Squid": [
        buildVersion : ["8.1", "8.0"],
        rhelVersion  : ["rhel-9"],
        releaseExtra : ["rc", "released", "z0","z1","z2","z3","z4","z5"]
    ],
    "Tentacle": [
        buildVersion : ["9.2", "9.1", "9.0"],
        rhelVersion  : ["rhel-10", "rhel-9"],
        releaseExtra : ["rc", "released", "z0", "z1", "z2", "z3"]
    ],
    "Umbrella": [
        buildVersion : ["10.0"],
        rhelVersion  : ["rhel-10", "rhel-9"],
        releaseExtra : [] //Add rc, released, z-streams as it becomes ready for umbrella
    ]
]

def generate_jobs(cfg, data) {

    def ceph     = data.ceph
    def fg       = data.fg
    def config   = data.config
    def vpc      = data.vpc
    def logLevel = data.logLevel

    def cephciRepo   = "https://github.com/red-hat-storage/cephci.git"
    def cephciBranch = "main"

    def buildType = ["IBM", "RH"]
    def description  = "IBM or RH build"
    def cronExpr = ""
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
        vpc             : vpc,
        buildVersion    : config.buildVersion,
        rhelVersion     : config.rhelVersion,
        release         : ["stable", "nightly"] + config.releaseExtra,
        buildType       : buildType,
        buildDescription: description,
        cronExpr        : cronExpr
    ])
}

CEPH_RELEASE_CONFIG.each { ceph, config ->
    FG_VPC.each { fg, vpc ->
        generate_jobs(jobsConfiguration, [
            ceph: ceph,
            fg  : fg,
            vpc : vpc,
            config: config,
            logLevel  : LOG_LEVELS
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
                            url("https://github.com/ibmstorage/ceph-qe-jenkins.git")
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
                choiceParam("CLOUD_TYPE", ["ibmc", "aws"], "Cloud platform (aws runs on agents-aws agent)")
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
                stringParam("INVENTORY", "", "Ceph CI Inventory file name")

                // VM_FLAVOR: choices depend on CLOUD_TYPE (IBM Cloud profile or AWS EC2 instance type)
                activeChoiceReactiveParam("VM_FLAVOR") {
                    description("VM flavor / instance type based on selected cloud (IBM Cloud profile or AWS EC2 instance type)")
                    choiceType("SINGLE_SELECT")
                    groovyScript {
                        script("""
                            if ("aws".equals(CLOUD_TYPE)) { return ["t4g.medium", "m6g.xlarge"] }
                            return ["bx2-2x8", "cx2-4x8", "cx2-8x16"]
                        """)
                        fallbackScript("return [\"bx2-2x8\", \"cx2-4x8\", \"cx2-8x16\"]")
                    }
                    referencedParameter("CLOUD_TYPE")
                }

                booleanParam("ENABLE_FIREWALL", false, "Enable firewall")
                booleanParam("SKIP_SUBSCRIPTION", true, "Disable registering VMs with RHEL CDN")
                booleanParam("SKIP_SOS_REPORT", true, "Uncheck to enable SOS Report collection")
                stringParam("CUSTOM_CONFIG", "", "Custom configs with comma separated 'key=value' values")
                stringParam("CUSTOM_IMAGES", "", "Custom images with comma separated values")
                stringParam("ADDITIONAL_ARGS", "", "Additional cephci arguments with comma separated 'key=value' values")
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com", "Email Address")
            }

            triggers {
                cron(jobConfig.cronExpr)
            }
        }
    }
}
