/**
 * Resolve OCP + ODF versions from RHCS build version or Ceph container image.
 *
 * Maintains a slim RHCS-to-ODF-minor mapping and dynamically resolves the
 * latest OCP patch version via the OpenShift Release API at runtime.
 *
 * Supported RHCS versions: 8.0 (squid), 8.1 (squid), 9.0 (tentacle), 9.1 (tentacle), 9.2 (tentacle).
 * Returns null for unsupported versions so the caller can exit gracefully.
 *
 * inferAuto:
 *   - RHCS_BUILD_VERSION (e.g. 9.1) → rhcsToOcpMinor → API → OCP/ODF
 *   - else: podman + cephadm version --verbose → ceph_git_nice_ver (e.g. 20.2.1) → RHCS → API → OCP/ODF
 *   - ocpChannel / ocpChannelOverride: "released" (default) or "nightly"
 *   - odfBuildChannel / odfChannel: "released" (default) or "nightly" — catalog source when no image override
 *   - odfCatalogImage: when set, takes precedence over odfBuildChannel
 *
 * Also supports BVT-style: JOB_NAME <stream>-bvt-ci + BUILD_VERSION + overrides (no inferAuto).
 */

def call(Map parameters) {
    if (parameters.inferAuto == true) {
        return inferAuto(parameters)
    }

    def jobName = (parameters.jobName ?: env.JOB_NAME ?: '').toString()
    def buildVersion = (parameters.buildVersion ?: '').toString().trim()
    def ocpVerOverride = parameters.ocpVersionOverride?.toString()?.trim()
    def ocpChOverride = parameters.ocpChannelOverride?.toString()?.trim()
    def odfOpChOverride = parameters.odfOperatorChannelOverride?.toString()?.trim()
        ?: parameters.odfChannelOverride?.toString()?.trim()

    def stream = extractCephStream(jobName)
    if (!buildVersion) {
        throw new RuntimeException(
            "resolveOdfInteropParams: buildVersion (BUILD_VERSION) is empty"
        )
    }

    def rhcsVer = parseRhcsVersion(buildVersion, 'BUILD_VERSION')
    def streamFromBv = rhcsMajorToStream(parseLeadingMajor(buildVersion, 'BUILD_VERSION'))
    if (streamFromBv && streamFromBv != stream) {
        throw new RuntimeException(
            "resolveOdfInteropParams: BUILD_VERSION '${buildVersion}' implies '${streamFromBv}' but JOB_NAME implies '${stream}'"
        )
    }

    def ocpMinor = rhcsToOcpMinor()[rhcsVer]
    if (!ocpMinor) {
        echo "resolveOdfInteropParams: RHCS '${rhcsVer}' is not in the interop mapping (supported: ${rhcsToOcpMinor().keySet()})"
        return null
    }

    def ocpChannel = normalizeOcpChannel(ocpChOverride ?: 'released')
    def resolved = resolveOcpFromOdfMinor(ocpMinor, ocpChannel)
    if (!resolved) {
        echo "resolveOdfInteropParams: could not resolve OCP version for OCP ${ocpMinor}"
        return null
    }

    def ocpVersion = ocpVerOverride ?: resolved.ocpVersion
    def odfOperatorChannel = odfOpChOverride ?: "stable-${ocpMinor}"

    def bvtMsg = "BVT-style: stream '${stream}', RHCS '${rhcsVer}' → OCP/ODF ${ocpMinor} → API"
    if (ocpVerOverride || ocpChOverride || odfOpChOverride) {
        bvtMsg += ' (with parameter overrides)'
    }
    echo "resolveOdfInteropParams: ${bvtMsg} → OCP ${ocpVersion} (${resolved.ocpChannel}), ODF ${odfOperatorChannel}"

    return [
        cephStream         : stream,
        matrixKey          : rhcsVer,
        ocpVersion         : ocpVersion,
        ocpChannel         : resolved.ocpChannel,
        ocpPullSpec        : resolved.ocpPullSpec,
        odfOperatorChannel : odfOperatorChannel,
        odfChannel         : odfOperatorChannel,
        ocsVersion         : ocpMinor,
    ]
}

def inferAuto(Map p) {
    def img = p.cephImage?.toString()?.trim()
    if (!img) {
        throw new RuntimeException('inferAuto: cephImage (CEPH_IMAGE) is required')
    }

    def defaults = p.defaults ?: [:]
    def ocpChannel = normalizeOcpChannel(
        (p.ocpChannelOverride ?: p.ocpChannel ?: defaults.ocpChannel ?: 'released').toString().trim()
    )
    def odfBuildChannel = normalizeOdfBuildChannel(
        (p.odfBuildChannel ?: p.odfChannel ?: defaults.odfChannel ?: 'released').toString().trim()
    )
    def odfCatalogImage = p.odfCatalogImage?.toString()?.trim() ?: ''

    def bv = p.rhcsBuildVersion?.toString()?.trim()
    if (bv) {
        def rhcsVer = parseRhcsVersion(bv, 'RHCS_BUILD_VERSION')
        def ocpMinor = rhcsToOcpMinor()[rhcsVer]
        if (!ocpMinor) {
            echo "resolveOdfInteropParams: RHCS_BUILD_VERSION '${bv}' (parsed as '${rhcsVer}') is not in the interop mapping (supported: ${rhcsToOcpMinor().keySet()})"
            return null
        }
        return resolveByRhcsVersion(
            rhcsVer, ocpMinor,
            "RHCS_BUILD_VERSION '${bv}' → RHCS '${rhcsVer}' → OCP/ODF ${ocpMinor} → API (OCP=${ocpChannel}, ODF=${odfBuildChannel})",
            ocpChannel,
            odfBuildChannel,
            odfCatalogImage
        )
    }

    def psf = p.pullSecretFile?.toString()?.trim()
    if (!psf) {
        throw new RuntimeException(
            'inferAuto: set RHCS_BUILD_VERSION (e.g. 9.1) or pass pullSecretFile so podman can read the image'
        )
    }

    def meta = fetchCephImageMetadata(image: img, pullSecretFile: psf)
    def nice = meta?.ceph_git_nice_ver?.toString()?.trim()
    if (!nice) {
        echo "resolveOdfInteropParams: could not extract version from image (check podman, pull secret, image)"
        return null
    }

    def niceBase = nice.replaceAll(/-.*$/, '')
    def rhcsVer = upstreamCephToRhcsVersion(niceBase)
    if (!rhcsVer) {
        echo "resolveOdfInteropParams: upstream Ceph '${nice}' (base '${niceBase}') does not map to a known RHCS version (known: ${upstreamCephVersionMap().keySet()})"
        return null
    }

    def ocpMinor = rhcsToOcpMinor()[rhcsVer]
    if (!ocpMinor) {
        echo "resolveOdfInteropParams: RHCS '${rhcsVer}' (from upstream '${nice}') is not in the interop mapping"
        return null
    }

    return resolveByRhcsVersion(
        rhcsVer, ocpMinor,
        "podman + cephadm: ceph_git_nice_ver '${nice}' → RHCS '${rhcsVer}' → OCP/ODF ${ocpMinor} → API (OCP=${ocpChannel}, ODF=${odfBuildChannel})",
        ocpChannel,
        odfBuildChannel,
        odfCatalogImage
    )
}

def normalizeOcpChannel(String channel) {
    validateBuildChannel(channel, 'OCP_CHANNEL')
    return channel
}

def normalizeOdfBuildChannel(String channel) {
    validateBuildChannel(channel, 'ODF build channel')
    return channel
}

def validateBuildChannel(String channel, String label) {
    def allowed = ['released', 'nightly']
    if (!allowed.contains(channel)) {
        throw new RuntimeException(
            "resolveOdfInteropParams: ${label} must be one of ${allowed}, got '${channel}'"
        )
    }
}

def resolveOdfCatalogPullSpec(String odfCatalogImage, String odfBuildChannel, String ocpMinor) {
    if (odfCatalogImage) {
        echo "resolveOdfInteropParams: using ODF_CATALOG_IMAGE (ODF build channel ignored)"
        return odfCatalogImage
    }
    if (odfBuildChannel == 'nightly') {
        return resolveOdfDevCatalog(odfMinor: ocpMinor).pullSpec
    }
    return ''
}

// ──────────────────────────────────────────────────────────────────────
// Parsers
// ──────────────────────────────────────────────────────────────────────

def parseLeadingMajor(String s, String label) {
    def t = s?.trim()
    if (!t) {
        throw new RuntimeException("resolveOdfInteropParams: empty ${label}")
    }
    def m = (t =~ /(?i)^v?(\d+)/)
    if (!m.find()) {
        throw new RuntimeException("resolveOdfInteropParams: cannot parse major from ${label}: '${s}'")
    }
    return (m.group(1) as int)
}

def parseRhcsVersion(String s, String label) {
    def t = s?.trim()
    if (!t) {
        throw new RuntimeException("resolveOdfInteropParams: empty ${label}")
    }
    def m = (t =~ /^(\d+\.\d+)/)
    if (!m.find()) {
        throw new RuntimeException("resolveOdfInteropParams: cannot parse RHCS version (major.minor) from ${label}: '${s}'")
    }
    return m.group(1)
}

// ──────────────────────────────────────────────────────────────────────
// Mappings
// ──────────────────────────────────────────────────────────────────────

def rhcsMajorToStream(int maj) {
    return [8: 'squid', 9: 'tentacle'][maj]
}

def rhcsToOcpMinor() {
    return [
        '8.0': '4.18',
        '8.1': '4.20',
        '9.0': '4.21',
        '9.1': '4.22',
        '9.2': '4.23',
    ]
}

/** @deprecated Use rhcsToOcpMinor */
def rhcsToOdfMinor() {
    return rhcsToOcpMinor()
}

def upstreamCephVersionMap() {
    return [
        '19.2.0': '8.0',
        '19.2.1': '8.1',
        '20.1.0': '9.0',
        '20.2.1': '9.1',
        '20.2.2': '9.2',
    ]
}

def upstreamCephToRhcsVersion(String niceVer) {
    return upstreamCephVersionMap()[niceVer]
}

// ──────────────────────────────────────────────────────────────────────
// OCP version resolution via OpenShift Release API
// ──────────────────────────────────────────────────────────────────────

def resolveOcpFromOdfMinor(String odfMinor, String preferredChannel = 'released') {
    if (preferredChannel == 'nightly') {
        return queryNightlyOcp(odfMinor)
    }

    def released = queryReleasedOcp(odfMinor)
    if (released) {
        return released
    }

    echo "resolveOdfInteropParams: no GA release found for OCP ${odfMinor}, trying nightly"
    return queryNightlyOcp(odfMinor)
}

def queryReleasedOcp(String odfMinor) {
    def ocpMajor = odfMinor.split('\\.')[0]
    def url = "${ocpReleaseApiBase()}/${ocpMajor}-stable/latest?prefix=${odfMinor}"
    return queryOcpReleaseApi(url, 'released')
}

def queryNightlyOcp(String odfMinor) {
    def url = "${ocpReleaseApiBase()}/${odfMinor}.0-0.nightly/latest"
    return queryOcpReleaseApi(url, 'nightly')
}

def ocpReleaseApiBase() {
    return 'https://openshift-release.apps.ci.l2s4.p1.openshiftapps.com/api/v1/releasestream'
}

def queryOcpReleaseApi(String url, String channel) {
    try {
        def raw = sh(
            script: "curl -sf --connect-timeout 15 --max-time 30 '${url}' 2>/dev/null || true",
            returnStdout: true
        ).trim()
        if (!raw) {
            return null
        }
        def json = readJSON(text: raw)
        def name = json?.name?.toString()?.trim()
        if (!name) {
            return null
        }
        def pullSpec = json?.pullSpec?.toString()?.trim() ?: ''
        return [ocpVersion: name, ocpChannel: channel, ocpPullSpec: pullSpec]
    } catch (Exception e) {
        echo "resolveOdfInteropParams: OpenShift Release API query failed (${channel}): ${e.message}"
        return null
    }
}

// ──────────────────────────────────────────────────────────────────────
// Resolution helpers
// ──────────────────────────────────────────────────────────────────────

def resolveByRhcsVersion(
    String rhcsVer,
    String ocpMinor,
    String howMsg = null,
    String ocpChannel = 'released',
    String odfBuildChannel = 'released',
    String odfCatalogImage = ''
) {
    def resolved = resolveOcpFromOdfMinor(ocpMinor, ocpChannel)
    if (!resolved) {
        echo "resolveOdfInteropParams: could not resolve OCP version for OCP/ODF ${ocpMinor} (RHCS ${rhcsVer}) from OpenShift Release API"
        return null
    }

    def stream = rhcsMajorToStream(parseLeadingMajor(rhcsVer, 'rhcsVer'))
    def odfOperatorChannel = "stable-${ocpMinor}"
    def catalogPullSpec = resolveOdfCatalogPullSpec(odfCatalogImage, odfBuildChannel, ocpMinor)

    def msg = howMsg ?: "RHCS '${rhcsVer}' → OCP/ODF ${ocpMinor} → OCP ${resolved.ocpVersion} (${resolved.ocpChannel}), ODF ${odfOperatorChannel}"
    if (catalogPullSpec) {
        msg += " + catalog ${catalogPullSpec.split('/')[-1].take(48)}"
    } else if (odfBuildChannel == 'released') {
        msg += ' (redhat-operators)'
    }
    echo "resolveOdfInteropParams: ${msg}"

    return [
        cephStream         : stream,
        matrixKey          : rhcsVer,
        ocpVersion         : resolved.ocpVersion,
        ocpChannel         : resolved.ocpChannel,
        ocpPullSpec        : resolved.ocpPullSpec,
        odfOperatorChannel : odfOperatorChannel,
        odfChannel         : odfOperatorChannel,
        ocsVersion         : ocpMinor,
        odfBuildChannel    : odfBuildChannel,
        odfCatalogImage    : catalogPullSpec,
    ]
}

def extractCephStream(String jobName) {
    def m = (jobName =~ /^([a-z]+)-bvt-ci$/)
    if (m.find()) {
        return m.group(1)
    }
    throw new RuntimeException(
        "resolveOdfInteropParams: JOB_NAME '${jobName}' does not match '<stream>-bvt-ci'"
    )
}
