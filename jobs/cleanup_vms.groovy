/*
    Cleanup Virtual Machines from IBM Cloud
    This workflow performs the following:
        - Generates a CSV report of VM records from IBM Cloud
        - Identifies orphan/stale VM entries
        - Optionally deletes orphan VM entries
    The script runs utility/ibm_vm_cleanup.py from cephci repository
*/

def job = pipelineJob("cleanup_vms") {
    // Display name
    displayName("Infra - Virtual Machine Cleanup Utility")

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
            scriptPath("jobs/pipelines/cleanup_vms/Jenkinsfile")
            lightweight(true)
        }

        // Required parameters
        parameters {
            booleanParam("DELETE", true, "Delete orphan/stale VM entries after generating report")
            booleanParam("DRY_RUN", false, "Generate the report and log intended deletes without removing VMs")
            stringParam("CEPHCI_REPO", "https://github.com/red-hat-storage/cephci.git", "Ceph CI git repo")
            stringParam("BRANCH", "main", "Ceph CI git branch")
        }
    }

    triggers {
        cron('0 10 * * *')  // Daily at 10:00 AM
    }
}
