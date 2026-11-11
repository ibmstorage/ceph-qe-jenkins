/*
    OCP + FDF (IBM Fusion Data Foundation) Interop Deployment Pipeline

    Deploy + FIO IO workloads (ceph-interop run-tests). No ocs-ci tests.
    Downstream of IBM BVT when TRIGGER_DATA_FOUNDATION_INTEROP is enabled (sync-blue-ceph-images).
*/
def job = pipelineJob("fdf-interop") {
    displayName("Interop : FDF-Ceph Testing [CICD]")

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
            scriptPath("jobs/pipelines/fdf_interop/Jenkinsfile")
            lightweight(false)
        }

        parameters {
            stringParam(
                "ENV_NAME",
                "",
                "ceph-interop --env-name workspace key (default: fdf-interop-BUILD_NUMBER)"
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

            stringParam(
                "CEPH_IMAGE",
                "",
                "Dev Ceph image to test (REQUIRED)"
            )

            stringParam(
                "RHCS_BUILD_VERSION",
                "",
                "Optional. RHCS version e.g. 9.1 — required for digest-only CEPH_IMAGE"
            )

            choiceParam(
                "OCP_CHANNEL",
                ["nightly", "released"],
                "OCP build channel"
            )

            stringParam(
                "OCP_VERSION",
                "",
                "Optional OCP version override"
            )

            choiceParam(
                "FDF_CHANNEL",
                ["nightly", "released"],
                "FDF build channel when FDF_CATALOG_IMAGE is empty"
            )

            stringParam(
                "FDF_CATALOG_IMAGE",
                "",
                "Optional FDF catalog override — auto-resolved from cp.stg.icr.io when empty"
            )

            stringParam(
                "FDF_OPERATOR_CHANNEL",
                "",
                "Optional OLM operator channel e.g. stable-5.0"
            )

            choiceParam(
                "IBM_REGION",
                ["eu-de"],
                "IBM Cloud region"
            )

            choiceParam(
                "VPC_NAME",
                ["vpc-ceph-integration"],
                "IBM Cloud VPC"
            )

            stringParam(
                "MASTER_COUNT",
                "3",
                "Number of master nodes"
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
                "OSD volume size in GB per worker"
            )

            stringParam(
                "DEVICE_COUNT",
                "3",
                "OSD volumes per worker"
            )

            stringParam(
                "EMAIL_ADDRESS",
                "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com,ebenahar-all-DG@ibm.com,Mudit.Agarwal2@ibm.com,ebenahar@ibm.com,pbalogh@ibm.com",
                "Notification email recipients (comma-separated)"
            )
        }
    }
}
