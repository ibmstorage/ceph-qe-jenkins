// Job configuration placeholder
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    def logLevel = ["ERROR", "INFO", "DEBUG"]
    def buildType = ["IBM", "RH"]
    def rhelVersion = []
    def buildVersion = []

    // Set release specific variables
    def release = ["stable", "nightly"]
    if (data.cephVersion.toLowerCase() == "squid") {
        release += ["rc", "released", "z0", "z1", "z2", "z3", "z4", "z5"]
        rhelVersion = ["rhel-9"]
        buildVersion = ["8.1", "8.0"]
    } else if (data.cephVersion.toLowerCase() == "tentacle") {
        release += ["rc", "released", "z0", "z1", "z2"]
        rhelVersion = ["rhel-10", "rhel-9"]
        buildVersion = ["9.2", "9.1", "9.0"]
    } else if (data.cephVersion.toLowerCase() == "umbrella") {
        release += [] //Add rc, released, z-streams as it becomes ready for umbrella
        rhelVersion = ["rhel-10", "rhel-9"]
        buildVersion = ["10.0"]
    }
    // Add the job configurations
    data.jobsConfiguration.add([
        "name": "${data.cephVersion.toLowerCase()}-${data.fg.toLowerCase()}-extended-regression-ci",
        "displayName": "${data.cephVersion} - ${data.fg} - Extended Regression Test Pipeline",
        "fg": [data.fg.toLowerCase()],
        "logLevel": logLevel,
        "vpc": data.vpc,
        "buildVersion": buildVersion,
        "rhelVersion": rhelVersion,
        "release": release,
        "buildType": buildType
    ])
}

// Create jobs specific to Ceph - Squid - DMFG
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "DMFG",
    vpc: ["vpc-vivek-dallas"]
)

// Create jobs specific to Ceph - Squid - CephFS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "CephFS",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Squid - RADOS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "RADOS",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Squid - NFS-Ganesha
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "NFS-Ganesha",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Squid - RBD
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "RBD",
    vpc: ["vpc-manohar-madrid"]
)

// Create jobs specific to Ceph - Squid - NvmeOF
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "NvmeOF",
    vpc: ["vpc-manohar-madrid"]
)

// Create jobs specific to Ceph - Squid - RGW
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "RGW",
    vpc: ["ci-vpc-01"]
)

// Create jobs specific to Ceph - Squid - SMB
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "SMB",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Tentacle - DMFG
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "DMFG",
    vpc: ["vpc-vivek-dallas"]
)

// Create jobs specific to Ceph - Tentacle - CephFS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "CephFS",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Tentacle - RADOS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "RADOS",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Tentacle - NFS-Ganesha
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "NFS-Ganesha",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Tentacle - RBD
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "RBD",
    vpc: ["vpc-manohar-madrid"]
)

// Create jobs specific to Ceph - Tentacle - NvmeOF
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "NvmeOF",
    vpc: ["vpc-manohar-madrid"]
)

// Create jobs specific to Ceph - Tentacle - RGW
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "RGW",
    vpc: ["ci-vpc-01"]
)

// Create jobs specific to Ceph - Tentacle - SMB
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "SMB",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Umbrella - DMFG
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "DMFG",
    vpc: ["vpc-vivek-dallas"]
)

// Create jobs specific to Ceph - Umbrella - CephFS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "CephFS",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Umbrella - RADOS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "RADOS",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Umbrella - NFS-Ganesha
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "NFS-Ganesha",
    vpc: ["vpc-neha-london"]
)

// Create jobs specific to Ceph - Umbrella - RBD
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "RBD",
    vpc: ["vpc-manohar-madrid"]
)

// Create jobs specific to Ceph - Umbrella - NvmeOF
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "NvmeOF",
    vpc: ["vpc-manohar-madrid"]
)

// Create jobs specific to Ceph - Umbrella - RGW
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "RGW",
    vpc: ["ci-vpc-01"]
)

// Create jobs specific to Ceph - Umbrella - SMB
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Umbrella",
    fg: "SMB",
    vpc: ["vpc-neha-london"]
)

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
                scriptPath("jobs/pipelines/extended_regression_ci/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                choiceParam("CLOUD_TYPE", ["ibmc", "aws"], "Cloud platform (aws runs on agents-aws agent)")
                stringParam("PREFIX", "${jobConfig.fg[0]}-extended-regression", "Prefix for instance name")
                choiceParam("FG", jobConfig.fg, "Ceph Component")
                stringParam("CEPHCI_REPO", "https://github.com/red-hat-storage/cephci.git", "Ceph CI git repo")
                stringParam("BRANCH", "main", "Ceph CI git branch")
                choiceParam("LOG_LEVEL", jobConfig.logLevel , "Ceph CI log level")
                choiceParam("BUILD_VERSION", jobConfig.buildVersion, "Ceph Version")
                choiceParam("RHEL_VERSION", jobConfig.rhelVersion, "RHEL OS Version")
                choiceParam("BUILD_TYPE", jobConfig.buildType , "RH or IBM build")
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
        }
    }
}
