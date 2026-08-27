/**
 * Resolve the IBM FDF catalog index image for an OCP/ODF minor version.
 *
 * Runs scripts/fdf-catalog.sh (skopeo list-tags on cp.stg.icr.io) and returns
 * the tag-based pull spec. Requires pullSecretFile for ICR authentication.
 *
 * Parameters:
 *   odfMinor       - required, e.g. 4.22
 *   pullSecretFile - required, path to registry auth JSON (OpenShift pull secret)
 *
 * Returns [tag, pullSpec].
 * Throws if no matching tag exists for the requested minor version.
 */
def call(Map parameters) {
    def odfMinor = parameters.odfMinor?.toString()?.trim()
    if (!odfMinor) {
        throw new RuntimeException('resolveFdfCatalog: odfMinor is required (e.g. 4.20)')
    }
    if (!(odfMinor ==~ /^\d+\.\d+$/)) {
        throw new RuntimeException(
            "resolveFdfCatalog: odfMinor must be major.minor (got '${odfMinor}')"
        )
    }

    def pullSecretFile = parameters.pullSecretFile?.toString()?.trim() ?: ''
    if (!pullSecretFile) {
        throw new RuntimeException('resolveFdfCatalog: pullSecretFile is required for ICR catalog lookup')
    }
    if (!fileExists(pullSecretFile)) {
        throw new RuntimeException("resolveFdfCatalog: pull secret not found at ${pullSecretFile}")
    }

    def fdfCatalogScript = 'scripts/fdf-catalog.sh'
    def buildId = env.BUILD_NUMBER ?: '0'
    def errFile = "fdf-catalog-${buildId}.err"
    def outFile = "fdf-catalog-${buildId}.json"

    try {
        def safeMinor = odfMinor.replace("'", "'\\''")
        def safeAuth = pullSecretFile.replace("'", "'\\''")
        def rc = sh(
            script: """
                set -euo pipefail
                bash '${fdfCatalogScript}' --authfile '${safeAuth}' --json '${safeMinor}' >'${outFile}' 2>'${errFile}' || exit \$?
            """,
            returnStatus: true
        )

        def stderr = fileExists(errFile) ? readFile(errFile).trim() : ''
        if (stderr) {
            echo "resolveFdfCatalog [fdf-catalog.sh stderr]:\n${stderr}"
        }

        if (rc != 0) {
            def detail = stderr ?: "fdf-catalog.sh exited ${rc}"
            throw new RuntimeException(
                "FDF catalog image not found for minor ${odfMinor}: ${detail}"
            )
        }

        def json = readFile(outFile).trim()
        def info = readJSON(text: json)
        def pullSpec = info?.pullSpec?.toString()?.trim()
        if (!pullSpec) {
            throw new RuntimeException(
                "FDF catalog image not found for minor ${odfMinor}: fdf-catalog.sh returned no pullSpec"
            )
        }

        def tag = info?.tag?.toString()?.trim() ?: ''
        echo "resolveFdfCatalog: ${odfMinor} -> ${pullSpec}"
        return [tag: tag, pullSpec: pullSpec]
    } finally {
        sh "rm -f '${outFile}' '${errFile}' 2>/dev/null || true"
    }
}
