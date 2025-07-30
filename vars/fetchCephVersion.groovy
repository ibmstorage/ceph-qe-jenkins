// Method to fetch ceph version using compose base url


def call(Map parameters) {
    // Define required commands
    def podmanRun = "podman run -it --rm"
    def rhel8UbiImage = "registry.access.redhat.com/ubi8/ubi"
    def rhel9UbiImage = "registry.access.redhat.com/ubi9/ubi"
    def execCmd = "sh -c"
    def yumAddRepo = "dnf config-manager --add-repo"
    def curlAddRepo = "curl -L -o /etc/yum.repos.d/upstream.repo"
    def ibmLicense = "yum install --nogpgcheck -y ibm-storage-ceph-license && touch /usr/share/ibm-storage-ceph-license/accept"
    def installCephCommon = "yum install ceph-common -q --nogpgcheck -y"

    // Define command arguments
    def cmdArgs = [podmanRun]

    // Get rhel version
    def platform = parameters.get("platform")
    if (!platform) {
        throw new RuntimeException("Mandatory parameter 'platform' does not exists")
    } else if (platform.startsWith("rhel-9")) {
        cmdArgs += rhel9UbiImage
    } else if (platform.startsWith("rhel-8")) {
        cmdArgs += rhel8UbiImage
    } else {
        throw new RuntimeException("Unsupported platform '${platform}'")
    }

    // Get ceph repo
    def rhsCephRepo = parameters.get("rhsCephRepo")
    if (!rhsCephRepo){
        throw new RuntimeException("Mandatory parameter 'rhsCephRepo' does not exists")
    }

    // Check for rhs ceph repo
    def buildType = parameters.get("buildType")
    if (!buildType){
        throw new RuntimeException("Mandatory parameter 'buildType' does not exists")
    }

    // Define args for yum command based on build type
    def yumCmdArgs = []
    if (buildType.toLowerCase() == "upstream") {
        yumCmdArgs = ["${curlAddRepo} ${rhsCephRepo}"]
    } else {
        yumCmdArgs = ["${yumAddRepo} ${rhsCephRepo}"]
    }

    // Check for IBM build
    if (buildType == "IBM") {
        yumCmdArgs += [ibmLicense]
    }

    // Add install package command
    yumCmdArgs += [installCephCommon]

    // Get package install comnmand
    yumCmdArgs = yumCmdArgs.join(" && ")

    // Get final command
    cmdArgs += ["${execCmd} \"${yumCmdArgs}\" || exit 0"]

    // Install repository on container
    def result = sh(returnStdout: true, script: cmdArgs.join(" "))

    // Get ceph build version based on package installed provided
    def build = result  =~ /.*ceph-common-(\d):(.*).el.*/
    if (!build.find()) {
        throw new RuntimeException("Failed to get ceph version from url ${rhsCephRepo}")
    }

    return build[0][2]
}
