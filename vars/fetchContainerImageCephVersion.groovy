// Per-classloader cache; safe for immutable image refs within a pipeline run.
def containerImageCephVersionCache = [:].asSynchronized()

def call(Map parameters) {

    def containerImage = parameters.get("containerImage")
    if (!containerImage?.toString()?.trim()) {
        throw new RuntimeException("Mandatory parameter 'containerImage' is missing or empty")
    }

    synchronized (containerImageCephVersionCache) {
        if (containerImageCephVersionCache.containsKey(containerImage)) {
            echo "INFO: Using cached Ceph version for image: ${containerImage}"
            return containerImageCephVersionCache[containerImage]
        }
    }

    def authRegistries = parameters.get("authRegistries", [
        "registry.stage.redhat.io",
        "registry.redhat.io",
        "cp.stg.icr.io",
        "cp.icr.io"
    ])

    echo "INFO: Fetching Ceph version from image: ${containerImage}"

    if (sh(script: 'command -v podman >/dev/null 2>&1', returnStatus: true) != 0) {
        throw new RuntimeException("podman is not available on this agent")
    }

    withCredentials([file(credentialsId: "all-cr-auth", variable: "CR_AUTH_FILE")]) {

        def registry = containerImage.contains("/") ? containerImage.split("/")[0] : ""

        def runCmd = authRegistries.contains(registry) ?
        """
        podman run --rm \
        --authfile ${CR_AUTH_FILE} \
        --entrypoint sh ${containerImage} \
        -c "rpm -qa | grep -E 'ceph-(base|common)'"
        """
        :
        """
        podman run --rm \
        --entrypoint sh ${containerImage} \
        -c "rpm -qa | grep -E 'ceph-(base|common)'"
        """

        def startMs = System.currentTimeMillis()
        def result = ""
        timeout(time: 5, unit: 'MINUTES') {
            retry(3) {
                result = sh(script: runCmd, returnStdout: true).trim()
            }
        }
        echo "INFO: fetchContainerImageCephVersion podman completed in ${System.currentTimeMillis() - startMs} ms"

        if (!result) {
            throw new RuntimeException(
                "No output from container. Possible image pull/auth issue: ${containerImage}"
            )
        }

        echo "DEBUG: RPM output:\n${result}"

        def matchedLine = result.readLines().find { l ->
            parseCephVersionReleaseFromRpmLine(l) != null
        }

        if (!matchedLine) {
            throw new RuntimeException(
                "No ceph-base/ceph-common package found in image: ${containerImage}\nOutput:\n${result}"
            )
        }

        def version = parseCephVersionReleaseFromRpmLine(matchedLine)
        synchronized (containerImageCephVersionCache) {
            containerImageCephVersionCache[containerImage] = version
        }
        return version
    }
}

/**
 * Parse version-release from rpm -qa lines such as:
 *   ceph-common-19.2.3-47.el9cp
 *   ceph-common-2:19.2.3-47.el9cp.x86_64
 */
String parseCephVersionReleaseFromRpmLine(String rpmLine) {
    def line = rpmLine?.trim()
    if (!line || !(line.startsWith("ceph-base-") || line.startsWith("ceph-common-"))) {
        return null
    }
    def remainder = line.replaceFirst(/^ceph-(base|common)-/, "")
    remainder = remainder.replaceFirst(/^\d+:/, "")
    def m = (remainder =~ /^(\d+\.\d+\.\d+-\d+)/)
    return m.find() ? m[0][1] : null
}
