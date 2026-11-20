/*
    OCP + ODF Chaos Pipeline (left-shift Krkn)

    Manual "Build with Parameters" only — not triggered by BVT.
    Sibling of odf-interop: same IBM Cloud deploy and RHCS matrix,
    chaos tests instead of FIO + ocs-ci acceptance.
*/
def job = pipelineJob("odf-chaos") {
    displayName("Interop : ODF-Ceph Chaos Testing [CICD]")

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
            scriptPath("jobs/pipelines/odf_chaos/Jenkinsfile")
            lightweight(false)
        }

        parameters {
            stringParam(
                "ENV_NAME",
                "",
                "ceph-interop --env-name workspace key (default: odf-chaos-BUILD_NUMBER). Overrides deprecated CLUSTER_NAME alias."
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
                "Dev Ceph image to test (REQUIRED). Patched onto the ODF operator CSV before chaos."
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
                "Number of worker nodes (must be >= 3 for ODF)"
            )

            stringParam(
                "DEVICE_SIZE",
                "200",
                "OSD volume size in GB. Default 200 (osd-1x200gb / odf-1x200gi): one disk per worker, replica 3, about 200Gi usable for the full chaos suite."
            )

            stringParam(
                "DEVICE_COUNT",
                "1",
                "Data volumes per worker. Default 1 is one OSD disk per worker."
            )

            stringParam(
                "CLUSTER_NAME",
                "",
                "(Deprecated) Alias for ENV_NAME. OpenShift cluster name is auto-generated and read from state.json after deploy."
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
                "",
                "Optional extra pytest / run-ci args on every chaos stage. Empty applies -k 'not high-12pods' (BASIC + STRENGTH; skips random pod-kill high). Set ALL to drop that filter. A value that already contains -k replaces the default filter. Does not replace stage test paths."
            )

            stringParam(
                "EMAIL_ADDRESS",
                "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com,ebenahar-all-DG@ibm.com,Mudit.Agarwal2@ibm.com,ebenahar@ibm.com,pbalogh@ibm.com",
                "Notification email recipients (comma-separated)"
            )
        }
    }
}
