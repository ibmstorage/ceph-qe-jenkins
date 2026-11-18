/*
    Storage Scale + CES deployment and sanity pipeline (ceph-qe-zypper).
*/
def job = pipelineJob("scale-ces") {
    displayName("Scale cluster: CES Sanity [CICD]")

    definition {
        cpsScm {
            scm {
                git {
                    branch("*/main")
                    remote {
                        url("https://github.com/ibmstorage/ceph-qe-jenkins.git")
                        // Same credential as bvt_ci / most IBM Cloud jobs (not gh-ceph-qe-sa-pat).
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
                "ceph-zypper --env-name (default: scale-ces-{BUILD_NUMBER})"
            )

            stringParam(
                "ZYPPER_REPO",
                // Private fork: agent SSH (id_ed25519) works; HTTPS needs a PAT with access.
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
                "deployments/infra/scale/scale-ibm-vpc-ces.yaml",
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
                "tests/sanity",
                "ceph-zypper run-tests --suite (post-deploy)"
            )

            stringParam(
                "PYTEST_MARKER",
                "sanity",
                "pytest -m expression for ceph-zypper run-tests"
            )

            stringParam(
                "PROTOCOLS_LABEL",
                "nfs, smb",
                "Email / description protocol label"
            )

            stringParam(
                "TEST_STAGE_LABEL",
                "Sanity (pytest)",
                "Email row label for the post-deploy pytest stage"
            )

            stringParam(
                "NODES_LABEL",
                "4 Scale (NSD+protocol) + 1 protocol client + 1 gpfs client",
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
