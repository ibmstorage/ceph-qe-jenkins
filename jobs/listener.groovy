

// Create job konflux listener
def job = pipelineJob("ceph-build-konflux-listener") {
    // Job display name
    displayName("Infra - Ceph Build Konflux Listener")

    // Job definition from git repo
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
            scriptPath("jobs/pipelines/listener/Jenkinsfile")
            lightweight(false)
        }

        // Required parameters for the job
        parameters {
            stringParam("TEST_KONFLUX_STRING_PARAM", "{}", "Build details string parameter")
        }
    }
}
