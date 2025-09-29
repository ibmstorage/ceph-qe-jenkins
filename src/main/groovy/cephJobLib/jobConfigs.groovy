package main.groovy.cephJobLib

class jobConfigs {
    // Default python version
    static final String PYTHON = "python3"

    // Default prefix configs
    static final int PREFIX_LENGTH = 4

    // Default job path
    static final String JOB_DIR_PATH = "/data/jenkins/ceph-builds"
    static final String MANIFEST_DIR_PATH = "/data/jenkins/ceph-builds/manifest"

    // Default result summary name
    static final String RESULTS_SUMMARY = "results.json"
    static final String ARTIFACTS = "artifacts.json"
    static final String EMAIL_BODY = "email.html"
    static final String RESULTS_DIR = "results_dir"

    // Default email template & address
    static final String CEPHCI_EMAIL = "ceph-qe-sa@ibm.com"
    static final String EMAIL_TEMPLATE = "result-email.html.j2"
}
