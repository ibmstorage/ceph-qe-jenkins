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
    def pullSecretFile = parameters.pullSecretFile?.toString()?.trim() ?: ''
    def defaults = parameters.defaults ?: [:]

    def defaultOcpChannel = (defaults.ocpChannel ?: 'nightly').toString().trim()
    def defaultOdfChannel = (defaults.odfChannel ?: 'nightly').toString().trim()

    def errors = []
    def cephImg = params.CEPH_IMAGE?.trim()
    def ocpVer = params.OCP_VERSION?.trim()
    def ocpBuildChannel = (params.OCP_CHANNEL ?: defaultOcpChannel).toString().trim()
    def odfBuildChannel = (params.ODF_CHANNEL ?: defaultOdfChannel).toString().trim()
    def odfCatalogImage = params.ODF_CATALOG_IMAGE?.trim() ?: ''
    def odfOperatorChannel = params.ODF_OPERATOR_CHANNEL?.trim() ?: ''
    def rhcsBv = params.RHCS_BUILD_VERSION?.trim() ?: ''

    validateBuildChannel(ocpBuildChannel, 'OCP_CHANNEL')
    validateBuildChannel(odfBuildChannel, 'ODF_CHANNEL')

    def result = [
        cephImage          : cephImg,
        ocpVersion         : '',
        ocpChannel         : ocpBuildChannel,
        ocpPullSpec        : '',
        odfOperatorChannel : '',
        odfBuildChannel    : odfBuildChannel,
        odfCatalogImage    : '',
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
        errors << 'Set ODF_OPERATOR_CHANNEL with OCP_VERSION, or leave both empty for auto-resolve'
    } else if (!ocpVer && odfOperatorChannel) {
        errors << 'Set OCP_VERSION with ODF_OPERATOR_CHANNEL, or leave both empty for auto-resolve'
    }

    if (errors) {
        populateEnv(result)
        return result
    }

    try {
        if (explicitMode) {
            applyExplicitResolution(result, ocpVer, odfOperatorChannel, ocpBuildChannel, odfBuildChannel, odfCatalogImage, errors)
        } else {
            applyAutoResolution(result, cephImg, rhcsBv, ocpBuildChannel, odfBuildChannel, odfCatalogImage, pullSecretFile, errors)
        }
    } catch (Exception e) {
        if (e.message?.startsWith('ODF catalog image not found')) {
            errors << e.message
        } else {
            errors << "Could not auto-resolve OCP/ODF: ${e.message}. " +
                'Set RHCS_BUILD_VERSION (e.g. 9.1) for digest-only images, or set OCP_VERSION + ODF_OPERATOR_CHANNEL.'
        }
    }

    validateClusterParams(params, errors)
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
    List errors
) {
    result.ocpVersion = ocpVer
    result.ocpChannel = ocpBuildChannel
    result.odfOperatorChannel = odfOperatorChannel
    result.ocsVersion = parseOdfMinor(odfOperatorChannel)
    if (!result.ocsVersion) {
        errors << "Could not parse ODF minor from ODF_OPERATOR_CHANNEL '${odfOperatorChannel}'"
        return
    }

    def catalog = resolveOdfCatalogImage(odfCatalogImage, odfBuildChannel, result.ocsVersion)
    result.odfCatalogImage = catalog.pullSpec
    echo "Using explicit OCP_VERSION / ODF_OPERATOR_CHANNEL (OCP_CHANNEL=${ocpBuildChannel}, ODF_CHANNEL=${odfBuildChannel})"
}

def applyAutoResolution(
    Map result,
    String cephImg,
    String rhcsBv,
    String ocpBuildChannel,
    String odfBuildChannel,
    String odfCatalogImage,
    String pullSecretFile,
    List errors
) {
    def inf = null
    if (pullSecretFile) {
        inf = resolveOdfInteropParams(
            inferAuto: true,
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

    echo "Auto-resolved OCP/ODF (matrix key: ${inf.matrixKey}, OCP=${ocpBuildChannel}, ODF=${odfBuildChannel})"
}

/** Resolve ODF catalog pull spec. ODF_CATALOG_IMAGE wins over ODF_CHANNEL. */
def resolveOdfCatalogImage(String odfCatalogImage, String odfBuildChannel, String odfMinor) {
    if (odfCatalogImage) {
        echo "ODF: using ODF_CATALOG_IMAGE (ODF_CHANNEL ignored)"
        return [pullSpec: odfCatalogImage, error: '']
    }
    if (odfBuildChannel == 'nightly') {
        def catalog = resolveOdfDevCatalog(odfMinor: odfMinor)
        return [pullSpec: catalog.pullSpec, error: '']
    }
    return [pullSpec: '', error: '']
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

def validateClusterParams(Map params, List errors) {
    def workerCount = (params.WORKER_COUNT?.trim() ?: '3') as Integer
    if (workerCount < 3) {
        errors << "WORKER_COUNT must be >= 3 for ODF (got ${workerCount})"
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
    // Backward compat for Update Config / email helpers
    env.RESOLVED_ODF_NIGHTLY_INDEX = result.odfCatalogImage ?: ''
    if (result.skip) {
        env.PIPELINE_SKIP = 'true'
    }
}
