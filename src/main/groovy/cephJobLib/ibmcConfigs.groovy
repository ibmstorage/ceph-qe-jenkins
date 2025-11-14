package main.groovy.cephJobLib

class ibmcConfigs {
    // Default python version
    static final String IBM_COS_CRN = "ceph-qe-cos"
    static final String IBM_COS_LOG_BUCKET = "ceph-qe-logs"

    static final String IBM_COMPOSE_URL = "http://10.243.80.5/repos/ceph/testing"

    static final String IBM_NGINX_REGISTRY_HOST = "nginx-vm-01.qe.ceph.lab"
    static final String IBM_NGINX_REGISTRY_PORT = "5000"
    static final String REDHAT_BREW_REGISTRY_HOST = "brew.registry.redhat.io"

    static final String IBM_NGINX_HOSTNAME = "nginx-vm-01.qe.ceph.lab"
    static final String IBM_NGINX_HOST = "149.81.216.83"
    static final String IBM_NGINX_USER = "cephuser"
    static final String IBM_NGINX_REPO_PATH = "/data/repos/ceph/testing/redhat"
    static final String IBM_NGINX_LOG_PATH = "/data/logs/repos"
}
