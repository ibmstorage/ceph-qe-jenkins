// Job configuration placeholder
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    def logLevel = ["DEBUG", "INFO", "ERROR"]

    // Add the job configurations
    data.jobsConfiguration << [
        name          : "${data.cephVersion}-released-ci",
        displayName   : "${data.cephVersion} - Released Test Pipeline",
        cephVersion   : data.cephVersion,
        logLevel      : logLevel,
        build_version : data.buildVersion,
        rhel_version  : data.rhelVersion
    ]
}

// Create jobs specific to Ceph - Reef
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "reef",
    buildVersion: ["7.1"],
    rhelVersion: ["rhel-9"]
)

// Create jobs specific to Ceph - Squid
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "squid",
    buildVersion: ["8.1"],
    rhelVersion: ["rhel-9"]
)

// Create jobs specific to Ceph - Tentacle
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "tentacle",
    buildVersion: ["9.2", "9.1", "9.0"],
    rhelVersion: ["rhel-10", "rhel-9"]
)

// Create job for each configuration created
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
                            url("https://github.com/ibmstorage/ceph-qe-jenkins.git")
                            credentials("gh-ceph-qe-sa-git")
                        }
                        branch("*/main")
                    }
                }
                scriptPath("jobs/pipelines/released_ci/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                stringParam("PREFIX", "released", "Prefix for instance name")
                choiceParam("CLOUD_TYPE", ["ibmc", "aws"], "Cloud Platform to execute Tests")
                stringParam("CEPHCI_REPO", "https://github.com/red-hat-storage/cephci.git", "CephCI git repo")
                stringParam("BRANCH", "main", "CephCI git branch")
                choiceParam("LOG_LEVEL", jobConfig.logLevel, "CephCI log level")
                choiceParam("BUILD_VERSION", jobConfig.build_version, "Ceph Version")
                choiceParam("BUILD_TYPE", ["IBM", "RH"], "RH or IBM build")
                choiceParam("RHEL_VERSION", jobConfig.rhel_version, "RHEL OS Version")
                stringParam("RELEASE", "released", "Ceph release to test (released build)")
                stringParam("INVENTORY", "", "Ceph CI Inventory file name")
                stringParam("CUSTOM_CONFIG", "", "Custom configs with comma separated 'key=value' values")

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
                booleanParam("SKIP_SOS_REPORT", false, "Capture SOS report")
                stringParam("EMAIL_ADDRESS", "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com", "Email Address")
            }
        }
    }
}
