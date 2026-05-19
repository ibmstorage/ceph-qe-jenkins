/**
 * Resolve OCP + ODF from RHCS line or Ceph image (podman).
 * Uses RuntimeException (not IllegalArgumentException) so load()-ed scripts pass typical Jenkins sandbox rules.
 *
 * Only squid and tentacle are supported.  Returns null when the resolved stream
 * is not in the matrix (e.g. reef / RHCS 7) so the caller can exit gracefully.
 *
 * inferAuto:
 *   - RHCS_BUILD_VERSION: leading major 8/9 → squid/tentacle → matrix
 *   - else: podman + cephadm version --verbose JSON → ceph_git_nice_ver (e.g. 20.2.1) → upstream major → matrix
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
    def odfChOverride = parameters.odfChannelOverride?.toString()?.trim()

    def stream = extractCephStream(jobName)
    if (!buildVersion) {
        throw new RuntimeException(
            "resolveOdfInteropParams: buildVersion (BUILD_VERSION) is empty"
        )
    }

    def majM = (buildVersion =~ /^(\d+)/)
    if (majM.find()) {
        def maj = (majM.group(1) as int)
        def streamFromBv = rhcsMajorToStream(maj)
        if (streamFromBv && streamFromBv != stream) {
            throw new RuntimeException(
                "resolveOdfInteropParams: BUILD_VERSION '${buildVersion}' implies '${streamFromBv}' but JOB_NAME implies '${stream}'"
            )
        }
    }

    def row = odfInteropMatrix()[stream]
    if (!row) {
        echo "resolveOdfInteropParams: stream '${stream}' is not in the interop matrix (supported: squid, tentacle)"
        return null
    }

    def ocpVersion = ocpVerOverride ?: row.ocpVersion
    def ocpChannel = ocpChOverride ?: row.ocpChannel
    def odfChannel = odfChOverride ?: row.odfChannel

    def allowedOcpChannels = ['released', 'nightly']
    if (!allowedOcpChannels.contains(ocpChannel)) {
        throw new RuntimeException(
            "resolveOdfInteropParams: OCP_CHANNEL must be one of ${allowedOcpChannels}, got '${ocpChannel}'"
        )
    }

    def bvtMsg = "BVT-style: stream '${stream}' from JOB_NAME, BUILD_VERSION '${buildVersion}' → interop matrix"
    if (ocpVerOverride || ocpChOverride || odfChOverride) {
        bvtMsg += ' (with parameter overrides)'
    }
    echo "resolveOdfInteropParams: ${bvtMsg} → OCP ${ocpVersion} (${ocpChannel}), ODF ${odfChannel}"

    return [
        cephStream    : stream,
        matrixKey     : stream,
        ocpVersion    : ocpVersion,
        ocpChannel    : ocpChannel,
        odfChannel    : odfChannel,
        ocsVersion    : row.ocsVersion,
    ]
}

def inferAuto(Map p) {
    def img = p.cephImage?.toString()?.trim()
    if (!img) {
        throw new RuntimeException('inferAuto: cephImage (CEPH_IMAGE) is required')
    }

    def bv = p.rhcsBuildVersion?.toString()?.trim()
    if (bv) {
        def stream = rhcsMajorToStream(parseLeadingMajor(bv, 'RHCS_BUILD_VERSION'))
        if (!stream) {
            echo "resolveOdfInteropParams: RHCS_BUILD_VERSION '${bv}' does not map to a supported stream (only squid / tentacle)"
            return null
        }
        return resolveByStream(
            stream,
            "RHCS_BUILD_VERSION '${bv}' → Ceph stream '${stream}' → interop matrix → OCP/ODF GA row"
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

    def cephMaj = parseLeadingMajor(nice, 'ceph_git_nice_ver')
    def stream = upstreamCephMajorToStream(cephMaj)
    if (!stream) {
        echo "resolveOdfInteropParams: Ceph major ${cephMaj} (from '${nice}') does not map to a supported stream (only squid / tentacle)"
        return null
    }
    return resolveByStream(
        stream,
        "podman + cephadm: ceph_git_nice_ver '${nice}' → Ceph major ${cephMaj} → stream '${stream}' → interop matrix → OCP/ODF GA row"
    )
}

/** First major from RHCS or Ceph version strings: 9.1, v20.2.1, 20.2.1-188.el9cp → 9 / 20 / 20 */
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

/** RHCS product line: 8 → squid, 9 → tentacle.  Returns null for unsupported majors. */
def rhcsMajorToStream(int maj) {
    return [8: 'squid', 9: 'tentacle'][maj]
}

/** Upstream Ceph: 19 → squid, 20+ → tentacle.  Returns null for unsupported majors. */
def upstreamCephMajorToStream(int cephMajor) {
    if (cephMajor >= 20) { return 'tentacle' }
    if (cephMajor == 19) { return 'squid' }
    return null
}

def resolveByStream(String stream, String howMsg = null) {
    def row = odfInteropMatrix()[stream]
    if (!row) {
        echo "resolveOdfInteropParams: stream '${stream}' is not in the interop matrix (supported: squid, tentacle)"
        return null
    }
    def msg = howMsg ?: "stream '${stream}' → interop matrix → OCP ${row.ocpVersion} (${row.ocpChannel}), ODF ${row.odfChannel}"
    echo "resolveOdfInteropParams: ${msg}"
    return [
        cephStream    : stream,
        matrixKey     : stream,
        ocpVersion    : row.ocpVersion,
        ocpChannel    : row.ocpChannel,
        odfChannel    : row.odfChannel,
        ocsVersion    : row.ocsVersion,
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

def odfInteropMatrix() {
    return [
        squid    : [ocpVersion: '4.20.19', ocpChannel: 'released', odfChannel: 'stable-4.20', ocsVersion: '4.20'],
        tentacle : [ocpVersion: '4.21.11', ocpChannel: 'released', odfChannel: 'stable-4.21', ocsVersion: '4.21'],
    ]
}
