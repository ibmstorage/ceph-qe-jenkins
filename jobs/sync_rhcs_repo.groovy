"""
Job to sync Red Hat Repository to mirror repository.
"""

// Set default job configuration
def buildVersion = ["9.1", "9.0", "9.2", "8.1", "8.0", "7.1"]
def rhelVersion = ["rhel-10", "rhel-9"]

// Create job to sync RHCS repo
def job = pipelineJob("sync-rhcs-repo") {
    // Job display name
    displayName("Infra - Synchronize Red Hat Ceph rpms")

    // Job definition from git repo
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
            scriptPath("jobs/pipelines/sync_rhcs_repo/Jenkinsfile")
            lightweight(false)
        }

        // Required parameters for the job
        parameters {
            choiceParam("BUILD_VERSION", buildVersion, "Build Version")
            choiceParam("RHEL_VERSION", rhelVersion, "RHEL OS Version")
            stringParam("CEPH_VERSION", "", "Ceph Version")
        }
    }
}
