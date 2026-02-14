// This script is used to set up the Ceph CI repository and environment.

def call(Map parameters) {
    // Get Ceph CI environment parameters
    def python = parameters.get("python")
    def venvDir = parameters.get("venvDir")

    // Get git repo parameters
    def gitUrl = parameters.get("gitUrl")
    def gitDir = parameters.get("gitDir")
    def gitBranch = parameters.get("gitBranch")
    def ibmcCredFile = parameters.get("ibmcCredFile")
    def credentialId = parameters.get("credentialId", "osp-cred")

    // Get cloud type
    def cloudType = parameters.get("cloudType")

    // Validate mandatory parameters
    if (!gitUrl || !gitDir || !gitBranch || !python || !cloudType || !venvDir || !ibmcCredFile) {
        currentBuild.result = "ABORTED"
        error(
            "Mandatory parameters 'gitUrl', 'gitDir', 'gitBranch', " +
            "'python', 'cloudType', 'venvDir', and 'ibmcCredFile' are required"
        )
    }

    // Checkout cephci to agent
    checkout([
        $class: "GitSCM",
        branches: [[name:  "${gitBranch}" ]],
        userRemoteConfigs: [[ url:  "${gitUrl}"]],
        extensions: [
            [$class: "RelativeTargetDirectory", relativeTargetDir: "${gitDir}"],
            [$class: "CloneOption", depth: 1, noTags: true, shallow: true, timeout: 10, reference: ""]
        ],
        submoduleCfg: []
    ])

    // Write the global credentials file
    withCredentials([file(credentialsId: "cephci-conf", variable: "ciConf")]) {
        // Fixme: When credentials change then we have a problem.
        sh("cp -n \$ciConf /home/jenkins/.cephci.yaml")
    }

    dir("${gitDir}") {
        echo "ibmcCredFile path is: ${ibmcCredFile}"
        // Write the platform access file (osp-cred for ibmc, osp-cred-aws for aws)
        withCredentials([file(credentialsId: credentialId, variable: "ospCred")]) {
            sh("cp -n \$ospCred ${ibmcCredFile}")
        }

        // Setup virtual environment
        def cmdStr = [
            "${python} -m venv ${venvDir}",
            "${venvDir}/bin/python -m pip install --upgrade pip",
            "${venvDir}/bin/python -m pip install -r requirements.txt"
        ].join(" && ")
        sh(script: cmdStr)
    }
}
