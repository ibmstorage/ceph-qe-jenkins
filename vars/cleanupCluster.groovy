// This script is used to clean up a Ceph cluster in a cloud environment.

def call(Map parameters) {
    // Check if Ceph CI directory present
    def gitDir = parameters.get("gitDir")
    if ( ! gitDir || ! fileExists(gitDir) ) {
        throw new RuntimeException(
            "Ceph CI directory '${gitDir}' does not exists"
        )
    }

    // Check if virtual environment directory present
    def venvDir = parameters.get("venvDir")
    if ( ! venvDir || ! fileExists("${gitDir}/${venvDir}") ) {
        throw new RuntimeException(
            "Ceph CI virtual environment directory '${venvDir}' " +
            "does not exists"
        )
    }

    // Check for mandatory parameters
    def cloudType = parameters.get("cloudType")
    if ( ! cloudType ) {
        throw new RuntimeException(
            "Ceph CI requires 'cloudType' parameter to execute tests"
        )
    }

    // Check for mandatory parameters
    def instanceName = parameters.get("instanceName")
    if ( ! instanceName ) {
        throw new RuntimeException(
            "CephCI requires 'instances-name' parameter to execute tests"
        )
    }

    // Check for cloud credentials
    def ibmcCredFile = parameters.get("ibmcCredFile")
    if ( ! ibmcCredFile ) {
        throw new RuntimeException(
            "Ceph CI requires IBMC credential file to execute tests"
        )
    }

    // Check for VPC Name (IBM Cloud only; AWS uses credentials in osp-cred)
    def vpcName = parameters.get("vpcName")
    if ( cloudType == "ibmc" && ! vpcName ) {
        throw new RuntimeException("Ceph CI cleanup stage requires VPC Name for IBM Cloud.")
    }

    // Create arguments for run.py
    def cmd = [
        "${venvDir}/bin/python run.py",
        "--cloud ${cloudType}",
        "--osp-cred ${ibmcCredFile}",
        "--cleanup ${instanceName}"
    ]

    // Add IBM Cloud VPC config (not used for AWS)
    if ( cloudType == "ibmc" && vpcName ) {
        cmd += ["--custom-config ibmc_vpc=${vpcName}"]
    }

    // Add optional parameters
    def logLevel = parameters.get("logLevel")
    if ( logLevel ) {
        cmd += ["--log-level ${logLevel}"]
    }

    // Add exit status
    cmd += [
        "|| exit 0"  // exit 0 in case test case failed for unexpected errors
    ]

    // Start cephci run
    dir(gitDir) {
        def executeCmd = cmd.join(" ")
        sh executeCmd
    }
}
