/**
 * Extract Ceph version metadata from a container image.
 *
 * Reuses the JSON written by resolveOdfInteropParams (Validate Parameters stage)
 * at .cephadm-resolve-<BUILD>.json. If that file doesn't exist (e.g. OCP/ODF
 * were set explicitly), runs "cephadm version --verbose" via podman.
 *
 * Required parameters:
 *   image            - full image reference (tag or digest)
 *   pullSecretFile   - path to the registry auth file
 *
 * Returns a Map with keys: ceph_git_nice_ver, ceph_git_ver, ceph_release_name (or empty map on failure).
 */
def call(Map parameters) {
    def image    = parameters.image?.trim()
    def authFile = parameters.pullSecretFile?.trim()

    if (!image) {
        echo "fetchCephImageMetadata: no image provided -- skipping"
        return [:]
    }
    if (!authFile) {
        echo "fetchCephImageMetadata: no pullSecretFile provided -- skipping"
        return [:]
    }

    def jsonPath = "${env.WORKSPACE}/.cephadm-resolve-${env.BUILD_NUMBER ?: '0'}.json"

    if (fileExists(jsonPath)) {
        echo "fetchCephImageMetadata: reusing ${jsonPath} from resolve step"
    } else {
        echo "fetchCephImageMetadata: resolve JSON not found, running podman..."
        withEnv([
            "CEPH_IMAGE=${image}",
            "AUTH_FILE=${authFile}",
            "CEPHADM_OUT=${jsonPath}",
        ]) {
            sh '''#!/bin/bash
                set +e
                if ! command -v podman >/dev/null 2>&1; then
                    echo "fetchCephImageMetadata: podman not found -- skipping"
                    exit 0
                fi
                podman run --rm --authfile "$AUTH_FILE" "$CEPH_IMAGE" \
                    bash -c "cephadm version --verbose" > "$CEPHADM_OUT" 2>/dev/null

                if [ -s "$CEPHADM_OUT" ] && \
                   python3 -c "import json; json.load(open('$CEPHADM_OUT'))" 2>/dev/null; then
                    echo "fetchCephImageMetadata: wrote $CEPHADM_OUT"
                else
                    echo "fetchCephImageMetadata: cephadm version --verbose did not produce valid JSON"
                    rm -f "$CEPHADM_OUT"
                fi
                exit 0
            '''
        }
    }

    def result = [:]
    if (fileExists(jsonPath)) {
        try {
            def meta = readJSON file: jsonPath
            if (meta?.ceph_git_nice_ver) { result.ceph_git_nice_ver = meta.ceph_git_nice_ver.toString() }
            if (meta?.ceph_git_ver)      { result.ceph_git_ver = meta.ceph_git_ver.toString() }
            if (meta?.ceph_release_name) { result.ceph_release_name = meta.ceph_release_name.toString() }
            echo "fetchCephImageMetadata: version=${result.ceph_git_nice_ver}, release=${result.ceph_release_name}"
        } catch (Exception e) {
            echo "fetchCephImageMetadata: could not parse ${jsonPath} -- ${e.message}"
        }
    }
    return result
}
