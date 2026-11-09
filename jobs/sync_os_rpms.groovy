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
                        url("https://github.com/ibmstorage/ceph-qe-jenkins.git")
                        credentials("gh-ceph-qe-sa-git")
                    }
                }
            }
            scriptPath("jobs/pipelines/sync_mirror/Jenkinsfile")
            lightweight(true)
        }
    }
}
