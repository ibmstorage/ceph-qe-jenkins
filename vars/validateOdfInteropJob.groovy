/**
 * Validate and resolve ODF interop job parameters.
 *
 * Three axes:
 *   CEPH_IMAGE (required)
 *   OCP_CHANNEL: nightly (default) | released
 *   ODF_CHANNEL: nightly (default) | released — ignored when ODF_CATALOG_IMAGE is set
 *
 * ODF_CATALOG_IMAGE takes precedence over ODF_CHANNEL when provided.
 *
 * Populates env.RESOLVED_* and returns a result map. Sets env.PIPELINE_SKIP on
 * unsupported RHCS (caller should exit NOT_BUILT).
 */

def call(Map parameters) {
    def params = parameters.params ?: [:]
    def product = (parameters.product ?: params.INTEROP_PRODUCT ?: env.INTEROP_PRODUCT ?: 'odf')
        .toString().trim().toLowerCase()
    def pullSecretFile = parameters.pullSecretFile?.toString()?.trim() ?: ''
    def defaults = parameters.defaults ?: [:]

    def service = resolveServiceParams(product, params, defaults)

    def defaultOcpChannel = (defaults.ocpChannel ?: 'nightly').toString().trim()

    def errors = []
    def cephImg = params.CEPH_IMAGE?.trim()
    def ocpVer = params.OCP_VERSION?.trim()
    def ocpBuildChannel = (params.OCP_CHANNEL ?: defaultOcpChannel).toString().trim()
    def odfBuildChannel = service.buildChannel
    def odfCatalogImage = service.catalogImage
    def odfOperatorChannel = service.operatorChannel
    def rhcsBv = params.RHCS_BUILD_VERSION?.trim() ?: ''

    validateBuildChannel(ocpBuildChannel, 'OCP_CHANNEL')
    validateBuildChannel(odfBuildChannel, service.channelLabel)

    def result = [
        cephImage          : cephImg,
        ocpVersion         : '',
        ocpChannel         : ocpBuildChannel,
        ocpPullSpec        : '',
        odfOperatorChannel : '',
        odfBuildChannel    : odfBuildChannel,
        odfCatalogImage    : '',
        odfCatalogTag      : '',
        ocsVersion         : '',
        matrixKey          : '',
        skip               : false,
        errors             : errors,
    ]

    if (!cephImg) {
        errors << 'CEPH_IMAGE is required — provide the dev Ceph image to test'
        populateEnv(result)
        return result
    }

    def explicitMode = ocpVer || odfOperatorChannel
    if (ocpVer && !odfOperatorChannel) {
        errors << "Set ${service.operatorLabel} with OCP_VERSION, or leave both empty for auto-resolve"
    } else if (!ocpVer && odfOperatorChannel) {
        errors << "Set OCP_VERSION with ${service.operatorLabel}, or leave both empty for auto-resolve"
    }

    if (errors) {
        populateEnv(result)
        return result
    }

    try {
        if (explicitMode) {
            applyExplicitResolution(result, ocpVer, odfOperatorChannel, ocpBuildChannel, odfBuildChannel, odfCatalogImage, product, pullSecretFile, errors)
        } else {
            applyAutoResolution(result, cephImg, rhcsBv, ocpBuildChannel, odfBuildChannel, odfCatalogImage, product, pullSecretFile, errors)
        }
    } catch (Exception e) {
        if (e.message?.startsWith('ODF catalog image not found') || e.message?.startsWith('FDF catalog image not found')) {
            errors << e.message
        } else {
            errors << "Could not auto-resolve OCP/${product == 'fdf' ? 'FDF' : 'ODF'}: ${e.message}. " +
                "Set RHCS_BUILD_VERSION (e.g. 9.1) for digest-only images, or set OCP_VERSION + ${service.operatorLabel}."
        }
    }

    validateClusterParams(params, errors, product)
    validateCredentials(parameters.credentials ?: [:], errors)

    populateEnv(result)
    return result
}

def applyExplicitResolution(
    Map result,
    String ocpVer,
    String odfOperatorChannel,
    String ocpBuildChannel,
    String odfBuildChannel,
    String odfCatalogImage,
    String product,
    String pullSecretFile,
    List errors
) {
    result.ocpVersion = ocpVer
    result.ocpChannel = ocpBuildChannel
    result.odfOperatorChannel = odfOperatorChannel
    result.ocsVersion = parseOdfMinor(odfOperatorChannel)
    if (!result.ocsVersion) {
        errors << "Could not parse ${product == 'fdf' ? 'FDF' : 'ODF'} minor from ${product == 'fdf' ? 'FDF_OPERATOR_CHANNEL' : 'ODF_OPERATOR_CHANNEL'} '${odfOperatorChannel}'"
        return
    }

    def catalog = resolveCatalogImage(
        product, odfCatalogImage, odfBuildChannel, result.ocsVersion,
        product == 'fdf' ? 'FDF_CATALOG_IMAGE' : 'ODF_CATALOG_IMAGE',
        product == 'fdf' ? 'FDF_CHANNEL' : 'ODF_CHANNEL',
        pullSecretFile
    )
    result.odfCatalogImage = catalog.pullSpec
    result.odfCatalogTag = catalog.tag
    echo "Using explicit OCP_VERSION / ${product == 'fdf' ? 'FDF_OPERATOR_CHANNEL' : 'ODF_OPERATOR_CHANNEL'} (OCP_CHANNEL=${ocpBuildChannel}, ${product == 'fdf' ? 'FDF_CHANNEL' : 'ODF_CHANNEL'}=${odfBuildChannel})"
}

def applyAutoResolution(
    Map result,
    String cephImg,
    String rhcsBv,
    String ocpBuildChannel,
    String odfBuildChannel,
    String odfCatalogImage,
    String product,
    String pullSecretFile,
    List errors
) {
    def inf = null
    if (pullSecretFile) {
        inf = resolveOdfInteropParams(
            inferAuto: true,
            product: product,
            cephImage: cephImg,
            rhcsBuildVersion: rhcsBv,
            ocpChannel: ocpBuildChannel,
            odfBuildChannel: odfBuildChannel,
            odfCatalogImage: odfCatalogImage,
            pullSecretFile: pullSecretFile,
            defaults: [ocpChannel: ocpBuildChannel, odfChannel: odfBuildChannel],
        )
    } else {
        errors << 'pullSecretFile is required for auto-resolution'
        return
    }

    if (!inf) {
        def img = cephImg.split('/')[-1].take(40)
        currentBuild.description = "Skipped: unsupported RHCS version (supported: 8.0, 8.1, 9.0, 9.1, 9.2) | Ceph: ${img}"
        result.skip = true
        return
    }

    result.ocpVersion = inf.ocpVersion
    result.ocpChannel = inf.ocpChannel
    result.ocpPullSpec = inf.ocpPullSpec ?: ''
    result.odfOperatorChannel = inf.odfOperatorChannel ?: inf.odfChannel
    result.ocsVersion = inf.ocsVersion
    result.matrixKey = inf.matrixKey
    result.odfCatalogImage = inf.odfCatalogImage ?: ''
    result.odfCatalogTag = parseCatalogTagFromPullSpec(result.odfCatalogImage)

    // Fallback ODF nightly catalog when resolveOdfInteropParams left it empty (released channel)
    if (product == 'odf' && !result.odfCatalogImage && odfBuildChannel == 'nightly') {
        def catalog = resolveOdfDevCatalog(odfMinor: result.ocsVersion)
        result.odfCatalogImage = catalog.pullSpec
        result.odfCatalogTag = catalog.tag ?: parseCatalogTagFromPullSpec(catalog.pullSpec)
    }

    def productLabel = product == 'fdf' ? 'FDF' : 'ODF'
    echo "Auto-resolved OCP/${productLabel} (matrix key: ${inf.matrixKey}, OCP=${ocpBuildChannel}, ${productLabel}=${odfBuildChannel})"
}

/** Resolve catalog pull spec. Catalog image param wins over channel-based resolve. */
def resolveCatalogImage(
    String product,
    String catalogImage,
    String buildChannel,
    String minor,
    String catalogLabel = 'ODF_CATALOG_IMAGE',
    String channelLabel = 'ODF_CHANNEL',
    String pullSecretFile = ''
) {
    if (catalogImage) {
        echo "${product.toUpperCase()}: using ${catalogLabel} (${channelLabel} ignored)"
        return [pullSpec: catalogImage, tag: parseCatalogTagFromPullSpec(catalogImage), error: '']
    }
    if (product == 'fdf') {
        def catalog = resolveFdfCatalog(odfMinor: minor, pullSecretFile: pullSecretFile)
        return [pullSpec: catalog.pullSpec, tag: catalog.tag ?: parseCatalogTagFromPullSpec(catalog.pullSpec), error: '']
    }
    if (buildChannel == 'nightly') {
        def catalog = resolveOdfDevCatalog(odfMinor: minor)
        return [pullSpec: catalog.pullSpec, tag: catalog.tag ?: parseCatalogTagFromPullSpec(catalog.pullSpec), error: '']
    }
    return [pullSpec: '', tag: '', error: '']
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

/** Map product-specific Jenkins params to internal service build fields. */
def resolveServiceParams(String product, Map params, Map defaults) {
    def isFdf = product == 'fdf'
    def channelLabel = isFdf ? 'FDF_CHANNEL' : 'ODF_CHANNEL'
    def catalogLabel = isFdf ? 'FDF_CATALOG_IMAGE' : 'ODF_CATALOG_IMAGE'
    def operatorLabel = isFdf ? 'FDF_OPERATOR_CHANNEL' : 'ODF_OPERATOR_CHANNEL'
    def defaultChannel = (
        (isFdf ? defaults.fdfChannel : defaults.odfChannel)
        ?: defaults.odfChannel
        ?: 'nightly'
    ).toString().trim()

    return [
        channelLabel   : channelLabel,
        catalogLabel   : catalogLabel,
        operatorLabel  : operatorLabel,
        buildChannel   : (params[channelLabel] ?: defaultChannel).toString().trim(),
        catalogImage   : (params[catalogLabel] ?: '').trim(),
        operatorChannel: (params[operatorLabel] ?: '').trim(),
    ]
}

def parseOdfMinor(String odfOperatorChannel) {
    def m = (odfOperatorChannel =~ /(\d+\.\d+)/)
    return m.find() ? m.group(1) : ''
}

def validateBuildChannel(String channel, String label) {
    def allowed = ['nightly', 'released']
    if (!allowed.contains(channel)) {
        throw new RuntimeException("${label} must be one of ${allowed}, got '${channel}'")
    }
}

def validateClusterParams(Map params, List errors, String product = 'odf') {
    def workerCount = (params.WORKER_COUNT?.trim() ?: '3') as Integer
    def productLabel = product == 'fdf' ? 'FDF' : 'ODF'
    if (workerCount < 3) {
        errors << "WORKER_COUNT must be >= 3 for ${productLabel} (got ${workerCount})"
    }
    def deviceCount = (params.DEVICE_COUNT?.trim() ?: '3') as Integer
    if (deviceCount < 1) {
        errors << "DEVICE_COUNT must be >= 1 (got ${deviceCount})"
    }
}

def validateCredentials(Map creds, List errors) {
    def missing = []
    if (!creds.ibmApiKey)       missing << 'IBM_CLOUD_API_KEY'
    if (!creds.ibmCosAccess)    missing << 'IBM_COS_HMAC_ACCESS_KEY'
    if (!creds.ibmCosSecret)    missing << 'IBM_COS_HMAC_SECRET_KEY'
    if (!creds.awsAccessKey)    missing << 'AWS_ACCESS_KEY_ID'
    if (!creds.awsSecretKey)    missing << 'AWS_SECRET_ACCESS_KEY'
    if (missing) {
        errors << "Missing credentials: ${missing.join(', ')}"
    }
}

def populateEnv(Map result) {
    if (result.ocpVersion) {
        env.RESOLVED_OCP_VERSION = result.ocpVersion
    }
    if (result.ocpChannel) {
        env.RESOLVED_OCP_CHANNEL = result.ocpChannel
    }
    env.RESOLVED_OCP_PULL_SPEC = result.ocpPullSpec ?: ''
    if (result.odfOperatorChannel) {
        env.RESOLVED_ODF_OPERATOR_CHANNEL = result.odfOperatorChannel
        env.RESOLVED_ODF_CHANNEL = result.odfOperatorChannel
    }
    if (result.ocsVersion) {
        env.RESOLVED_OCS_VERSION = result.ocsVersion
    }
    env.RESOLVED_ODF_CATALOG_IMAGE = result.odfCatalogImage ?: ''
    env.RESOLVED_ODF_CATALOG_TAG = result.odfCatalogTag ?: ''
    if (env.INTEROP_PRODUCT == 'fdf') {
        env.RESOLVED_FDF_CATALOG_IMAGE = result.odfCatalogImage ?: ''
        env.RESOLVED_FDF_CATALOG_TAG = result.odfCatalogTag ?: ''
    }
    // Backward compat for Update Config / email helpers
    env.RESOLVED_ODF_NIGHTLY_INDEX = result.odfCatalogImage ?: ''
    if (result.skip) {
        env.PIPELINE_SKIP = 'true'
    }
}
