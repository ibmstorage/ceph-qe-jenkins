#!/usr/bin/env bash
# Commit message format checker.
# Called by pre-commit at the commit-msg stage with the path to the message file.
#
# Rules:
#   1. Subject line must not be empty.
#   2. Subject line must be ≤ 72 characters.
#   3. If a body is present, the second line must be blank (separating subject
#      from body).
#   4. Subject must start with one of the recognised type prefixes:
#        feat | fix | docs | refactor | chore | test | ci | style | revert
#      followed by an optional scope in parentheses and a colon, e.g.:
#        feat(metadata): add tentacle SMB suites
#        fix: correct global-conf paths in nvmeof.yaml
#        docs: update architecture overview
#   5. At least one "Signed-off-by: Name <email>" trailer must be present
#      (DCO — Developer Certificate of Origin).
#      Add automatically with:  git commit --signoff

set -euo pipefail

MSG_FILE="${1:?commit message file path required}"
SUBJECT=$(head -1 "$MSG_FILE")

PASS=0
FAIL=1

# Strip leading/trailing whitespace for the empty-check
TRIMMED="${SUBJECT#"${SUBJECT%%[![:space:]]*}"}"
TRIMMED="${TRIMMED%"${TRIMMED##*[![:space:]]}"}"

# 1. Subject must not be empty
if [[ -z "$TRIMMED" ]]; then
  echo "commit-msg: subject line is empty" >&2
  exit $FAIL
fi

# 2. Subject ≤ 72 characters
LENGTH=${#SUBJECT}
if (( LENGTH > 72 )); then
  echo "commit-msg: subject line is ${LENGTH} characters (max 72): ${SUBJECT}" >&2
  exit $FAIL
fi

# 3. Second line must be blank when a body is present
SECOND=$(sed -n '2p' "$MSG_FILE")
if [[ -n "$SECOND" ]]; then
  echo "commit-msg: line 2 must be blank (separate subject from body), got: ${SECOND}" >&2
  exit $FAIL
fi

# 4. Conventional commit type prefix
PATTERN='^(feat|fix|docs|refactor|chore|test|ci|style|revert)(\([^)]+\))?: .+'
if ! echo "$SUBJECT" | grep -qE "$PATTERN"; then
  echo "commit-msg: subject must match '<type>[(<scope>)]: <description>'" >&2
  echo "  Allowed types: feat, fix, docs, refactor, chore, test, ci, style, revert" >&2
  echo "  Examples:" >&2
  echo "    feat(metadata): add tentacle SMB suites" >&2
  echo "    fix: correct global-conf paths in nvmeof.yaml" >&2
  echo "    docs: update architecture overview" >&2
  echo "  Got: ${SUBJECT}" >&2
  exit $FAIL
fi

# 5. DCO sign-off — at least one valid Signed-off-by trailer
SOB_PATTERN='^Signed-off-by: .+ <.+@.+>$'
if ! grep -qE "$SOB_PATTERN" "$MSG_FILE"; then
  echo "commit-msg: missing 'Signed-off-by' trailer (DCO required)" >&2
  echo "  Add with:  git commit --signoff" >&2
  echo "  Expected:  Signed-off-by: Your Name <you@example.com>" >&2
  exit $FAIL
fi

exit $PASS
