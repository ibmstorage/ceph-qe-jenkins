/**
 * Resolve OCP + ODF from RHCS line or Ceph image (podman).
 * Uses RuntimeException (not IllegalArgumentException) so load()-ed scripts pass typical Jenkins sandbox rules.
 *
 * inferAuto:
 *   - RHCS_BUILD_VERSION: leading major 7/8/9 → reef/squid/tentacle → matrix
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

    def matrix = odfInteropMatrix()
    def row = matrix[stream]
    if (!row) {
        throw new RuntimeException(
            "resolveOdfInteropParams: no matrix for stream '${stream}'. Known: ${matrix.keySet().sort()}"
        )
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

    def nice = cephGitNiceVerFromPodman(img, psf)
    if (!nice) {
        throw new RuntimeException(
            'inferAuto: podman did not return ceph_git_nice_ver from cephadm version --verbose (check podman, pull secret, image)'
        )
    }

    def cephMaj = parseLeadingMajor(nice, 'ceph_git_nice_ver')
    def stream = upstreamCephMajorToStream(cephMaj)
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

/** RHCS product line: 7 → reef, 8 → squid, 9 → tentacle */
def rhcsMajorToStream(int maj) {
    def s = [7: 'reef', 8: 'squid', 9: 'tentacle'][maj]
    if (!s) {
        throw new RuntimeException(
            "resolveOdfInteropParams: RHCS major ${maj} not supported (use 7, 8, or 9)"
        )
    }
    return s
}

/** Upstream Ceph (e.g. 18.2 / 19.2 / 20.2): 18→reef, 19→squid, 20+→tentacle */
def upstreamCephMajorToStream(int cephMajor) {
    if (cephMajor >= 20) {
        return 'tentacle'
    }
    if (cephMajor == 19) {
        return 'squid'
    }
    if (cephMajor == 18) {
        return 'reef'
    }
    throw new RuntimeException(
        "resolveOdfInteropParams: Ceph major ${cephMajor} not mapped (expected 18–20+ for reef/squid/tentacle)"
    )
}

def resolveByStream(String stream, String howMsg = null) {
    def row = odfInteropMatrix()[stream]
    if (!row) {
        throw new RuntimeException("resolveOdfInteropParams: unknown stream '${stream}'")
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

/** podman run … cephadm version --verbose → JSON → ceph_git_nice_ver (e.g. v20.2.1) */
def cephGitNiceVerFromPodman(String image, String pullSecretFile) {
    def outFile = "${env.WORKSPACE}/.cephadm-resolve-${env.BUILD_NUMBER ?: '0'}.json"
    try {
        withEnv([
            "CEPH_IMAGE_PODMAN=${image}",
            "PULL_SECRET_FILE=${pullSecretFile}",
            "CEPHADM_JSON_OUT=${outFile}",
        ]) {
            sh(
                script: '''
                    set +e
                    rm -f "${CEPHADM_JSON_OUT}"
                    if ! command -v podman >/dev/null 2>&1; then
                        exit 1
                    fi
                    podman run --rm --authfile "${PULL_SECRET_FILE}" "${CEPH_IMAGE_PODMAN}" \
                        bash -c "cephadm version --verbose" > "${CEPHADM_JSON_OUT}" 2>/dev/null
                ''',
                returnStatus: true
            )
        }
        if (!fileExists(outFile)) {
            return null
        }
        def raw = readFile(outFile).trim()
        if (!raw) {
            return null
        }
        def meta = new groovy.json.JsonSlurper().parseText(raw)
        return meta?.ceph_git_nice_ver?.toString()?.trim()
    } catch (Exception e) {
        echo "cephGitNiceVerFromPodman: ${e.message}"
        return null
    }
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
        reef     : [ocpVersion: '4.19.28', ocpChannel: 'released', odfChannel: 'stable-4.19', ocsVersion: '4.19'],
        squid    : [ocpVersion: '4.20.19', ocpChannel: 'released', odfChannel: 'stable-4.20', ocsVersion: '4.20'],
        tentacle : [ocpVersion: '4.21.11', ocpChannel: 'released', odfChannel: 'stable-4.21', ocsVersion: '4.21'],
    ]
}
