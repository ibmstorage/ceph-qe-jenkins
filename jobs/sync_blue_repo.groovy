/*
    Copy the IBM Storage Ceph build repo to the local mirror.

    This workflow follows the below steps

        - Accepts a comma seperate string holding repository information.
        - For each provided repo the synchronization is done
        - The ceph version and platform are determined automatically.
*/
def job = pipelineJob("sync-blue-ceph-repos") {
    // Display name
    displayName("Infra - Synchronize IBM Storage Ceph Repos")

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
            scriptPath("jobs/pipelines/sync_blue_repo/Jenkinsfile")
            lightweight(true)
        }

        // Required parameter
        parameters {
            textParam("BLUE_REPOS", "", "A IBM Storage Ceph repo per line.")
        }
    }
}
