// Standalone pipeline for the cephadm agent scale suite.
// It is not part of DMFG regression or extended regression, which run their
// suites in parallel and would provision this cluster at the same time.

def jobsConfiguration = []

jobsConfiguration.add([
    "name": "umbrella-dmfg-agent-scale-ci",
    "displayName": "Umbrella - DMFG - Cephadm Agent Scale Regression",
    "fg": ["dmfg"],
    "logLevel": ["ERROR", "INFO", "DEBUG"],
    "vpc": ["vpc-vivek-dallas"],
    "buildVersion": ["10.0"],
    "rhelVersion": ["rhel-10", "rhel-9"],
    "release": ["stable", "nightly"],
    "buildType": ["IBM", "RH"]
])

for (jobConfig in jobsConfiguration) {
    def job = pipelineJob(jobConfig.name) {
        displayName(jobConfig.displayName)

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
                scriptPath("jobs/pipelines/agent_scale_ci/Jenkinsfile")
                lightweight(false)
            }

            parameters {
                choiceParam("CLOUD_TYPE", ["ibmc"], "IBM Cloud only. The suite conf is an IBM VPC cluster.")
                stringParam("PREFIX", "dmfg-agent-scale", "Prefix for instance name")
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

                choiceParam("VM_FLAVOR", ["bx2-2x8", "cx2-4x8", "cx2-8x16"], "IBM Cloud VM profile")

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
