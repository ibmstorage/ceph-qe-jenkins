// Job configuration placeholde
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    // Platforms supported
    def platforms = ["ibmc"]
    def ospCreds = ["ci-vpc-01"]
    def logLevel = ["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]

    // For creating job configuration for each platform
    for (platform in platforms) {
        // Add the job configurations
        data.jobsConfiguration.add([
            "name": "qe-${data.cephVersion}-test-executor-${platform}",
            "displayName": "Ceph CI Test Executor - ${data.cephVersion} - ${platform}",
            "logLevel": logLevel,
            "cloud_type": [platform],
            "ospCreds": ospCreds,
            "build_version": data.buildVersion,
            "rhel_version": data.rhelVersion,
            "components": data.components,
            "release": data.release
        ])
    }
}

// Create jobs specific to Ceph - Squid
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "squid",
    buildVersion: ["8.1", "8.0"],
    rhelVersion: ["rhel-9.5", "rhel-9.4", "rhel-9.3", "rhel-9.2", "rhel-9.1", "rhel-9.0"],
    components: ["callhome", "cephadm", "cephfs", "cephmgr", "ceph_volume", "integrations", "interop", "mgr", "nfs", "nvmeof", "rados", "rbd", "rgw", "smoke", "upgrades", "smb"],
    release: ["", "latest"]
)

// Create jobs specific to Ceph - Tentacle
generate_jobs(
    jobsConfiguration: jobsConfiguration,
    cephVersion: "tentacle",
    buildVersion: ["9.0"],
    rhelVersion: ["rhel-9.5", "rhel-9.4", "rhel-9.3", "rhel-9.2", "rhel-9.1", "rhel-9.0"],
    components: ["callhome", "cephadm", "cephfs", "cephmgr", "ceph_volume", "integrations", "interop", "mgr", "nfs", "nvmeof", "rados", "rbd", "rgw", "smoke", "upgrades", "smb"],
    release: ["", "latest"]
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
                            url("https://gitlab.cee.redhat.com/rhcs-qe/cephqe-jenkins.git")
                        }

                        branch("*/main")
                    }
                }
                scriptPath("jobs/pipelines/qe_test_executor/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                stringParam("PREFIX", "ci", "Prefix for instance name")
                stringParam("CEPHCI_REPO", "https://github.com/red-hat-storage/cephci.git", "Ceph CI git repo")
                stringParam("BRANCH", "main", "Ceph CI git branch")
                choiceParam("LOG_LEVEL", jobConfig.logLevel , "CephCI log level")
                choiceParam("BUILD_VERSION", jobConfig.build_version, "Ceph Version")
                choiceParam("RHEL_VERSION", jobConfig.rhel_version, "RHEL OS Version")
                choiceParam("BUILD_TYPE", ["RH", "IBM"] , "RH or IBM build")
                choiceParam("COMPONENT", jobConfig.components, "Ceph component")
                choiceParam("RELEASE", jobConfig.release, "Build release")
                stringParam("REPO", "", "Repository for RPM builds")
                stringParam("HOTFIX_REPO", "", "Hotfix repo")
                stringParam("CONTAINER_IMAGE", "", "Container image URL")
                stringParam("SUITE", "", "Ceph CI suite")
                stringParam("GLOBAL_CONF", "", "Ceph CI global conf")
                stringParam("INVENTORY", "", "Ceph CI Inventory file name")
                booleanParam("ENABLE_FIPS_MODE", false, "Enable FIPS mode")
                booleanParam("ENABLE_FIREWALL", false, "Enable firewall")
                booleanParam("USE_RECIPE_FILE_CUSTOM_CONFIG", true, "Use recipe file custom configs")
                stringParam("CUSTOM_CONFIG", "", "Custom configs with comma separated 'key=value' values")
                stringParam("CUSTOM_IMAGES", "", "Custom images with comma separated values")
                booleanParam("EXECUTE_TESTS", true, "Start test executor run")
                booleanParam("CAPTURE_CEPH_LOGS", false, "Capture ceph logs")
                booleanParam("SKIP_SOS_REPORT", false, "Capture SOS report")
                stringParam("ADDITIONAL_ARGS", "", "Additional cephci arguments with comma separated 'key=value' values")
                choiceParam("CLOUD_TYPE", jobConfig.cloud_type, "Cloud Platform to execute Tests")
                choiceParam("OSP_CRED", jobConfig.ospCreds, "OpenStack project to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "", "EMail Address")
            }
        }
    }
}