#!/usr/bin/env bash
# Resolve the Konflux ODF catalog index for an OCS/ODF minor version.
#
# Default (latest) resolution:
#   1. Paginate Quay for *.konflux tags matching the minor (filter like:{minor}.)
#   2. Return the highest tag by version sort (newest dev build)
#
# Stable resolution (--stable):
#   1. Find the highest latest-stable-{minor}.X rolling tag and read its digest
#   2. Match the *.konflux tag with the same digest
#   3. If no digest match, fall back to highest *.konflux with WARNING
#
# All diagnostic output (DEBUG/WARNING) goes to stderr only; stdout is clean.
#
# Usage:
#   ./ocs-build.sh <version>                  # latest *.konflux (default)
#   ./ocs-build.sh --stable <version>         # match latest-stable digest
#   ./ocs-build.sh --tag-only <version>       # tag name only
#   ./ocs-build.sh --json [--stable] <version>
#
# Example:
#   ./ocs-build.sh 4.23                       # 4.23.0-42.konflux (latest dev)
#   ./ocs-build.sh --stable 4.23              # 4.23.0-41.konflux (stable pointer)

set -euo pipefail

REPO="rhceph-dev/ocs-registry"
REGISTRY="quay.io"
API_BASE="https://quay.io/api/v1/repository/${REPO}/tag/"
OUTPUT="pullspec"
MODE="latest"
VERSION=""
PAGE_LIMIT=100

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag-only) OUTPUT="tag" ; shift ;;
        --json)     OUTPUT="json" ; shift ;;
        --stable)   MODE="stable" ; shift ;;
        -h|--help)
            sed -n '2,24p' "$0"
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
    echo "Usage: $0 [--tag-only|--json] [--stable] <version>" >&2
    echo "Example: $0 4.23" >&2
    exit 1
fi

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "Error: version must be major.minor (got '${VERSION}')" >&2
    exit 1
fi

# Fetch all tags matching filter_tag_name across Quay pages; prints merged JSON to stdout.
fetch_tags() {
    local filter="$1"
    local page=1
    local total=0
    local merged_file page_file
    merged_file="$(mktemp)"
    page_file="$(mktemp)"
    trap 'rm -f "$merged_file" "$page_file"' RETURN

    echo '{"tags":[]}' > "$merged_file"

    while true; do
        local url="${API_BASE}?onlyActiveTags=true&limit=${PAGE_LIMIT}&page=${page}&filter_tag_name=like:${filter}"
        echo "DEBUG: fetching tags page=${page} from ${url}" >&2

        curl -ks --connect-timeout 15 --max-time 30 "${url}" > "$page_file"
        local count
        count="$(jq '.tags | length' "$page_file" 2>/dev/null || echo 0)"

        if [[ "$count" -eq 0 ]]; then
            break
        fi

        jq -s '.[0].tags += .[1].tags | .[0]' "$merged_file" "$page_file" > "${merged_file}.new"
        mv "${merged_file}.new" "$merged_file"
        total=$((total + count))
        echo "DEBUG: page ${page}: ${count} tags (running total: ${total})" >&2

        if [[ "$count" -lt "$PAGE_LIMIT" ]]; then
            break
        fi
        page=$((page + 1))
    done

    echo "DEBUG: fetched ${total} tags total for filter '${filter}'" >&2
    cat "$merged_file"
}

# Return highest *.konflux tag name for VERSION from merged tags JSON on stdin.
pick_latest_konflux_tag() {
    jq -r --arg ver "$VERSION" \
        '.tags[] | select(.name | test("^" + $ver + "\\.[0-9]+-[0-9]+\\.konflux$")) | .name' \
        | sort -V | tail -1
}

# Return manifest_digest for a tag name from merged tags JSON on stdin.
digest_for_tag() {
    local tag="$1"
    jq -r --arg tag "$tag" \
        '.tags[] | select(.name == $tag) | .manifest_digest'
}

STABLE_TAG=""
KONFLUX_TAG=""
KONFLUX_DIGEST=""

if [[ "$MODE" == "latest" ]]; then
    echo "DEBUG: resolution mode -> latest konflux" >&2
    KONFLUX_JSON="$(fetch_tags "${VERSION}.")"
    KONFLUX_TAG="$(pick_latest_konflux_tag <<< "$KONFLUX_JSON")"

    if [[ -z "$KONFLUX_TAG" || "$KONFLUX_TAG" == "null" ]]; then
        echo "Error: no konflux tag found for ODF ${VERSION} — cannot continue" >&2
        exit 1
    fi

    KONFLUX_DIGEST="$(digest_for_tag "$KONFLUX_TAG" <<< "$KONFLUX_JSON")"
    echo "DEBUG: selected latest konflux tag -> ${KONFLUX_TAG}" >&2
else
    echo "DEBUG: resolution mode -> stable digest match" >&2
    STABLE_JSON="$(fetch_tags "latest-stable-${VERSION}.")"
    KONFLUX_JSON="$(fetch_tags "${VERSION}.")"

    STABLE_TAG="$(jq -r --arg ver "$VERSION" \
        '.tags[] | select(.name | test("^latest-stable-" + $ver + "\\.[0-9]+$")) | .name' \
        <<< "$STABLE_JSON" | sort -V | tail -1)"

    if [[ -z "$STABLE_TAG" || "$STABLE_TAG" == "null" ]]; then
        echo "Error: no latest-stable-${VERSION}.X tag found on ${REGISTRY}/${REPO}" >&2
        jq -r '.tags[].name' <<< "$STABLE_JSON" | sort >&2 || true
        exit 1
    fi

    echo "DEBUG: resolved stable tag -> ${STABLE_TAG}" >&2

    STABLE_SHA="$(digest_for_tag "$STABLE_TAG" <<< "$STABLE_JSON")"
    if [[ -z "$STABLE_SHA" || "$STABLE_SHA" == "null" ]]; then
        echo "Error: no manifest digest for tag ${STABLE_TAG}" >&2
        exit 1
    fi

    echo "DEBUG: digest for ${STABLE_TAG} -> ${STABLE_SHA}" >&2

    KONFLUX_TAG="$(jq -r --arg sha "$STABLE_SHA" --arg ver "$VERSION" \
        '.tags[] | select(.manifest_digest == $sha and (.name | test("^" + $ver + "\\.[0-9]+-[0-9]+\\.konflux$"))) | .name' \
        <<< "$KONFLUX_JSON" | sort -V | tail -1)"

    if [[ -z "$KONFLUX_TAG" || "$KONFLUX_TAG" == "null" ]]; then
        echo "WARNING: no konflux tag matching digest of ${STABLE_TAG} (${STABLE_SHA}) for ODF ${VERSION}" >&2
        echo "DEBUG: konflux candidates for ${VERSION}:" >&2
        jq -r --arg ver "$VERSION" \
            '.tags[] | select(.name | test("^" + $ver + "\\.[0-9]+-[0-9]+\\.konflux$")) | "\(.name)  digest=\(.manifest_digest)"' \
            <<< "$KONFLUX_JSON" | sort >&2 || echo "  (none)" >&2

        KONFLUX_TAG="$(pick_latest_konflux_tag <<< "$KONFLUX_JSON")"
        if [[ -z "$KONFLUX_TAG" || "$KONFLUX_TAG" == "null" ]]; then
            echo "Error: no konflux tag found at all for ODF ${VERSION} — cannot continue" >&2
            exit 1
        fi
        echo "WARNING: falling back to latest konflux tag by version sort -> ${KONFLUX_TAG}" >&2
    fi

    KONFLUX_DIGEST="$(digest_for_tag "$KONFLUX_TAG" <<< "$KONFLUX_JSON")"
    echo "DEBUG: selected konflux tag -> ${KONFLUX_TAG}" >&2
fi

if [[ -z "$KONFLUX_DIGEST" || "$KONFLUX_DIGEST" == "null" ]]; then
    echo "Error: no manifest digest for konflux tag ${KONFLUX_TAG}" >&2
    exit 1
fi

echo "DEBUG: digest for ${KONFLUX_TAG} -> ${KONFLUX_DIGEST}" >&2

PULL_SPEC="${REGISTRY}/${REPO}:${KONFLUX_TAG}"

case "$OUTPUT" in
    tag)
        echo "$KONFLUX_TAG"
        ;;
    json)
        jq -n \
            --arg tag "$KONFLUX_TAG" \
            --arg digest "$KONFLUX_DIGEST" \
            --arg pullSpec "$PULL_SPEC" \
            --arg stableTag "$STABLE_TAG" \
            '{tag: $tag, digest: $digest, pullSpec: $pullSpec, stableTag: $stableTag}'
        ;;
    pullspec|*)
        echo "$PULL_SPEC"
        ;;
esac
