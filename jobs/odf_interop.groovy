/*
    OCP + ODF Interop Deployment Pipeline

    Deploys OpenShift Container Platform with OpenShift Data Foundation
    in external mode using an internal Ceph cluster on IBM Cloud.

    Steps:
        - Clones the ceph-interop repository
        - Sets up Python virtual environment and credentials
        - Validates all required parameters and credentials
        - Deploys OCP cluster on IBM Cloud VPC (RHCOS + ignition via Nginx)
        - Configures DNS via AWS Route53
        - Installs ODF operator and creates internal Ceph StorageCluster
        - Patches Ceph image on StorageCluster
        - Runs IO verification workloads (FIO on RBD + CephFS)
        - Tears down all resources (mandatory)
*/
def job = pipelineJob("odf-interop") {
    // Job display name
    displayName("Interop : ODF-Ceph Testing [CICD]")

    // Job definition from git repo
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
            scriptPath("jobs/pipelines/odf_interop/Jenkinsfile")
            lightweight(false)
        }

        // Required parameters for the job
        parameters {
            stringParam(
                "CEPH_INTEROP_REPO",
                "https://github.ibm.com/ceph-qe-ng/ceph-interop.git",
                "Ceph Interop repository URL"
            )

            stringParam(
                "CEPH_INTEROP_BRANCH",
                "main",
                "Branch of the ceph-interop repository to checkout"
            )

            // ── OCP Configuration ─────────────────────────────────────
            stringParam(
                "OCP_VERSION",
                "",
                "OpenShift version (e.g. 4.21.10). Leave empty with ODF_CHANNEL empty to auto-resolve via CEPH_IMAGE / RHCS_BUILD_VERSION"
            )

            choiceParam(
                "OCP_CHANNEL",
                ["released", "nightly"],
                "OCP release channel"
            )

            // ── ODF Configuration ─────────────────────────────────────
            stringParam(
                "ODF_CHANNEL",
                "",
                "ODF operator channel (e.g. stable-4.21). Leave empty with OCP_VERSION empty for auto-resolve via CEPH_IMAGE / RHCS_BUILD_VERSION"
            )

            stringParam(
                "ODF_NIGHTLY_INDEX",
                "",
                "ODF nightly catalog index image (leave empty for released)"
            )

            stringParam(
                "CEPH_IMAGE",
                "",
                "Ceph version image to test (REQUIRED). e.g. registry.stage.redhat.io/rhceph/rhceph-9-rhel9:9-1768259913"
            )

            stringParam(
                "RHCS_BUILD_VERSION",
                "",
                "RHCS build version (e.g. 9.1, 8.0) (OPTIONAL)"
            )

            // ── IBM Cloud Environment ────────────────────────────────
            choiceParam(
                "IBM_REGION",
                ["eu-de"],
                "IBM Cloud region for deployment"
            )

            choiceParam(
                "VPC_NAME",
                ["vpc-ceph-integration"],
                "IBM Cloud VPC for cluster resources"
            )

            stringParam(
                "SSH_KEY_NAMES",
                "ceph-qe-sa,ceph-qe-sa-svc",
                "Comma-separated IBM Cloud SSH key names for VSI access (e.g. ceph-qe-sa,ceph-qe-sa-svc)"
            )

            // ── Cluster Sizing ───────────────────────────────────────
            stringParam(
                "MASTER_COUNT",
                "3",
                "Number of master (control plane) nodes"
            )

            stringParam(
                "WORKER_COUNT",
                "3",
                "Number of worker nodes (must be >= 3 for ODF)"
            )

            stringParam(
                "DEVICE_SIZE",
                "50",
                "Size of each data volume per worker in GB (for Ceph OSDs)"
            )

            stringParam(
                "DEVICE_COUNT",
                "3",
                "Number of data volumes per worker node"
            )

            // ── Pipeline Control ─────────────────────────────────────
            stringParam(
                "CLUSTER_NAME",
                "",
                "Cluster name — auto-generated (leave empty). Pipeline always runs: deploy → workload → teardown"
            )

            stringParam(
                "PVC_COUNT",
                "10",
                "Number of PVCs per storage type (RBD and CephFS) for IO workloads"
            )

            stringParam(
                "EMAIL_ADDRESS",
                "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com,Mudit.Agarwal2@ibm.com,ebenahar@ibm.com,pbalogh@ibm.com",
                "Notification email recipients (comma-separated)"
            )
        }
    }
}
