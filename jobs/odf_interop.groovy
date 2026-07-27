/*
    OCP + ODF Interop Deployment Pipeline

    BVT downstream triggers target this job (odf-interop).
*/
def job = pipelineJob("odf-interop") {
    displayName("Interop : ODF-Ceph Testing [CICD]")

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

            // ── Ceph (required) ─────────────────────────────────────
            stringParam(
                "CEPH_IMAGE",
                "",
                "Dev Ceph image to test (REQUIRED)"
            )

            stringParam(
                "RHCS_BUILD_VERSION",
                "",
                "Optional. RHCS version e.g. 9.1 — required for digest-only CEPH_IMAGE when version cannot be inferred"
            )

            // ── OCP ─────────────────────────────────────────────────
            choiceParam(
                "OCP_CHANNEL",
                ["nightly", "released"],
                "OCP build channel (default: nightly). released = latest GA stable for matrix minor."
            )

            stringParam(
                "OCP_VERSION",
                "",
                "Optional OCP version override. Leave empty to auto-resolve from CEPH_IMAGE + OCP_CHANNEL."
            )

            // ── ODF ─────────────────────────────────────────────────
            choiceParam(
                "ODF_CHANNEL",
                ["nightly", "released"],
                "ODF build channel when ODF_CATALOG_IMAGE is empty (default: nightly). Ignored if ODF_CATALOG_IMAGE is set."
            )

            stringParam(
                "ODF_CATALOG_IMAGE",
                "",
                "Optional ODF catalog index image — takes precedence over ODF_CHANNEL. e.g. quay.io/rhceph-dev/ocs-registry:4.22.0-105.konflux"
            )

            stringParam(
                "ODF_OPERATOR_CHANNEL",
                "",
                "Optional OLM operator channel e.g. stable-4.22. Leave empty with OCP_VERSION for auto-resolve stable-{minor} from matrix."
            )

            // ── IBM Cloud ───────────────────────────────────────────
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

            stringParam(
                "CLUSTER_NAME",
                "",
                "Cluster name — auto-generated (leave empty). Pipeline always runs: deploy → workload → teardown"
            )

            stringParam(
                "PVC_COUNT",
                "2",
                "Number of PVCs per storage type (RBD and CephFS) for IO workloads"
            )

            stringParam(
                "OCS_CI_REPO",
                "https://github.com/red-hat-storage/ocs-ci.git",
                "ocs-ci Git repository (run-ci / pytest tests)"
            )

            stringParam(
                "OCS_CI_BRANCH",
                "master",
                "ocs-ci branch to clone"
            )

            stringParam(
                "OCS_VERSION",
                "",
                "OCS/ODF version passed to run-ci --ocs-version (e.g. 4.16). Empty = minor version from resolved ODF channel"
            )

            stringParam(
                "OCS_CI_TESTS",
                "-m acceptance",
                "Pytest / run-ci args before the tests/ path (e.g. -m acceptance). See ocs-ci usage: https://ocs-ci.readthedocs.io/en/latest/usage.html — pipeline also passes --html=report.html --self-contained-html for the HTML report"
            )

            stringParam(
                "EMAIL_ADDRESS",
                "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com,Mudit.Agarwal2@ibm.com,ebenahar@ibm.com,pbalogh@ibm.com",
                "Notification email recipients (comma-separated)"
            )
        }
    }
}
