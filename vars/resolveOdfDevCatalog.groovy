/**
 * Resolve the Konflux ODF catalog index image for an ODF/OCP minor.
 *
 * Runs scripts/ocs-build.sh from the pipeline SCM checkout (stable-tag digest
 * match → konflux build) and returns the tag-based pull spec.
 *
 * Returns [tag, digest, pullSpec, stableTag]. Throws if the catalog image
 * cannot be resolved on Quay.
 */

def call(Map parameters) {
    def odfMinor = parameters.odfMinor?.toString()?.trim()
    if (!odfMinor) {
        throw new RuntimeException('resolveOdfDevCatalog: odfMinor is required (e.g. 4.22)')
    }
    if (!(odfMinor ==~ /^\d+\.\d+$/)) {
        throw new RuntimeException(
            "resolveOdfDevCatalog: odfMinor must be major.minor (got '${odfMinor}')"
        )
    }

    def ocsBuild = 'scripts/ocs-build.sh'
    def buildId = env.BUILD_NUMBER ?: '0'
    def errFile = "ocs-build-${buildId}.err"
    def outFile = "ocs-build-${buildId}.json"

    try {
        def safeMinor = odfMinor.replace("'", "'\\''")
        def rc = sh(
            script: """
                set -euo pipefail
                bash '${ocsBuild}' --json '${safeMinor}' >'${outFile}' 2>'${errFile}' || exit \$?
            """,
            returnStatus: true
        )

        if (rc != 0) {
            def detail = fileExists(errFile) ? readFile(errFile).trim() : ''
            if (!detail) {
                detail = "ocs-build.sh exited ${rc}"
            }
            throw new RuntimeException(
                "ODF catalog image not found for minor ${odfMinor}: ${detail}"
            )
        }

        def json = readFile(outFile).trim()
        def info = readJSON(text: json)
        def pullSpec = info?.pullSpec?.toString()?.trim()
        if (!pullSpec) {
            throw new RuntimeException(
                "ODF catalog image not found for minor ${odfMinor}: ocs-build.sh returned no pullSpec"
            )
        }

        echo "resolveOdfDevCatalog: ODF ${odfMinor} → ${pullSpec} (tag=${info.tag}, stable=${info.stableTag})"
        return [
            tag       : info.tag?.toString()?.trim() ?: '',
            digest    : info.digest?.toString()?.trim() ?: '',
            pullSpec  : pullSpec,
            stableTag : info.stableTag?.toString()?.trim() ?: '',
        ]
    } finally {
        sh(script: "rm -f '${errFile}' '${outFile}' 2>/dev/null || true", returnStatus: true)
    }
}
