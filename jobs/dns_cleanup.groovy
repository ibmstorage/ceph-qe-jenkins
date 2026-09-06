/*
    Cleanup DNS entries from IBM Cloud DNS

    This workflow performs the following:
        - Generates a CSV report of DNS records from IBM Cloud
        - Identifies orphan/stale DNS entries
        - Optionally deletes orphan DNS entries

    The script runs utility/ibm_dns_report.py from cephci repository
*/

def job = pipelineJob("dns-cleanup") {
    // Display name
    displayName("Infra - DNS Cleanup Utility")

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
            scriptPath("jobs/pipelines/dns_cleanup/Jenkinsfile")
            lightweight(true)
        }

        // Required parameters
        parameters {
            booleanParam("DELETE", true, "Delete orphan/stale DNS entries after generating report")
            booleanParam("DRY_RUN", false, "Show what would be deleted without actually deleting")
            stringParam("CEPHCI_REPO", "https://github.com/red-hat-storage/cephci.git", "Ceph CI git repo")
            stringParam("BRANCH", "main", "Ceph CI git branch")
        }
    }
    triggers {
        cron('30 00 * * *')
    }
}
