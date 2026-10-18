#!/usr/bin/env bash
#
# Scans the commits a change adds for credentials. Requires gitleaks on PATH.
#
# Only the commits the change adds: re-reporting the whole history on every run
# would bury today's mistake in yesterday's noise, and deciding what to do about
# old history is a separate question. Both workflows call this, so a pull request,
# a push to `main` and a version tag are judged by the same rule.
#
# Usage:
#   bash .github/scripts/scan-commits-for-secrets.sh [range]
#
# Without an argument the range is derived from the event, through the variables
# the workflows pass in: EVENT_NAME, BASE_REF, BEFORE, AFTER.

set -euo pipefail

if [ "$#" -gt 0 ]; then
  range=$1
else
  event=${EVENT_NAME:-}
  before=${BEFORE:-}
  after=${AFTER:-HEAD}

  if [ "$event" = pull_request ]; then
    if [ -z "${BASE_REF:-}" ]; then
      echo 'BASE_REF is required to scan a pull request' >&2
      exit 1
    fi
    range="origin/${BASE_REF}..HEAD"
  elif [ -z "$before" ] || printf '%s' "$before" | grep -Eq '^0+$'; then
    # A tag push, a branch's first push and a manual dispatch all arrive without a
    # usable `before`. The commit being released or pushed is the one that has to be
    # clean, so that is what gets scanned.
    range="${after}~1..${after}"
  else
    range="${before}..${after}"
  fi
fi

echo "scanning $range"

# -v names the file, line and rule a developer needs to fix it; --redact keeps the
# value out of the log, since a log that repeats the secret widens the leak this
# check exists to stop.
exec gitleaks git --redact=100 --exit-code 1 -v --log-opts="$range" .
