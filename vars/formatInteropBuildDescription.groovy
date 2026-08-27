/**
 * Multi-line build description for ODF/FDF interop jobs.
 *
 * Example:
 *   Ceph Version: 20.2.1-181.el9cp
 *   ODF: 4.22.999-3.konflux
 *   OCP: 4.23.0-0.nightly-2026-08-18-112820
 *   Env: odf-interop-322
 */
def call(Map parameters = [:]) {
    def product = (parameters.product ?: env.INTEROP_PRODUCT ?: 'odf').toString().toLowerCase()
    def envName = (parameters.envName ?: env.ENV_NAME ?: '').trim()
    def ocpVersion = (parameters.ocpVersion ?: env.RESOLVED_OCP_VERSION ?: '').trim()
    def cephImage = (parameters.cephImage ?: '').trim()
    def catalogImage = (parameters.catalogImage ?: '').trim()
    def catalogTag = (parameters.catalogTag ?: '').trim()
    def cephVersion = (parameters.cephVersion ?: '').trim()

    if (!catalogImage) {
        catalogImage = (env.RESOLVED_ODF_CATALOG_IMAGE ?: env.RESOLVED_FDF_CATALOG_IMAGE ?: '').trim()
    }
    if (!catalogTag) {
        catalogTag = product == 'fdf'
            ? (env.RESOLVED_FDF_CATALOG_TAG ?: '').trim()
            : (env.RESOLVED_ODF_CATALOG_TAG ?: '').trim()
    }
    if (!cephVersion) {
        cephVersion = readCephVersionFromMetadata()
    }

    def lines = []
    lines << (cephVersion ? "Ceph Version: ${cephVersion}" : "Ceph: ${imageTag(cephImage)}")

    def productLabel = product == 'fdf' ? 'FDF' : 'ODF'
    lines << "${productLabel}: ${catalogLabel(catalogTag, catalogImage)}"

    lines << "OCP: ${ocpVersion ?: 'N/A'}"

    if (envName) {
        lines << "Env: ${envName}"
    }

    return lines.join('\n')
}

private String catalogLabel(String catalogTag, String catalogImage) {
    if (catalogTag) {
        return catalogTag
    }
    def parsed = parseCatalogTagFromPullSpec(catalogImage)
    if (parsed) {
        return parsed
    }
    def operatorChannel = (env.RESOLVED_ODF_OPERATOR_CHANNEL ?: env.RESOLVED_ODF_CHANNEL ?: '').trim()
    if (operatorChannel) {
        return operatorChannel
    }
    return 'N/A'
}

private String parseCatalogTagFromPullSpec(String pullSpec) {
    if (!pullSpec?.trim()) {
        return ''
    }
    def ref = pullSpec.split('/')[-1].trim()
    def tagMatcher = (ref =~ /:([^@]+)$/)
    if (tagMatcher.find()) {
        return tagMatcher.group(1)
    }
    def digestMatcher = (ref =~ /@sha256:([a-f0-9]+)/)
    if (digestMatcher.find()) {
        return digestMatcher.group(1).take(8)
    }
    return ''
}

private String readCephVersionFromMetadata() {
    try {
        def jsonPath = "${env.WORKSPACE}/.cephadm-resolve-${env.BUILD_NUMBER ?: '0'}.json"
        if (!fileExists(jsonPath)) {
            return ''
        }
        def meta = readJSON(file: jsonPath)
        return meta?.ceph_git_nice_ver?.toString()?.trim() ?: ''
    } catch (Exception ignored) {
        return ''
    }
}

private String imageTag(String image) {
    if (!image?.trim()) {
        return 'N/A'
    }
    return image.split('/')[-1].trim()
}
