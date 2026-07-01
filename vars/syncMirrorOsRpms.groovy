// Syncrhonize the Mirror with Red Hat CDN
import main.groovy.cephJobLib.ibmcConfigs

def call(Map parameters) {
    println("Inside SyncMirrorOsRpms")
    // Define the remote connnections
    def remoteConnection = [:]
    remoteConnection.name = "${ibmcConfigs.IBM_NGINX_HOSTNAME}"
    remoteConnection.host = "10.243.80.5"
    remoteConnection.user = "${ibmcConfigs.IBM_NGINX_USER}"
    remoteConnection.allowAnyHosts = true

    withCredentials([
        file(credentialsId: "repo_access_key", variable: "NGINX_ACCESS")
    ]) {
        remoteConnection.identityFile = "$NGINX_ACCESS"

        def baseCmdString = "podman run --rm --security-opt label=disable"
        baseCmdString += " -v /data/repos:/data/repos"
        baseCmdString += " quay.io/ibm-ceph-qe/repo-sync:rhel"

        // Sync RHEL 8.10 repo
        def rhel8Cmd = baseCmdString + "8"
        sshCommand remote: remoteConnection, command: rhel8Cmd

        // Sync RHEL 9.6 repo
        def rhel9Cmd = baseCmdString + "9"
        sshCommand remote: remoteConnection, command: rhel9Cmd

        //Sync RHEL 10 repo
        def rhel10Cmd = baseCmdString + "10"
        sshCommand remote: remoteConnection, command: rhel10Cmd
    }
}
