package main.groovy.cephJobLib

class jobConfigs {
    // Default prefix configs
    static final int PREFIX_LENGTH = 6

    // Default job path
    static final String JOB_DIR_PATH = "/data/jenkins/ceph-builds"

    // Default result summary name
    static final String RESULTS_SUMMARY = "results.json"

    // Default email template & address
    static final String CEPHCI_EMAIL = "ceph-qe-sa@ibm.com"
    static final String EMAIL_TEMPLATE = "result-email-template.html.j2"
}
