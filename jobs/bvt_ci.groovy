// Job configuration placeholder
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    def vpc = ["vpc-cephci", "vpc-block", "vpc-cephfs", "vpc-dmfg", "vpc-nfsg", "vpc-rgw"]
    def logLevel = ["DEBUG", "INFO", "ERROR"]
    def vmFlavor = ["bx2-2x8", "bx2-4x16", "bx2-8x32"]
    def buildType = ["IBM", "RH"]

    // Set releases for Ceph versions
    def release = ["stable", "nightly"]
    if (data.cephVersion.toLowerCase() == "squid") {
        release += ["released", "z0", "z1", "z2", "z3", "z4"]
    }

    // Add the job configurations
    data.jobsConfiguration.add([
        "name": "${data.cephVersion}-bvt-ci",
        "displayName": "${data.cephVersion} - Build Verification Test Pipeline",
        "logLevel": logLevel,
        "vmFlavor": vmFlavor,
        "vpc": vpc,
        "buildVersion": data.buildVersion,
        "rhelVersion": data.rhelVersion,
        "release": release,
        "buildType": buildType
    ])
}

// Create jobs specific to Ceph - Squid
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "squid",
    buildVersion: ["8.1", "8.0"],
    rhelVersion: ["rhel-9"]
)

// Create jobs specific to Ceph - Tentacle
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "tentacle",
    buildVersion: ["9.0"],
    rhelVersion: ["rhel-10", "rhel-9"]
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
                scriptPath("jobs/pipelines/bvt_ci/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                stringParam("PREFIX", "bvt", "Prefix for instance name")
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
                booleanParam("UPDATE_BVT_RESULTS", false, "Update BVT results to manifest")
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "", "Email Address")
            }
        }
    }
}
