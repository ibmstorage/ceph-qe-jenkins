package main.groovy.cephJobLib

class cephciConfigs {
    // Default python version
    static final String PYTHON = "python3"

    // Ceph CI git details
    static final String GIT_URL = "https://github.com/red-hat-storage/cephci.git"
    static final String GIT_BRANCH = "main"
    static final String GIT_DIR = "cephci"

    // Default Ceph CI values
    static final String VENV_DIR = ".venv"
    static final String LOG_DIR = "logs"
    static final String LOG_LEVEL = "DEBUG"
    static final String INSTANCE_PREFIX = "cephci"

    // Default inventory config
    static final Map OSP_INVENTORY = [
        "rhel-8": "conf/inventory/rhel-8.10-server-x86_64-large.yaml",
        "rhel-9": "conf/inventory/rhel-9.6-server-x86_64-large.yaml"
    ]
    static final Map IBMC_INVENTORY = [
        "rhel-8": "conf/inventory/ibm-rhel-8.10-server-bx2-4x16.yaml",
        "rhel-9": "conf/inventory/ibm-rhel-9.6-server-bx2-4x16.yaml"
    ]
}
