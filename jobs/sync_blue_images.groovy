/*
    Copy the IBM Storage Ceph Container images to local Registry.

    This workflow follows the below steps

        - Accepts a multiline string having the image details.
        - For each provided repo the synchronization is done
        - The ceph version and platform are determined automatically.
*/
def job = pipelineJob("sync-blue-ceph-images") {
    // Display name
    displayName("Infra - Synchronize IBM Storage Ceph Images")

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
            scriptPath("jobs/pipelines/sync_blue_images/Jenkinsfile")
            lightweight(true)
        }

        // Required parameter
        parameters {
            textParam("BLUE_IMAGES", "", "IBM Storage Ceph container images per line.")
            booleanParam("TRIGGER_BVT", false, "Trigger Build Verification test.")
            stringParam("REGION", "eu-de", "Region for repository URL (default: eu-de)")
            // Auto trigger is intended for nightly, rc and respins and would be passed by build team.
            choiceParam("BUILD_TYPE", ["nightly", "rc", "respin"], "Release build type")
        }
    }
}
