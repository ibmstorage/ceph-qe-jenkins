package main.groovy.cephJobLib

class jobConfigs {
    // Default prefix configs
    static final int PREFIX_LENGTH = 6

    // Set OSP credentials default
    static final String IBMC_CRED_PREFIX = "ibmc-cred"
    static final String OSP_CRED_FILE = "osp-creds.yaml"

    // Default job path
    static final String JOB_DIR_PATH = "/data/jenkins/ceph-builds"

    // Default result summary name
    static final String RESULTS_SUMMARY = "results.json"

    // Default email template & address
    static final String CEPHCI_EMAIL = "ceph-qe-sa@ibm.com"
    static final String EMAIL_TEMPLATE = "result-email-template.html.j2"
}
