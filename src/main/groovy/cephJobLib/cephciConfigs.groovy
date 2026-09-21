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

    // Default inventory config (Frankfurt / EU — sanity, BVT, legacy VPCs)
    static final Map IBMC_INVENTORY = [
        "rhel-8": "conf/inventory/ibm-eu-rhel-8.10.yaml",
        "rhel-9": "conf/inventory/ibm-eu-rhel-9-latest.yaml",
        "rhel-10": "conf/inventory/ibm-eu-rhel-10-latest.yaml"
    ]

    // IBM Cloud inventory for released builds
    static final Map IBMC_LIVE_INVENTORY = [
        "rhel-9": "conf/inventory/ibm-eu-live-rhel-9-latest.yaml",
        "rhel-10": "conf/inventory/ibm-eu-live-rhel-10-latest.yaml"
    ]

    // Dallas, London, and Madrid share one inventory per RHEL version.
    // Unlisted VPCs (Frankfurt, Sydney ci-vpc-01, Tokyo, etc.) keep IBMC_INVENTORY (EU).
    static final List REGIONAL_VPCS = [
        "vpc-vivek-dallas",
        "vpc-neha-london",
        "vpc-manohar-madrid",
    ]

    // rhel-9 → 9.8, rhel-10 → 10.2 (same pinning as ibm-eu-rhel-*-latest)
    static final Map IBMC_REGIONAL_INVENTORY = [
        "rhel-9" : "conf/inventory/ibm-rhel-9.8.yaml",
        "rhel-10": "conf/inventory/ibm-rhel-10.2.yaml",
    ]

    /**
     * Resolve IBM Cloud inventory for platform + VPC.
     * Falls back to EU IBMC_INVENTORY when the VPC is not regional or
     * the platform is missing (e.g. rhel-8).
     */
    static String resolveIbmcInventory(String platform, String vpcName = null) {
        if (vpcName && REGIONAL_VPCS.contains(vpcName)) {
            def regional = IBMC_REGIONAL_INVENTORY.get(platform)
            if (regional) {
                return regional
            }
        }
        return IBMC_INVENTORY[platform]
    }

    // AWS inventory config (for --cloud aws)
    static final Map AWS_INVENTORY = [
        "rhel-9": "conf/inventory/aws-rhel-9.6-server-arm-64.yaml",
        "rhel-10": "conf/inventory/aws-rhel-10.1-server-arm-64.yaml"
    ]

    // Default AWS EC2 instance type (e.g. t3.small, t3.medium, m5.xlarge)
    static final String AWS_INSTANCE_TYPE = "t3.small"
}
