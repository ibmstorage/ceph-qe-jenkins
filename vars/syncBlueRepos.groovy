// Synchronize the given repos
import main.groovy.cephJobLib.ibmcConfigs

def call(Map parameters) {
    // Define the remote connection
    def remoteConnection = [:]
    remoteConnection.name = "${ibmcConfigs.IBM_NGINX_HOSTNAME}"
    remoteConnection.host = "10.243.80.5"
    remoteConnection.user = "${ibmcConfigs.IBM_NGINX_USER}"
    remoteConnection.allowAnyHosts = true

    withCredentials([
        file(credentialsId: "repo_access_key", variable: "NGINX_ACCESS")
    ]) {
        remoteConnection.identityFile = "$NGINX_ACCESS"

        def cmdStr = "podman run --rm --security-opt label=disable"
        cmdStr += " -v /data/repos:/data/repos"
        cmdStr += " quay.io/ibm-ceph-qe/repo-sync:blue "

        // Sync IBM Storage Ceph repo
        parameters.repos.stripIndent().eachLine { repo ->
            def repoCmd = cmdStr + repo.trim()
            sshCommand remote: remoteConnection, command: repoCmd
        }
    }
}
