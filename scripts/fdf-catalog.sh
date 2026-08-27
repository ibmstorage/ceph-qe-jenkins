#!/usr/bin/env bash
# Resolve the latest IBM FDF catalog index image for an OCP/ODF minor version.
#
# Uses skopeo list-tags against the staging ICR catalog image:
#   docker://cp.stg.icr.io/cp/df/isf-data-foundation-catalog
#
# Tags are filtered to v{minor}* (e.g. v4.20, v4.20.13-1) and the highest
# semver tag is selected.
#
# Usage:
#   ./fdf-catalog.sh <version>              # pull spec only
#   ./fdf-catalog.sh --authfile PATH <version>
#   ./fdf-catalog.sh --tag-only <version>   # tag name only
#   ./fdf-catalog.sh --json <version>       # {"tag":"...","pullSpec":"..."}
#
# Example:
#   ./fdf-catalog.sh --authfile ~/pull-secret.json 4.20

set -euo pipefail

REGISTRY="cp.stg.icr.io"
REPO_PATH="cp/df/isf-data-foundation-catalog"
IMAGE="docker://${REGISTRY}/${REPO_PATH}"
OUTPUT="pullspec"
VERSION=""
AUTHFILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag-only) OUTPUT="tag" ; shift ;;
        --json)     OUTPUT="json" ; shift ;;
        --authfile)
            if [[ $# -lt 2 ]]; then
                echo "Error: --authfile requires a path" >&2
                exit 1
            fi
            AUTHFILE="$2"
            shift 2
            ;;
        -h|--help)
            sed -n '2,17p' "$0"
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
    echo "Usage: $0 [--authfile PATH] [--tag-only|--json] <version>" >&2
    echo "Example: $0 --authfile ~/pull-secret.json 4.20" >&2
    exit 1
fi

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+$ ]]; then
    echo "Error: version must be major.minor (got '${VERSION}')" >&2
    exit 1
fi

if ! command -v skopeo >/dev/null 2>&1; then
    echo "Error: skopeo is required but not found in PATH" >&2
    exit 1
fi

if [[ -z "$AUTHFILE" ]]; then
    echo "Error: --authfile is required (registry pull secret for ${REGISTRY})" >&2
    exit 1
fi
if [[ ! -f "$AUTHFILE" ]]; then
    echo "Error: authfile not found: ${AUTHFILE}" >&2
    exit 1
fi

SKOPEO_ARGS=(list-tags --tls-verify=false --authfile "$AUTHFILE" "$IMAGE")

echo "DEBUG: listing tags for ${IMAGE} (authfile=${AUTHFILE})" >&2

TAGS_JSON="$(skopeo "${SKOPEO_ARGS[@]}" 2>/dev/null)" || {
    echo "Error: skopeo list-tags failed for ${IMAGE}" >&2
    exit 1
}

PREFIX="v${VERSION}"
BEST_TAG="$(jq -r --arg prefix "${PREFIX}" \
    '.Tags[] | select(startswith($prefix))' \
    <<< "$TAGS_JSON" 2>/dev/null | sort -V | tail -1)"

if [[ -z "$BEST_TAG" || "$BEST_TAG" == "null" ]]; then
    echo "Error: no tag matching ${PREFIX}* found on ${REGISTRY}/${REPO_PATH}" >&2
    echo "DEBUG: available tags:" >&2
    jq -r '.Tags[]?' <<< "$TAGS_JSON" 2>/dev/null | sort -V | tail -20 >&2 || true
    exit 1
fi

echo "DEBUG: resolved tag -> ${BEST_TAG}" >&2

PULL_SPEC="${REGISTRY}/${REPO_PATH}:${BEST_TAG}"

case "$OUTPUT" in
    tag)
        echo "$BEST_TAG"
        ;;
    json)
        jq -n \
            --arg tag "$BEST_TAG" \
            --arg pullSpec "$PULL_SPEC" \
            '{tag: $tag, pullSpec: $pullSpec}'
        ;;
    pullspec|*)
        echo "$PULL_SPEC"
        ;;
esac
