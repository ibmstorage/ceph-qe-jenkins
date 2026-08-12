#!/usr/bin/env bash
# Resolve the stable Konflux ODF catalog index for an OCS/ODF minor version.
#
# Resolution order:
#   1. Find the highest latest-stable-{minor}.X rolling tag on Quay and read its
#      manifest digest.
#   2. Find the *.konflux tag for that minor that shares the same digest → exact
#      match between the stable pointer and the Konflux build.
#   3. If no digest match is found, fall back to the highest *.konflux tag for
#      that minor by version sort and emit a WARNING to stderr.
#   4. If no *.konflux tag exists at all for the minor, exit non-zero.
#
# All diagnostic output (DEBUG/WARNING) goes to stderr only; stdout is clean.
#
# Usage:
#   ./ocs-build.sh <version>              # quay.io/rhceph-dev/ocs-registry:4.22.0-105.konflux
#   ./ocs-build.sh --tag-only <version>   # tag name only (e.g. 4.22.0-105.konflux)
#   ./ocs-build.sh --json <version>       # {"tag":"...","digest":"...","pullSpec":"...","stableTag":"..."}
#
# Example:
#   ./ocs-build.sh 4.22

set -euo pipefail

REPO="rhceph-dev/ocs-registry"
REGISTRY="quay.io"
API_BASE="https://quay.io/api/v1/repository/${REPO}/tag/"
OUTPUT="pullspec"
VERSION=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag-only) OUTPUT="tag" ; shift ;;
        --json)     OUTPUT="json" ; shift ;;
        -h|--help)
            sed -n '2,12p' "$0"
            exit 0
            ;;
        -*)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
        *)
            if [[ -z "$VERSION" ]]; then
                VERSION="$1"
            else
                echo "Unexpected argument: $1" >&2
                exit 1
            fi
            shift
            ;;
    esac
done

if [[ -z "$VERSION" ]]; then
    echo "Usage: $0 [--tag-only|--json] <version>" >&2
    echo "Example: $0 4.22" >&2
    exit 1
fi

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "Error: version must be major.minor (got '${VERSION}')" >&2
    exit 1
fi

API_URL="${API_BASE}?onlyActiveTags=true&limit=200&filter_tag_name=like:${VERSION}"
echo "DEBUG: fetching tags from ${API_URL}" >&2

TAGS_JSON="$(curl -ks --connect-timeout 15 --max-time 30 "${API_URL}")"

TAG_COUNT="$(jq '.tags | length' <<< "$TAGS_JSON" 2>/dev/null || echo 0)"
echo "DEBUG: fetched ${TAG_COUNT} tags (limit=200; if near the limit, some tags may be missing)" >&2

STABLE_TAG="$(jq -r --arg ver "$VERSION" \
    '.tags[] | select(.name | test("^latest-stable-" + $ver + "\\.[0-9]+$")) | .name' \
    <<< "$TAGS_JSON" | sort -V | tail -1)"

if [[ -z "$STABLE_TAG" || "$STABLE_TAG" == "null" ]]; then
    echo "Error: no latest-stable-${VERSION}.X tag found on ${REGISTRY}/${REPO}" >&2
    echo "DEBUG: available tags matching '${VERSION}':" >&2
    jq -r '.tags[].name' <<< "$TAGS_JSON" | sort >&2
    exit 1
fi

echo "DEBUG: resolved stable tag -> ${STABLE_TAG}" >&2

STABLE_SHA="$(jq -r --arg tag "$STABLE_TAG" \
    '.tags[] | select(.name == $tag) | .manifest_digest' <<< "$TAGS_JSON")"

if [[ -z "$STABLE_SHA" || "$STABLE_SHA" == "null" ]]; then
    echo "Error: no manifest digest for tag ${STABLE_TAG}" >&2
    exit 1
fi

echo "DEBUG: digest for ${STABLE_TAG} -> ${STABLE_SHA}" >&2

KONFLUX_TAG="$(jq -r --arg sha "$STABLE_SHA" --arg ver "$VERSION" \
    '.tags[] | select(.manifest_digest == $sha and (.name | test("^" + $ver + "\\.[0-9]+-[0-9]+\\.konflux$"))) | .name' \
    <<< "$TAGS_JSON" | sort -V | tail -1)"

if [[ -z "$KONFLUX_TAG" || "$KONFLUX_TAG" == "null" ]]; then
    echo "WARNING: no konflux tag matching digest of ${STABLE_TAG} (${STABLE_SHA}) for ODF ${VERSION}" >&2
    echo "DEBUG: tags in this response that look like konflux candidates for ${VERSION}:" >&2
    jq -r --arg ver "$VERSION" \
        '.tags[] | select(.name | test("^" + $ver + "\\.[0-9]+-[0-9]+\\.konflux$")) | "\(.name)  digest=\(.manifest_digest)"' \
        <<< "$TAGS_JSON" | sort >&2 || echo "  (none)" >&2
    echo "DEBUG: all tags sharing digest ${STABLE_SHA}:" >&2
    jq -r --arg sha "$STABLE_SHA" \
        '.tags[] | select(.manifest_digest == $sha) | .name' \
        <<< "$TAGS_JSON" | sort >&2 || echo "  (none)" >&2

    # Fall back to the latest .konflux tag for this version by version sort
    KONFLUX_TAG="$(jq -r --arg ver "$VERSION" \
        '.tags[] | select(.name | test("^" + $ver + "\\.[0-9]+-[0-9]+\\.konflux$")) | .name' \
        <<< "$TAGS_JSON" | sort -V | tail -1)"

    if [[ -z "$KONFLUX_TAG" || "$KONFLUX_TAG" == "null" ]]; then
        echo "Error: no konflux tag found at all for ODF ${VERSION} — cannot continue" >&2
        exit 1
    fi

    echo "WARNING: falling back to latest konflux tag by version sort -> ${KONFLUX_TAG}" >&2
fi

DIGEST="${STABLE_SHA#sha256:}"
PULL_SPEC="${REGISTRY}/${REPO}:${KONFLUX_TAG}"

case "$OUTPUT" in
    tag)
        echo "$KONFLUX_TAG"
        ;;
    json)
        jq -n \
            --arg tag "$KONFLUX_TAG" \
            --arg digest "sha256:${DIGEST}" \
            --arg pullSpec "$PULL_SPEC" \
            --arg stableTag "$STABLE_TAG" \
            '{tag: $tag, digest: $digest, pullSpec: $pullSpec, stableTag: $stableTag}'
        ;;
    pullspec|*)
        echo "$PULL_SPEC"
        ;;
esac
