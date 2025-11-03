import main.groovy.cephJobLib.ibmcConfigs


def call(Map parameters) {
    // Check for build Version and platform
    def buildVersion = parameters.get("buildVersion")
    def platform = parameters.get("platform")
    def cephVersion = parameters.get("cephVersion")
    if (!buildVersion || !platform || !cephVersion) {
        throw new RuntimeException(
            "Mandatory build parameters are not provided"
        )
    }

    // Set NGINX repo path and log path
    def nginxRepoPath = "${ibmcConfigs.IBM_NGINX_REPO_PATH}"
    def nginxLogPath = "${ibmcConfigs.IBM_NGINX_LOG_PATH}"

    // Set IBM Cloud Object Storage bucket
    def ibmCosBucket = ""
    if (buildVersion.startsWith("8")) {
        ibmCosBucket = "ceph-8"
        nginxRepoPath += "/8"
    } else if (buildVersion.startsWith("9")) {
        ibmCosBucket = "ceph-9"
        nginxRepoPath += "/9"
    } else {
        error("Unsupported build version ${buildVersion}")
    }

    // Append the platform to the bucket name
    if (platform == "rhel-8") {
        ibmCosBucket += "-rhel8"
        nginxRepoPath += "/rhel8"
    } else if (platform == "rhel-9") {
        ibmCosBucket += "-rhel9"
        nginxRepoPath += "/rhel9"
    } else if (platform == "rhel-10") {
        ibmCosBucket += "-rhel10"
        nginxRepoPath += "/rhel10"
    } else {
        error("Unsupported rhel platform ${platform}")
    }

    // Create the rsync command
    def rsyncCmd = "/usr/local/bin/rclone sync "
    rsyncCmd += "${ibmcConfigs.IBM_COS_CRN}:${ibmCosBucket} ${nginxRepoPath} "
    rsyncCmd += "--disable partials "
    rsyncCmd += "--progress "
    rsyncCmd += "--log-level DEBUG "
    rsyncCmd += "--log-file ${nginxLogPath}/rclone-ceph-${cephVersion}-build-${env.BUILD_NUMBER}.log"

    // Create repo command
    def createrepoCmd = "createrepo ${nginxRepoPath}/${cephVersion}/Tools "
    createrepoCmd += "--outputdir ${nginxRepoPath}/${cephVersion}/Tools"

    // Define the remote server connection
    def remote = [:]
    remote.name = "${ibmcConfigs.IBM_NGINX_HOSTNAME}"
    remote.host = "${ibmcConfigs.IBM_NGINX_HOST}"
    remote.user = "${ibmcConfigs.IBM_NGINX_USER}"
    remote.allowAnyHosts = true
    remote.sudo = true

    withCredentials([
        file(
            credentialsId: "repo_access_key", variable: "NGINX_SERVER_KEY"
        )
    ]) {
        // Set remote identity key
        remote.identityFile = "${NGINX_SERVER_KEY}"

        // Execute the rsync command on the remote server
        println("Remote execution to pull rpm packages")
        sshCommand remote: remote, command: rsyncCmd

        // Execute the createrepo command on the remote server
        println("Remote execution of createrepo")
        sshCommand remote: remote, command: createrepoCmd
    }
}
