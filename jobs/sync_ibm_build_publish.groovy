/*
    Wrapper pipeline to synchronize IBM Storage Ceph images and repos
    and update manifest repository with build information.

    This workflow follows the below steps:
        - Validates input parameters (CEPH_VERSION, BLUE_IMAGES, BLUE_REPOS)
        - Calls sync-blue-ceph-images job to sync container images
        - Calls sync-blue-ceph-repos job to sync repositories
        - Updates manifest repository (ibm/{version}.yaml) with build information in nightly section
        - If RELEASE_CANDIDATE is enabled, also updates rc section
*/
def job = pipelineJob("sync-ibm-build-publish") {
    // Display name
    displayName("Infra - Synchronize IBM Build and Publish")

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
            scriptPath("jobs/pipelines/sync_ibm_build_publish/Jenkinsfile")
            lightweight(true)
        }

        // Parameters
        parameters {
            choiceParam(
                "CEPH_VERSION",
                ["10.0", "9.2", "9.1", "9.0", "8.1", "8.0", "7.1", "7.0", "6.1", "5.3"],
                "Ceph version to update in manifest."
            )
            stringParam(
                "CephBuildVersion",
                "",
                "Ceph build version (required, e.g., 21.1.1-1, 20.2.2-181 or 19.2.0-139)."
            )
            textParam(
                "IMAGES",
                "",
                "Container images per line (format: image_name: image_url or just image_url)."
            )
            textParam(
                "REPOS",
                "",
                "Repository URLs per line (e.g., https://public.dhe.ibm.com/.../IBM-CEPH-9.1-...-rhel9.repo)."
            )
            booleanParam(
                "RELEASE_CANDIDATE",
                false,
                "If enabled, update both 'rc' and 'nightly' sections in the manifest file. Otherwise, only 'nightly' section is updated."
            )
        }
    }
}
