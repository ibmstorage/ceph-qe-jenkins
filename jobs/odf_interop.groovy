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
                        url("https://github.com/ibmstorage/ceph-qe-jenkins.git")
                        credentials("gh-ceph-qe-sa-pat")
                    }
                }
            }
            scriptPath("jobs/pipelines/odf_interop/Jenkinsfile")
            lightweight(false)
        }

        parameters {
            stringParam(
                "ENV_NAME",
                "",
                "ceph-interop --env-name workspace key (default: interop-BUILD_NUMBER). Overrides deprecated CLUSTER_NAME alias."
            )

            stringParam(
                "CEPH_INTEROP_REPO",
                "https://github.com/ibmstorage/ceph-qe-interop.git",
                "Ceph Interop repository URL"
            )

            stringParam(
                "CEPH_INTEROP_BRANCH",
                "main",
                "Branch of the ceph-interop repository to checkout (default: main)"
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
                "Optional OLM operator channel e.g. stable-5.0. Leave empty with OCP_VERSION for auto-resolve stable-{odfMinor} from matrix."
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
                "MASTER_COUNT",
                "3",
                "Number of master (control plane) nodes"
            )

            stringParam(
                "WORKER_COUNT",
                "3",
                "Number of worker nodes (min 3 when EC_SCHEME=false). When EC_SCHEME is 2+2 or 4+2, workers are raised to k+m+1 (5 or 7) if this value is lower."
            )

            choiceParam(
                "EC_SCHEME",
                ["false", "2+2", "4+2"],
                "Day-1 erasure coding for default RBD, CephFS, and RGW pools. false keeps replica defaults. 2+2 uses 5 workers; 4+2 uses 7 workers (unless WORKER_COUNT is already higher)."
            )

            booleanParam(
                "ENABLE_FIPS",
                false,
                "Install OCP with FIPS (openshift-install-fips). Requires OCP ≥ 4.16. Sets openshift profile fips: true in the per-build configuration."
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
                "(Deprecated) Alias for ENV_NAME. OpenShift cluster name is auto-generated and read from state.json after deploy."
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
                "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com,ebenahar-all-DG@ibm.com,Mudit.Agarwal2@ibm.com,ebenahar@ibm.com,pbalogh@ibm.com",
                "Notification email recipients (comma-separated)"
            )
        }
    }
}
