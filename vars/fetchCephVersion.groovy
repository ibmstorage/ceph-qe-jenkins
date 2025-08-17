// Method to fetch ceph version using compose base url


def call(Map parameters) {
    // Define required commands
    def podmanRun = "podman run --rm"
    def rhel8UbiImage = "registry.access.redhat.com/ubi8/ubi"
    def rhel9UbiImage = "registry.access.redhat.com/ubi9/ubi"
    def rhel10UbiImage = "registry.access.redhat.com/ubi10/ubi"
    def execCmd = "sh -c"
    def yumAddRepo = "dnf config-manager -q --add-repo"
    def curlAddRepo = "curl -L -o /etc/yum.repos.d/upstream.repo"
    def infoCephCommon = "dnf repoquery -q --qf '%{version}-%{release}' ceph-common | cut -d '.' -f -3"

    // Define command arguments
    def cmdArgs = [podmanRun]

    // Get rhel version
    def platform = parameters.get("platform")
    if ( ! platform ) {
        throw new RuntimeException("Mandatory parameter 'platform' does not exists")
    } else if ( platform.startsWith("rhel-10") ) {
        cmdArgs += rhel10UbiImage
    } else if ( platform.startsWith("rhel-9") ) {
        cmdArgs += rhel9UbiImage
    } else if ( platform.startsWith("rhel-8") ) {
        cmdArgs += rhel8UbiImage
    } else {
        throw new RuntimeException("Unsupported platform '${platform}'")
    }

    // Get ceph repo
    def rhsCephRepo = parameters.get("rhsCephRepo")
    if ( ! rhsCephRepo ) {
        throw new RuntimeException("Mandatory parameter 'rhsCephRepo' does not exists")
    }

    // Check for rhs ceph repo
    def buildType = parameters.get("buildType")
    if ( ! buildType ) {
        throw new RuntimeException("Mandatory parameter 'buildType' does not exists")
    }

    // Define args for yum command based on build type
    def yumCmdArgs = []
    if ( buildType.toLowerCase() == "upstream" ) {
        yumCmdArgs = ["${curlAddRepo} ${rhsCephRepo}"]
    } else {
        yumCmdArgs = ["${yumAddRepo} ${rhsCephRepo}"]
    }

    // Add install package command
    yumCmdArgs += [infoCephCommon]

    // Get package install comnmand
    yumCmdArgs = yumCmdArgs.join(" && ")

    // Get final command
    cmdArgs += ["${execCmd} \"${yumCmdArgs}\" || exit 0"]

    // Install repository on container
    def cmdStr = cmdArgs.join(" ")
    def result = sh(returnStdout: true, script: cmdStr)

    return result
}
