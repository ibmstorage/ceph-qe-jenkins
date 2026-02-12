/*
    Synchronize RHEL OS RPMS from CDN repository.

    The images are not built as part of this workflow.

    Salient points
        - This is a timed workflow
        - Keeps the specified mirror repository up-to-date
*/

// Create the job definition
def job = pipelineJob("sync-mirror-os") {
    displayName("Infra - Synchronize Mirror repo - RHEL rpms")

    definition {
        cpsScm {
            scm {
                git {
                    branch("*/main")
                    remote {
                        url("https://github.ibm.com/ceph-qe-ng/jenkins.git")
                        credentials("ceph-qe-sa-pat")
                    }
                }
            }
            scriptPath("jobs/pipelines/sync_mirror/Jenkinsfile")
            lightweight(true)
        }
    }
}
