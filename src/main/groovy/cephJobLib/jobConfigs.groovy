package main.groovy.cephJobLib

class jobConfigs {
    // Default prefix configs
    static final int PREFIX_LENGTH = 6

    // Set OSP credentials default
    static final String OSP_CRED_PREFIX = "osp-cred"
    static final String IBMC_CRED_PREFIX = "ibmc-cred"
    static final String OSP_CRED_FILE = "osp-creds.yaml"

    // Default job path
    static final String JOB_DIR_PATH = "/home/jenkins/ceph-builds"

    // Default result summary name
    static final String RESULTS_SUMMARY = "results.json"

    // Default email template & address
    static final String CEPHCI_EMAIL = "ceph-qe-sa@ibm.com"
    static final String EMAIL_TEMPLATE = "result-email-template.html.j2"

    // Recipe file default configs
    static final String RECIPE_PATH = "/home/jenkins/magna002/cephci-jenkins/latest-rhceph-container-info"
    static final String RH_RECIPE_PREFIX = "RHCEPH"
    static final String IBM_RECIPE_PREFIX = "IBMCEPH"
    static final String UPSTREAM_RECIPE_PREFIX = "UPSTREAM"

    // Set polarion default values
    static final String POLARION_XML = "polarion.xml"
    static final String POLARION_URL = "https://polarion.engineering.redhat.com/polarion/import/xunit"
    static final String POLARION_TEST_RUN = "https://polarion.engineering.redhat.com/polarion/#/project/CEPH/testrun?id="
}
