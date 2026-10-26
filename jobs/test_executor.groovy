// Job configuration placeholder
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    def vpc = [
        "vpc-cephci", "ci-vpc-01",
        "vpc-vivek-dallas", "vpc-neha-london", "vpc-manohar-madrid"
    ]
    def logLevel = ["ERROR", "INFO", "DEBUG"]
    def buildType = ["IBM", "RH"]

    // Set releases for Ceph versions
    def release = ["stable", "nightly"]
    if (data.cephVersion.toLowerCase() == "reef") {
        release += ["rc", "released", "respin", "z0", "z1", "z2", "z3", "z4", "z5", "z6", "z7", "z8", "z9"]
    } else if (data.cephVersion.toLowerCase() == "squid") {
        release += ["rc", "released", "respin", "z0", "z1", "z2", "z3", "z4", "z5", "z6"]
    } else if (data.cephVersion.toLowerCase() == "tentacle") {
        release += ["rc", "released", "respin", "z0", "z1", "z2", "z3"]
    } else if (data.cephVersion.toLowerCase() == "umbrella") {
        release += [] //Add rc, released, z-streams as it becomes ready for umbrella
    }

    // Add the job configurations
    data.jobsConfiguration.add([
        "name": "${data.cephVersion}-test-executor",
        "displayName": "${data.cephVersion} - Manual Test Suite Execution",
        "logLevel": logLevel,
        "vpc": vpc,
        "buildVersion": data.buildVersion,
        "rhelVersion": data.rhelVersion,
        "release": release,
        "buildType": buildType
    ])
}

// Create jobs specific to Ceph - Reef
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "reef",
    buildVersion: ["7.1", "7.0"],
    rhelVersion: ["rhel-9"]
)

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
    buildVersion: ["9.2", "9.1", "9.0"],
    rhelVersion: ["rhel-10", "rhel-9"]
)

// Create jobs specific to Ceph - Umbrella
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "umbrella",
    buildVersion: ["10.0"],
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
                scriptPath("jobs/pipelines/test_executor/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                choiceParam("CLOUD_TYPE", ["ibmc", "aws"], "Cloud platform (aws runs on agents-aws agent)")
                stringParam("PREFIX", "mte", "Prefix for instance name")
                stringParam("CEPHCI_REPO", "https://github.com/red-hat-storage/cephci.git", "Ceph CI git repo")
                stringParam("BRANCH", "main", "Ceph CI git branch")
                choiceParam("LOG_LEVEL", jobConfig.logLevel , "Ceph CI log level")
                choiceParam("BUILD_VERSION", jobConfig.buildVersion, "Ceph Version")
                choiceParam("RHEL_VERSION", jobConfig.rhelVersion, "RHEL OS Version")
                choiceParam("BUILD_TYPE", jobConfig.buildType , "RH or IBM build (AWS supports IBM only)")
                choiceParam("RELEASE", jobConfig.release, "Release type")
                stringParam("REPO", "", "Repository for RPM builds")
                stringParam("HOTFIX_REPO", "", "Hotfix repo")
                stringParam("CONTAINER_IMAGE", "", "Container image URL")
                stringParam("SUITE", "", "Ceph CI suite")
                stringParam("GLOBAL_CONF", "", "Ceph CI global conf")
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

                booleanParam("ENABLE_FIPS_MODE", false, "Enable FIPS mode")
                booleanParam("ENABLE_FIREWALL", false, "Enable firewall")
                booleanParam("SKIP_SUBSCRIPTION", true, "Disable registering VMs with RHEL CDN")
                booleanParam("SKIP_SOS_REPORT", true, "Uncheck to enable SOS Report collection")
                booleanParam("ENABLE_REPORT_PORTAL", false, "Update results to report portal")
                booleanParam("ENABLE_GSHEET", false, "Update results to GSheet")
                stringParam("CUSTOM_CONFIG", "", "Custom configs with comma separated 'key=value' values")
                stringParam("CUSTOM_IMAGES", "", "Custom images with comma separated values")
                stringParam("ADDITIONAL_ARGS", "", "Additional cephci arguments with comma separated 'key=value' values")
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "", "Email Address")
            }
        }
    }
}
