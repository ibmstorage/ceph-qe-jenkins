// Job configuration placeholder
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    def logLevel = ["DEBUG", "INFO", "ERROR"]
    def vmFlavor = ["cx2-2x4", "cx2-4x8", "cx2-8x16"]
    def buildType = ["IBM", "RH"]
    def rhelVersion = []
    def buildVersion = []

    // Set release specific variables
    def release = ["stable", "nightly"]
    if (data.cephVersion.toLowerCase() == "squid") {
        release += ["rc", "released", "z0", "z1", "z2", "z3", "z4"]
        rhelVersion = ["rhel-9"]
        buildVersion = ["8.1", "8.0"]
    } else if (data.cephVersion.toLowerCase() == "tentacle") {
        rhelVersion = ["rhel-10", "rhel-9"]
        buildVersion = ["9.0"]
    }
    // Add the job configurations
    data.jobsConfiguration.add([
        "name": "${data.cephVersion.toLowerCase()}-${data.fg.toLowerCase()}-weekly-ci",
        "displayName": "${data.cephVersion} - ${data.fg} - Weekly Test Pipeline",
        "fg": [data.fg.toLowerCase()],
        "logLevel": logLevel,
        "vmFlavor": vmFlavor,
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
    vpc: ["vpc-dmfg"]
)

// Create jobs specific to Ceph - Squid - CephFS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "CephFS",
    vpc: ["vpc-cephfs"]
)

// Create jobs specific to Ceph - Squid - RADOS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "RADOS",
    vpc: ["vpc-cephfs"]
)

// Create jobs specific to Ceph - Squid - NFS-Ganesha
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "NFS-Ganesha",
    vpc: ["vpc-nfsg"]
)

// Create jobs specific to Ceph - Squid - RBD
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "RBD",
    vpc: ["vpc-block"]
)

// Create jobs specific to Ceph - Squid - NvmeOF
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "NvmeOF",
    vpc: ["vpc-block"]
)

// Create jobs specific to Ceph - Squid - RGW
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    fg: "RGW",
    vpc: ["vpc-rgw"]
)

// Create jobs specific to Ceph - Tentacle - DMFG
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "DMFG",
    vpc: ["vpc-dmfg"]
)

// Create jobs specific to Ceph - Tentacle - CephFS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "CephFS",
    vpc: ["vpc-cephfs"]
)

// Create jobs specific to Ceph - Tentacle - RADOS
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "RADOS",
    vpc: ["vpc-cephfs"]
)

// Create jobs specific to Ceph - Tentacle - NFS-Ganesha
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "NFS-Ganesha",
    vpc: ["vpc-nfsg"]
)

// Create jobs specific to Ceph - Tentacle - RBD
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "RBD",
    vpc: ["vpc-block"]
)

// Create jobs specific to Ceph - Tentacle - NvmeOF
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "NvmeOF",
    vpc: ["vpc-block"]
)

// Create jobs specific to Ceph - Tentacle - RGW
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    fg: "RGW",
    vpc: ["vpc-rgw"]
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
                scriptPath("jobs/pipelines/weekly_ci/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                stringParam("PREFIX", "${jobConfig.fg[0]}-weekly", "Prefix for instance name")
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
                choiceParam("VM_FLAVOR", jobConfig.vmFlavor, "IBM Cloud virtual machine profile")
                booleanParam("ENABLE_FIPS_MODE", false, "Enable FIPS mode")
                booleanParam("ENABLE_FIREWALL", false, "Enable firewall")
                stringParam("CUSTOM_CONFIG", "", "Custom configs with comma separated 'key=value' values")
                stringParam("CUSTOM_IMAGES", "", "Custom images with comma separated values")
                stringParam("ADDITIONAL_ARGS", "", "Additional cephci arguments with comma separated 'key=value' values")
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com", "Email Address")
            }
        }
    }
}
