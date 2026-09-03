/*
    The purpose of the module is to determine the following based on the ceph
    version identified from the image.

    ceph-version
    ceph-release
    base image
*/

def call(String image) {

    if (!image?.toString()?.trim()) {
        throw new RuntimeException("Mandatory parameter 'image' is missing or empty")
    }

    Map result = [:]
    def ref = image.toLowerCase()
    if (ref.contains("ibm-ceph")) {
        result["vendor"] = "ibm"
        result["buildType"] = "IBM"
    } else if (ref.contains("rh-osbs") || ref.contains("rhceph")) {
        result["vendor"] = "redhat"
        result["buildType"] = "RH"
    } else {
        throw new RuntimeException(
            "Unsupported ceph base image vendor for: ${image}. " +
            "Expected image reference to contain ibm-ceph, rhceph, or rh-osbs."
        )
    }

    def cephVersion = ""
    try {
        withCredentials([file(credentialsId: "all-cr-auth", variable: "CR_AUTH_FILE")]) {

            sh(script: "podman pull --authfile \${CR_AUTH_FILE} " + image)

            def cephVerCmd = "podman run --rm --entrypoint '' " + image
            cephVerCmd += " rpm -qa --qf '%{version}-%{release}' ceph-common"
            cephVersion = sh(
                returnStdout: true,
                script: cephVerCmd
            ).trim()
            echo("Debug: The ceph-common version found in " + image + " is " + cephVersion)
        }
    } catch (Exception e) {
        throw new RuntimeException("Failed to process ceph base image ${image}: ${e.message}")
    }

    if (!cephVersion) {
        throw new RuntimeException("No ceph-common version found in image: ${image}")
    }

    // cephVersion is the raw rpm output e.g. "20.2.1-185.el9cp"
    // result["cephVersion"] strips the trailing .el*cp suffix  e.g. "20.2.1-185"
    // The raw string is used below for startsWith/endsWith checks; callers use result["cephVersion"]
    def lastDotIndex = cephVersion.lastIndexOf(".")
    if ( lastDotIndex != -1 ) {
        result["cephVersion"] = cephVersion.substring(0, lastDotIndex)
    } else {
        throw new RuntimeException("Invalid ceph version format: ${cephVersion}")
    }

    // Gather the release name
    if ( cephVersion.startsWith("16.2.") ){
        // Release name pacific
        result["releaseVersion"] = "5.3"
        result["release"] = "pacific"
    } else if ( cephVersion.startsWith("17.2") ) {
        //Release name quincy
        result["releaseVersion"] = "6.1"
        result["release"] = "quincy"
    } else if ( cephVersion.startsWith("18.2.0") ) {
        result["releaseVersion"] = "7.0"
        result["release"] = "reef"
    } else if ( cephVersion.startsWith("18.2.1") ) {
        result["releaseVersion"] = "7.1"
        result["release"] = "reef"
    } else if ( cephVersion.startsWith("19.2.0") ) {
        result["releaseVersion"] = "8.0"
        result["release"] = "squid"
    } else if ( cephVersion.startsWith("19.2.1") ) {
        result["releaseVersion"] = "8.1"
        result["release"] = "squid"
    } else if ( cephVersion.startsWith("20.1") ) {
        result["releaseVersion"] = "9.0"
        result["release"] = "tentacle"
    } else if ( cephVersion.startsWith("20.2.1") ) {
        result["releaseVersion"] = "9.1"
        result["release"] = "tentacle"
    } else if ( cephVersion.startsWith("20.2.2") ) {
        result["releaseVersion"] = "9.2"
        result["release"] = "tentacle"
    } else {
        throw new RuntimeException("Error: unknown cephVersion was returned." + cephVersion)
    }

    // Set the platform -> by default we run against the base image
    if ( cephVersion.endsWith("el8cp") ) {
        result["osPlatform"] = "rhel-8"
    } else if ( cephVersion.endsWith("el9cp") ) {
        result["osPlatform"] = "rhel-9"
    } else if ( cephVersion.endsWith("el10cp") ) {
        result["osPlatform"] = "rhel-10"
    }

    // ToDO: This check must be removed once UBI10 base image is supported
    if ( result["release"] == "tentacle" ) {
        result["osPlatform"] = "rhel-10"
    }

    if ( !result["osPlatform"] ) {
        throw new RuntimeException(
            "Unable to determine osPlatform from ceph version: ${cephVersion}. " +
            "Expected version string to end with el8cp, el9cp, or el10cp."
        )
    }

    // Cleanup pulled image
    // Auth was done via authfile (not podman login), so no logout is needed
    try {
        sh(script: "podman rmi ${image}")
        println("Successfully cleaned up image: ${image}")
    } catch (Exception e) {
        println("Warning: Failed to remove image ${image}. Error: ${e.message}")
    }

    // Return the result
    return result
}
