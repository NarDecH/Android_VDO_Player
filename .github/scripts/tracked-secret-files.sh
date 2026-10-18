#!/usr/bin/env bash
#
# Fails if a credential-shaped file is tracked by git.
#
# `.gitignore` keeps such a file out of a casual `git add`. It does nothing against
# `git add -f`, against a file that was added before the rule existed, or against a
# clone whose ignore rules are stale — and once the file is in a commit, ignoring it
# later changes nothing. This gate looks at what is actually in the tree instead of
# at what is supposed to be kept out of it.
#
# The name list mirrors the "never commit these" blocks in .gitignore. When a new
# kind of credential file shows up, add it in both places: `.gitignore` keeps it out
# of the working tree, this keeps it out of the history.
#
# Usage:
#   bash .github/scripts/tracked-secret-files.sh          # the staged files
#   bash .github/scripts/tracked-secret-files.sh <rev>    # the tree of that revision
#
# CI passes no argument: after a fresh checkout the index is the commit under test,
# and the same command works locally before the commit exists.

set -euo pipefail

# Placeholders that are meant to be committed: they carry the shape of the variable,
# not a value, and failing on them would train people to ignore this check.
allowed() {
  case "$1" in
    .env.example | .env.sample | .env.template) return 0 ;;
  esac
  return 1
}

forbidden() {
  allowed "$1" && return 1

  case "$1" in
    # Helper-written git credentials, the file this repository has already seen.
    .git-credentials* | *.credentials | credentials.json) return 0 ;;
    # Keys and signing material.
    *.jks | *.keystore | *.p12 | *.pfx | *.ppk | keystore.properties) return 0 ;;
    *.pem | *.key | id_rsa* | id_ed25519*) return 0 ;;
    # Service credentials: Firebase config is a project API key plus an app id.
    google-services.json) return 0 ;;
    # Environment files, minus the placeholders above.
    .env | .env.*) return 0 ;;
  esac

  return 1
}

if [ "$#" -gt 0 ]; then
  # A revision: the tree as committed, not the working copy. Held in a variable
  # because inside the function the positional parameters are the function's own.
  rev=$1
  files() { git ls-tree -r -z --name-only "$rev"; }
else
  # No argument: the index, i.e. what `git commit` would record right now.
  files() { git ls-files -z; }
fi

files | {
  found=0
  # NUL-separated on purpose: file names in this repository contain spaces
  # ("run-emulator-playback-test.sh" is fine, "Test (AVD) - 14.xml" is not).
  while IFS= read -r -d '' path; do
    if forbidden "${path##*/}"; then
      printf 'credential-shaped file is tracked: %s\n' "$path"
      found=1
    fi
  done

  if [ "$found" -ne 0 ]; then
    printf '\n%s\n%s\n' \
      'Remove the file from the index and from the history, and treat whatever it held' \
      'as leaked: rotating the credential is the only fix that helps after a push.'
    exit 1
  fi

  printf 'no credential-shaped files are tracked\n'
}
