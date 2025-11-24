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
    static final String CLOUD_TYPE = "ibmc"
    static final String VM_FLAVOR = "bx2-2x8"
    static final String VPC_NAME = "vpc-cephci"

    // Default inventory config
    static final Map IBMC_INVENTORY = [
        "rhel-8": "conf/inventory/ibm-eu-rhel-8.10.yaml",
        "rhel-9": "conf/inventory/ibm-eu-rhel-9-latest.yaml",
        "rhel-10": "conf/inventory/ibm-eu-rhel-10-latest.yaml"
    ]
}
