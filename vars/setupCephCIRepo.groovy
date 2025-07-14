// This script is used to set up the Ceph CI repository and environment.


def call(Map parameters) {
    // Get Ceph CI environment parameters
    def python = parameters.get("pythonVersion", "python3")

    // Get git repo parameters
    def gitUrl = parameters.get("gitUrl", "https://github.com/red-hat-storage/cephci.git")
    def gitDir = parameters.get("gitDir", "cephci")
    def gitBranch = parameters.get("gitBranch", "main")
    def cloudType = parameters.get("cloudType", "ibmc").toLowerCase()

    // Setup virtual environment
    def venvCmd = [
        "cd", gitDir, "&&", python, "-m", "venv", ".venv", "&&",
        ".venv/bin/python", "-m", "pip", "install", "--upgrade", "pip", "&&",
        ".venv/bin/python", "-m", "pip", "install", "-r", "requirements.txt"
    ]

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

    // Check for cloud credentials
    def ospCredFile = parameters.get("ospCredFile")
    if (cloudType in ["openstack", "ibmc"]) {
        def ospCredId = parameters.get("ospCredId")
        if (!(ospCredId && ospCredFile)) {
            throw new RuntimeException("Ceph CI requires OSP credential file to execute tests")
        }

        // Read OSP Credentials contents
        withCredentials([file(credentialsId: ospCredId, variable: "OSP_CRED")]) {
            def ospCredContent = readFile file: "${OSP_CRED}"
            writeFile file: ospCredFile, text: "${ospCredContent}"
        }
    }

    // Execute command
    sh venvCmd.join(" ")
}