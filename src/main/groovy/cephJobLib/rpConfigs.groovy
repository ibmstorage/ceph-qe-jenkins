package main.groovy.cephJobLib

class rpConfigs {
    // Default RP URLs
    static final String HOSTNAME = "reportportal-ocs3.apps.dno.ocp-hub.prod.psi.redhat.com"
    static final String HOST_URL = "https://reportportal-ocs3.apps.dno.ocp-hub.prod.psi.redhat.com"
    static final String DATA_ROUTER_URL = "https://datarouter.ccitredhat.com"
    static final String DATA_ROUTER_IMAGE = "quay.io/dno/droute:latest"

    // Project specific urls
    static final String RHCS_LANUCH_URL = "https://reportportal-ocs3.apps.dno.ocp-hub.prod.psi.redhat.com/ui/#rhcs/launches/all"
    static final String IBM_LANUCH_URL = "https://reportportal-ocs3.apps.dno.ocp-hub.prod.psi.redhat.com/ui/#ibmceph/launches/all"
    static final String UPSTREAM_LANUCH_URL = "https://reportportal-ocs3.apps.dno.ocp-hub.prod.psi.redhat.com/ui/#upstream/launches/all"

    // Default RP project names
    static final String RHCS_PROJECT_NAME = "rhcs"
    static final String IBM_PROJECT_NAME = "ibmceph"
    static final String UPSTREAM_PROJECT_NAME = "upstream"

    // Default config paths
    static final String RP_CONFIG = "rp_config.json"
}
