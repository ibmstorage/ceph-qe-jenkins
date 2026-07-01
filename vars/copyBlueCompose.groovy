/*
    Copy the given compose from Artifactory to the local repository mirror
    hosted in IBM Cloud.

    Workflow
        - Copy the tar ball to a temporary location (ensure disk has minimum 20GB free)
        - Uncompress the archive
        - Copy only x86_64 rpms to a separate location
        - Identify the build version and platform
        - SCP the rpms to the mirror repo
        - Ensure cephuser is the owner
        - Execute createrepo as cephuser

    OnEvent
        Failure
            Email the devOps and build team about the failure.

    Notes:
        COMPOSE name is of format IBM-CEPH-{CEPH_MAJOR}.{CEPH_MINOR}-{DATE}.ci.{BUILD_NUMBER}
        REPO_PATH is sys-ceph-team-rpm-local/COMPOSES/
        COMPOSE_FILE is {COMPOSE}.tar.gz
        Uncompressed data would be {OUTPUT}/COMPOSES/{COMPOSE}/
        The folder would have repo files for each platform and a directories for each platform

    Limitation
        Support for x86_64 in IBM Cloud.
*/
import main.groovy.cephJobLib.ibmcConfigs

def call(String composeName) {
    // Define the remote connection
    def remoteConnection = [:]
    remoteConnection.name = "${ibmcConfigs.IBM_NGINX_HOSTNAME}"
    remoteConnection.host = "10.243.80.5"
    remoteConnection.user = "${ibmcConfigs.IBM_NGINX_USER}"
    remoteConnection.allowAnyHosts = true

    withCredentials([
        file(credentialsId: "repo_access_key", variable: "NGINX_ACCESS"),
        string(credentialsId: "gh-ceph-qe-sa-pat", variable: "GH_PAT"),
        string(credentialsId: "artifactory-token", variable: "TAAS_TOKEN")
    ]) {
        remoteConnection.identityFile = "$NGINX_ACCESS"

        def cmdStr = "podman run --rm --security-opt label=disable"
        cmdStr += " -v /data/repos:/data/repos"
        cmdStr += " quay.io/ibm-ceph-qe/repo-sync:ibm-taas"
        cmdStr += " --taas-token " + "$TAAS_TOKEN"
        cmdStr += " --gh-token " + "$GH_PAT"
        cmdStr += " --compose " + composeName

        sshCommand remote: remoteConnection, command: cmdStr
    }
}
