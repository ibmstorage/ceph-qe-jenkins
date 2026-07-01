/**
 * Check if a container image exists in the registry
 *
 * This function uses skopeo inspect to verify image availability without downloading.
 * Can be used independently or before syncing images.
 *
 * @param imageUrl Full image URL (e.g., "registry.redhat.io/rhceph/rhceph-5-rhel8:5.3")
 * @return true if image exists in registry, false otherwise
 *
 * Example usage:
 *   if (checkImageInRegistry("registry.redhat.io/rhceph/rhceph-5-rhel8:5.3")) {
 *       println("Image is available")
 *   }
 */

def call(String imageUrl) {
    try {
        // Use skopeo inspect to check if image exists without downloading
        def dockerImage = "docker://${imageUrl}"

        // Use credentials for authentication (consistent with syncBlueImages)
        def exitCode = 1
        withCredentials([file(credentialsId: "all-cr-auth", variable: "CR_AUTH_FILE")]) {
            def skopeoCmd = "skopeo inspect --tls-verify=true --authfile \${CR_AUTH_FILE} ${dockerImage}"

            // Execute skopeo inspect command
            exitCode = sh(
                script: skopeoCmd,
                returnStatus: true
            )
        }

        if (exitCode == 0) {
            println("Image ${imageUrl} is available in registry")
            return true
        } else {
            println("Image ${imageUrl} is NOT available in registry (exit code: ${exitCode})")
            return false
        }
    } catch (Exception e) {
        println("Error checking image ${imageUrl}: ${e.getMessage()}")
        return false
    }
}
