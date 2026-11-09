// Job configuration placeholder
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    def vpc = ["vpc-cephci"]
    def logLevel = ["DEBUG", "INFO", "ERROR"]
    def buildType = data.buildType ?: ["IBM", "RH"]
    def prefix = data.prefix ?: "sanity"
    def skipBuildCheck = data.skipBuildCheck != null ? data.skipBuildCheck : false
    def enablePromotion = data.enablePromotion != null ? data.enablePromotion : true

    // Set releases for Ceph versions
    def release = data.release ?: ["nightly", "stable"]
    if (!data.release) {
        if (data.cephVersion.toLowerCase() == "reef") {
            release += ["rc", "released", "respin", "z0", "z1", "z2", "z3", "z4", "z5", "z6", "z7", "z8", "z9"]
        } else if (data.cephVersion.toLowerCase() == "squid") {
            release += ["rc", "released", "respin", "z0", "z1", "z2", "z3", "z4", "z5"]
        } else if (data.cephVersion.toLowerCase() == "tentacle") {
            release += ["rc", "released", "respin", "z0", "z1", "z2", "z3"]
        }
    }

    def jobName = data.name ?: "${data.cephVersion}-sanity-ci"
    def displayName = data.displayName ?: "${data.cephVersion} - Sanity Test Pipeline"

    // Add the job configurations
    data.jobsConfiguration.add([
        "name": jobName,
        "displayName": displayName,
        "prefix": prefix,
        "logLevel": logLevel,
        "vpc": vpc,
        "buildVersion": data.buildVersion,
        "rhelVersion": data.rhelVersion,
        "release": release,
        "buildType": buildType,
        "skipBuildCheck": skipBuildCheck,
        "enablePromotion": enablePromotion,
        "cronExpr": data.cronExpr
    ])
}
// Create jobs specific to Ceph - Reef
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "reef",
    buildVersion: ["7.1", "7.0"],
    rhelVersion: ["rhel-9"],
    cronExpr: ""
)

// Create jobs specific to Ceph - Squid
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "squid",
    buildVersion: ["8.1", "8.0"],
    rhelVersion: ["rhel-9"],
    cronExpr: ""
)

// Create jobs specific to Ceph - Tentacle
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "tentacle",
    buildVersion: ["9.2", "9.1", "9.0"],
    rhelVersion: ["rhel-10", "rhel-9"],
    cronExpr: "30 3 * * *"
)

// Create jobs specific to Ceph - Umbrella
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "umbrella",
    buildVersion: ["10.0"],
    rhelVersion: ["rhel-10", "rhel-9"],
    cronExpr: ""
)

// Weekly Saturday FIPS job for Tentacle (6:00 AM IST / 00:30 UTC Saturday)
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    name: "tentacle-sanity-fips-ci",
    displayName: "Tentacle - Sanity Test Pipeline (FIPS)",
    prefix: "sanity-fips",
    cephVersion: "tentacle",
    buildVersion: ["9.2", "9.1", "9.0"],
    rhelVersion: ["rhel-10", "rhel-9"],
    buildType: ["IBM", "RH"],
    release: ["stable", "nightly", "rc", "released", "respin", "z0", "z1", "z2", "z3"],
    skipBuildCheck: true,
    enablePromotion: false,
    cronExpr: "30 0 * * 6"
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
                            url("https://github.com/ibmstorage/ceph-qe-jenkins.git")
                            credentials("gh-ceph-qe-sa-git")
                        }
                        branch("*/main")
                    }
                }
                scriptPath("jobs/pipelines/sanity_ci/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                choiceParam("CLOUD_TYPE", ["ibmc", "aws"], "Cloud platform (aws runs on agents-aws agent)")
                stringParam("PREFIX", jobConfig.prefix, "Prefix for instance name")
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
                booleanParam("SKIP_BUILD_CHECK", jobConfig.skipBuildCheck, "Skip checking if nightly and stable builds are identical")
                if (jobConfig.enablePromotion) {
                    booleanParam("UPDATE_SANITY_RESULTS", false, "Update Sanity results to manifest")
                }
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "cephqe-devops@wwpdl.vnet.ibm.com", "Email Address")
            }

            // Add trigger for cron job
            triggers {
                cron(jobConfig.cronExpr)
            }
        }
    }
}
