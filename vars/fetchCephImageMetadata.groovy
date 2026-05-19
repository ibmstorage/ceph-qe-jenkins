/**
 * Extract Ceph version metadata from a container image.
 *
 * Reuses the JSON at .cephadm-resolve-<BUILD>.json if it already exists.
 * Otherwise runs podman with "cephadm version --verbose" (JSON on squid/tentacle).
 * If --verbose does not produce valid JSON, falls back to plain "cephadm version"
 * and parses the version string (works on all versions).
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
        echo "fetchCephImageMetadata: reusing ${jsonPath}"
    } else {
        echo "fetchCephImageMetadata: running podman cephadm version --verbose..."
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

                if [ -s "$CEPHADM_OUT" ] && jq empty "$CEPHADM_OUT" 2>/dev/null; then
                    echo "fetchCephImageMetadata: wrote $CEPHADM_OUT (--verbose JSON)"
                    exit 0
                fi

                echo "fetchCephImageMetadata: --verbose did not produce valid JSON, trying plain cephadm version"
                rm -f "$CEPHADM_OUT"
                PLAIN=$(podman run --rm --authfile "$AUTH_FILE" "$CEPH_IMAGE" \
                    bash -c "cephadm version" 2>/dev/null)
                VER=$(echo "$PLAIN"    | grep -oE '[0-9]+[.][0-9]+[.][0-9]+[^ )*]*' | head -1)
                REL=$(echo "$PLAIN"    | grep -oE 'pacific|quincy|reef|squid|tentacle' | head -1)
                COMMIT=$(echo "$PLAIN" | grep -oE '[0-9a-f]{10,45}' | head -1)
                if [ -n "$VER" ]; then
                    jq -n --arg v "$VER" --arg r "$REL" --arg c "$COMMIT" \
                        '{ceph_git_nice_ver: $v, ceph_release_name: $r, ceph_git_ver: $c}' > "$CEPHADM_OUT"
                    echo "fetchCephImageMetadata: wrote $CEPHADM_OUT (parsed plain cephadm version)"
                else
                    echo "fetchCephImageMetadata: could not extract version from image"
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
