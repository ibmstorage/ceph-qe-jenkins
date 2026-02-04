/*
    JIRA Reporter pipeline jobs.

    Creates one Jenkins pipeline job per report in jira-reporter repo.
*/
def jiraReporterJobs = [
    [
        name: "jira-reporter-arrivals-vs-resolved",
        displayName: "JIRA Reporter - Arrivals vs Resolved",
        scriptPath: "pipeline/jira-arrivals-vs-resolved.groovy",
        schedule: "10 1 * * *"
    ],
    [
        name: "jira-reporter-average-qa-score",
        displayName: "JIRA Reporter - Average QA Score",
        scriptPath: "pipeline/jira-average-qa-score.groovy",
        schedule: "20 1 * * *"
    ],
    [
        name: "jira-reporter-blocker-resolution-time",
        displayName: "JIRA Reporter - Blocker Resolution Time",
        scriptPath: "pipeline/jira-blocker-resolution-time.groovy",
        schedule: "30 1 * * *"
    ],
    [
        name: "jira-reporter-component-distribution",
        displayName: "JIRA Reporter - Component Distribution",
        scriptPath: "pipeline/jira-component-distribution.groovy",
        schedule: "40 1 * * *"
    ],
    [
        name: "jira-reporter-customer-bugs-tracking",
        displayName: "JIRA Reporter - Customer Bugs Tracking",
        scriptPath: "pipeline/jira-customer-bugs-tracking.groovy",
        schedule: "50 1 * * *"
    ],
    [
        name: "jira-reporter-pqi-report",
        displayName: "JIRA Reporter - PQI Report",
        scriptPath: "pipeline/jira-pqi-report.groovy",
        schedule: "30 2 * * *"
    ],
    [
        name: "jira-reporter-qe-backlog",
        displayName: "JIRA Reporter - QE Backlog",
        scriptPath: "pipeline/jira-qe-backlog.groovy",
        schedule: "40 2 * * *"
    ],
    [
        name: "jira-reporter-release-readiness-criteria",
        displayName: "JIRA Reporter - Release Readiness Criteria",
        scriptPath: "pipeline/jira-release-readiness-criteria.groovy",
        schedule: "50 2 * * *"
    ],
    [
        name: "jira-reporter-scale-n-perf-defects",
        displayName: "JIRA Reporter - Scale & Performance Defects",
        scriptPath: "pipeline/jira-scale-n-perf-defects.groovy",
        schedule: "0 3 * * *"
    ],
    [
        name: "jira-reporter-tracking-dashboard",
        displayName: "JIRA Reporter - Tracking Dashboard",
        scriptPath: "pipeline/jira-tracking-dashboard.groovy",
        schedule: "10 3 * * *"
    ]
]

for (jobConfig in jiraReporterJobs) {
    def job = pipelineJob(jobConfig.name) {
        displayName(jobConfig.displayName)

        triggers {
            cron(jobConfig.schedule)
        }

        definition {
            cpsScm {
                scm {
                    git {
                        branch("*/main")
                        remote {
                            url("https://github.ibm.com/ceph-qe-ng/jira-reporter.git")
                            credentials("ceph-qe-sa-pat")
                        }
                    }
                }
                scriptPath(jobConfig.scriptPath)
                lightweight(true)
            }
        }
    }
}
