// Job configuration placeholder
def jobsConfiguration = []

// Method to generate required job metadata
def generate_jobs(data) {
    def logLevel = ["DEBUG", "INFO", "ERROR"]
    def vmFlavor = ["cx2-2x4", "cx2-4x8", "cx2-8x16"]
    def vpc = ["ci-vpc-01", "vpc-block", "vpc-cephci", "vpc-cephfs", "vpc-dmfg", "vpc-nfsg", "vpc-rgw"]
    def buildType = ["IBM", "RH"]
    def buildDescription = "IBM or RH build"
    def tag = "Upgrade"

    // Add cephci repo details
    def cephciRepo = "https://github.com/red-hat-storage/cephci.git"
    def cephciBranch = "main"

    // Add the job configurations
    data.jobsConfiguration.add([
        "name": "${data.cephVersion.toLowerCase()}-upgrade-ci",
        "displayName": "${data.cephVersion} - ${tag} Test Pipeline",
        "components": data.components,
        "cephciRepo": cephciRepo,
        "cephciBranch": cephciBranch,
        "logLevel": logLevel,
        "vmFlavor": vmFlavor,
        "tag": [tag],
        "vpc": vpc,
        "buildVersion": data.buildVersion,
        "rhelVersion": data.rhelVersion,
        "release": data.release,
        "buildType": buildType,
        "buildDescription": buildDescription
    ])
}

// Create jobs specific to Ceph - Squid
generate_jobs([
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Squid",
    buildVersion: ["8.1", "8.0"],
    rhelVersion: ["rhel-9"],
    release: ["", "nightly", "stable", "rc"],
    components: ["cephfs", "dmfg", "rados", "nfs", "rgw", "rbd", "nvmeotcp", "smb"]
])

// Create jobs specific to Ceph - Tentacle
generate_jobs([
    jobsConfiguration: jobsConfiguration,
    cephVersion: "Tentacle",
    buildVersion: ["9.0"],
    rhelVersion: ["rhel-9"],
    release: ["nightly", "stable"],
    components: ["cephfs", "dmfg", "rados", "nfs", "rgw", "rbd", "nvmeotcp", "smb"]
])

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
                scriptPath("jobs/pipelines/upgrade_ci/Jenkinsfile")
                lightweight(false)
            }

            // Add the required parameters for the job
            parameters {
                stringParam("PREFIX", "upgrade", "Prefix for instance name")
                choiceParam("TAGS", jobConfig.tag, "Tags for Test Suites")
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
                choiceParam("VM_FLAVOR", jobConfig.vmFlavor, "IBM Cloud virtual machine profile")
                booleanParam("ENABLE_FIPS_MODE", false, "Enable FIPS mode")
                booleanParam("ENABLE_FIREWALL", false, "Enable firewall")
                stringParam("CUSTOM_CONFIG", "", "Custom configs with comma separated 'key=value' values")
                stringParam("CUSTOM_IMAGES", "", "Custom images with comma separated values")
                for (component in jobConfig.components) {
                    booleanParam(component.toUpperCase(), true, "${component.toUpperCase()} for upgrade")
                }
                stringParam("ADDITIONAL_ARGS", "", "Additional cephci arguments with comma separated 'key=value' values")
                choiceParam("VPC_NAME", jobConfig.vpc, "IBM Cloud VPC to create/destroy resources")
                stringParam("EMAIL_ADDRESS", "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com", "Email Address")
            }
        }
    }
}
