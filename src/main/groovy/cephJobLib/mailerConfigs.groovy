package main.groovy.cephJobLib

class mailerConfigs {
    static final String MAILER_BRANCH = "main"
    static final String MAILER_URL = "https://github.ibm.com/ceph-qe-ng/resource_monitoring.git"
    static final String IBMC_LOG_FILE = "time_summary.html"
    static final String IBMC_MAILER_SUBJECT = "IBM Cloud Cost Report - Auto generated at "
    static final String MAILER_TO = "ibm-ceph-qe-devops-alerts@wwpdl.vnet.ibm.com"
    static final String MAILER_FROM = "ceph-qe-sa@ibm.com"
    static final String AWS_LOG_FILE = "aws_time_report.html"
    static final String AWS_MAILER_SUBJECT = "AWS Cost Report - Auto generated at "
}
