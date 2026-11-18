/*
    Storage Scale + CES S3 (NooBaa NSFS) deploy and S3 pytest via ceph-qe-zypper.
    Shares jobs/pipelines/scale_ces/Jenkinsfile with scale-ces; defaults target
    deployments/infra/scale/scale-ibm-vpc-ces-s3.yaml and tests/s3.
*/
def job = pipelineJob("scale-ces-s3") {
    displayName("Scale cluster: CES S3 [CICD]")

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
            scriptPath("jobs/pipelines/scale_ces/Jenkinsfile")
            lightweight(false)
        }

        parameters {
            stringParam(
                "ENV_NAME",
                "",
                "ceph-zypper --env-name (default: scale-ces-s3-BUILD_NUMBER)"
            )

            stringParam(
                "ZYPPER_REPO",
                "git@github.com:sunilkumarn417/ceph-qe-zypper.git",
                "ceph-qe-zypper repository URL (prefer git@ SSH on agents-interop)"
            )

            stringParam(
                "ZYPPER_BRANCH",
                "main",
                "Branch of ceph-qe-zypper to checkout"
            )

            stringParam(
                "CONTEXT",
                "deployments/infra/scale/scale-ibm-vpc-ces-s3.yaml",
                "Deploy/test context YAML relative to ceph-qe-zypper checkout"
            )

            choiceParam(
                "INSTALLER_SOURCE",
                ["local", "nfs", "http"],
                "Where the Scale installer is fetched from (default: local)"
            )

            stringParam(
                "INSTALLER_EXPORT",
                "/data/jenkins/workspace/artifacts",
                "Installer file or folder on the agent (local), or NFS export / HTTP URL"
            )

            stringParam(
                "INSTALLER_FILENAME",
                "Storage_Scale_Advanced-6.0.1.2-x86_64-Linux-install",
                "Installer filename when INSTALLER_EXPORT is a folder with multiple installers"
            )

            stringParam(
                "PYTEST_SUITE",
                "tests/s3",
                "ceph-zypper run-tests --suite (post-deploy S3 stage)"
            )

            stringParam(
                "PYTEST_MARKER",
                "s3",
                "pytest -m expression for ceph-zypper run-tests"
            )

            stringParam(
                "PROTOCOLS_LABEL",
                "s3",
                "Email / description protocol label"
            )

            stringParam(
                "TEST_STAGE_LABEL",
                "S3 (pytest)",
                "Email row label for the post-deploy pytest stage"
            )

            stringParam(
                "NODES_LABEL",
                "3 NSD + 1 admin + 2 protocol + 1 S3 client + 1 gpfs client",
                "Email nodes/clients summary"
            )

            booleanParam(
                "FRESH",
                false,
                "Pass --fresh to deploy (delete {env}-* resources first)"
            )

            booleanParam(
                "SKIP_TEARDOWN",
                false,
                "Keep IBM Cloud resources after the run (debug only)"
            )

            booleanParam(
                "VERBOSE",
                false,
                "Pass -v (DEBUG; noisy IBM SDK). Off by default. --log-file is always used."
            )

            choiceParam(
                "SSH_LOG",
                ["full", "summary"],
                "ceph-zypper --ssh-log: full streams remote stdout/stderr live (default); summary announces + 30s Still running. Not Paramiko noise."
            )

            stringParam(
                "SSH_PUBLIC_KEY",
                "",
                "Optional local .pub path on the agent to append via --ssh-public-key"
            )

            stringParam(
                "EMAIL_ADDRESS",
                "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com, mbenjami@ibm.com, dang1@ibm.com, nithya.balachandran@ibm.com, Kalpesh.Arun.Pandya@ibm.com, Samarah.Uriarte@ibm.com",
                "Notification email recipients (comma-separated)"
            )
        }
    }
}
