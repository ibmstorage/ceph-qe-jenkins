/*
    The purpose of the module is to determine the following based on the ceph
    version identified from the image.

    ceph-version
    ceph-release
    base image
*/

def call(String image) {

    // Constants
    final String PROD_REGISTRY = "cp.icr.io"
    final String STG_REGISTRY = "cp.stg.icr.io"
    final String VENDOR = "ibm"

    Map result = [:]

    withCredentials([
        string(credentialsId: "icr-token", variable: "icr_token"),
        string(credentialsId: "stg-icr-token", variable: "stg_icr_token")
    ]) {

        // Login to the registry
        def crLoginStatus = 1
        if ( image.startsWith(PROD_REGISTRY) ) {
            crLoginStatus = sh(
                returnStatus: true,
                script: "podman login ${PROD_REGISTRY} --username cp --password ${icr_token}"
                )
        } else if ( image.startsWith(STG_REGISTRY) ) {
            crLoginStatus = sh(
                returnStatus: true,
                script: "podman login ${STG_REGISTRY} --username cp --password ${stg_icr_token}"
            )
        } else {
            throw new RuntimeException("Unsupported registry. Expected ${PROD_REGISTRY} or ${STG_REGISTRY}")
        }

        if ( crLoginStatus != 0 ) {
            throw new RuntimeException("Unable to pull the specified ceph-base image.")
        }
    }

    // This is IBM specific
    result["vendor"] = VENDOR

    // Pull the ceph-common version
    try {
        sh(script: "podman image pull " + image)
    } catch (Exception e) {
        throw new RuntimeException("Failed to pull image: ${image}. Error: ${e.message}")
    }

    def cephVersion = ""
    try {
        def cephVerCmd = "podman run --rm --entrypoint '' " + image
        cephVerCmd += " rpm -qa --qf '%{version}-%{release}' ceph-common"
        cephVersion = sh(
            returnStdout: true,
            script: cephVerCmd
        ).trim()
    } catch (Exception e) {
        throw new RuntimeException("Failed to extract ceph version from image. Error: ${e.message}")
    }

    // The output would be => 20.1.0-185.el9cp
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
    } else if ( cephVersion.startsWith("20.2") ) {
        result["releaseVersion"] = "9.1"
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

    // This check must be removed once UBI10 base image is supported
    if ( result["release"] == "tentacle" ) {
        result["osPlatform"] = "rhel-10"
    }

    // Cleanup pulled image
    try {
        sh(script: "podman rmi ${image}")
        println("Successfully cleaned up image: ${image}")
    } catch (Exception e) {
        println("Warning: Failed to remove image ${image}. Error: ${e.message}")
    }

    // Logout
    try {
        sh(script:"podman logout --all")
    } catch (Exception e) {
        println("Warning: Failed to logout from registries. Error: ${e.message}")
    }

    // Return the result
    return result
}
