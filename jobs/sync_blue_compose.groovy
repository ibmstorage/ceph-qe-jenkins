/*
    The purpose of this workflow is to synchronize the local mirror hosted in
    IBM Cloud with the new blue build RPM images. This workflow is triggered
    by Ceph Build team via the mirror compose SPS (Secure Pipeline Service)
    job.
*/
def job = pipelineJob("sps-triggered-job-for-compose") {
    // Display Name
    displayName("Infra - Copy IBM Storage Ceph RPMs from Artifactory")

    definition {
        cpsScm {
            scm {
                git {
                    branch("*/main")
                    remote {
                        url("https://github.com/ibmstorage/ceph-qe-jenkins.git")
                        credentials("ceph-qe-sa-pat")
                    }
                }
            }
            scriptPath("jobs/pipelines/sync_blue_compose/Jenkinsfile")
            lightweight(true)
        }

        parameters {
            stringParam("COMPOSE_NAME", "", "Unique compose identifier")
        }
    }
}
