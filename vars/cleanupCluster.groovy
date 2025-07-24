// This script is used to clean up a Ceph cluster in a cloud environment.


def call(Map parameters) {
    // Get Ceph CI environment parameters
    def python = parameters.get("pythonVersion", "python3")

    // Get optional parameters
    def cloudType = parameters.get("cloudType", "ibmc").toLowerCase()
    def logLevel = parameters.get("logLevel", "DEBUG")
    def gitDir = parameters.get("gitDir", "cephci")

    // Validate environment based on cloud type
    if (!fileExists(gitDir)) {
        throw new RuntimeException("Ceph CI directory '${gitDir}' does not exists")
    }

    // Check for mandatory parameters
    def instanceName = parameters.get("instanceName")
    if (!instanceName) {
        throw new RuntimeException("CephCI requires 'instances-name' parameter to execute tests")
    }

    // Check for cloud credentials
    def ospCredFile = parameters.get("ospCredFile")
    if (!ospCredFile) {
        throw new RuntimeException("Ceph CI requires OSP credential file to execute tests")
    }


    // Create arguments for run.py
    def cmd = [
        "cd", gitDir, "&&",
        ".venv/bin/python", "run.py",
        "--cloud", cloudType,
        "--log-level", logLevel,
        "--osp-cred", ospCredFile,
        "--cleanup", instanceName,
        "||", "exit 0"      // exit 0 in case test case failed for unexpected errors
    ]

    // Execute command
    sh cmd.join(" ")
}
