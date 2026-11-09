/*
    RC respin CI: squid-respin-ci, tentacle-respin-ci.

    - Manual Build with Parameters (no cron / listener hook yet).
    - RELEASE = manifest section in qe-ceph-manifest (rc or respin), same Validate
      path as BVT via generateJobConfigs; recipe must include version, repositories,
      and images for that section.
    - Suites: metadata/<cephVersion>/common.yaml under job key "respin".
    - Pipeline: jobs/pipelines/respin_ci/Jenkinsfile (BVT, then sanity, then
      upgrade waves; 15h timeout; disableConcurrentBuilds).
*/
def jobsConfiguration = []

def generate_jobs(data) {
    def vpc = ["vpc-cephci"]
    def logLevel = ["DEBUG", "INFO", "ERROR"]
    def buildType = ["IBM", "RH"]
    def release = ["rc", "respin"]

    data.jobsConfiguration.add([
        "name": "${data.cephVersion}-respin-ci",
        "displayName": "${data.cephVersion} - RC Respin Test Pipeline",
        "logLevel": logLevel,
        "vpc": vpc,
        "buildVersion": data.buildVersion,
        "rhelVersion": data.rhelVersion,
        "release": release,
        "buildType": buildType
    ])
}

generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "squid",
    buildVersion: ["8.1", "8.0"],
    rhelVersion: ["rhel-9"]
)

generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "tentacle",
    buildVersion: ["9.2", "9.1", "9.0"],
    rhelVersion: ["rhel-10", "rhel-9"]
)

for (jobConfig in jobsConfiguration) {
    pipelineJob(jobConfig.name) {
        displayName(jobConfig.displayName)

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
                scriptPath("jobs/pipelines/respin_ci/Jenkinsfile")
                lightweight(false)
            }

            parameters {
                choiceParam("CLOUD_TYPE", ["ibmc", "aws"], "Cloud platform (aws runs on agents-aws agent)")
                stringParam("PREFIX", "respin", "Prefix for instance name")
                stringParam("CEPHCI_REPO", "https://github.com/red-hat-storage/cephci.git", "Ceph CI git repo")
                stringParam("BRANCH", "main", "Ceph CI git branch")
                choiceParam("LOG_LEVEL", jobConfig.logLevel, "Ceph CI log level")
                choiceParam("BUILD_VERSION", jobConfig.buildVersion, "Ceph Version")
                choiceParam("RHEL_VERSION", jobConfig.rhelVersion, "RHEL OS Version")
                choiceParam("BUILD_TYPE", jobConfig.buildType, "RH or IBM build")
                choiceParam("RELEASE", jobConfig.release, "Manifest section (rc or respin build)")
                stringParam("REPO", "", "Repository for RPM builds")
                stringParam("HOTFIX_REPO", "", "Hotfix repo")
                stringParam("CONTAINER_IMAGE", "", "Container image URL")

                activeChoiceReactiveParam("VM_FLAVOR") {
                    description("VM flavor / instance type based on selected cloud")
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
                stringParam("ADDITIONAL_ARGS", "", "Additional cephci arguments with comma separated 'key=value' values")
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "cephqe-devops@wwpdl.vnet.ibm.com", "Email Address")
            }
        }
    }
}
