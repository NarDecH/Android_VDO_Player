#!/usr/bin/env bash
#
# Installs the pinned gitleaks release, verified against its published SHA-256 list.
#
# The version lives here rather than in each workflow: a scanner whose rules float
# starts failing commits that changed nothing, and two workflows that pin it
# separately would drift apart. The release is checked against the release's own
# checksum file, so a truncated or swapped download fails here instead of producing
# a scanner that quietly matches nothing.
#
# The CLI needs no licence key — that requirement belongs to the GitHub Action, for
# organisations — so the binary is enough.
#
# Usage:
#   bash .github/scripts/install-gitleaks.sh [install-dir]
#
# The default install directory is /usr/local/bin, which is the one on PATH for both
# workflows. `sudo` is used only when that directory is not writable by this user.

set -euo pipefail

version=8.30.1
dest=${1:-/usr/local/bin}

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=darwin ;;
  # Git Bash on a developer Windows machine: the archive is a zip, and the binary
  # needs its .exe suffix or bash will not run it from an explicit path.
  MINGW* | MSYS* | CYGWIN*) os=windows ;;
  *)
    echo "unsupported OS: $(uname -s)" >&2
    exit 1
    ;;
esac

case "$(uname -m)" in
  x86_64 | amd64) arch=x64 ;;
  arm64 | aarch64) arch=arm64 ;;
  *)
    echo "unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

if [ "$os" = windows ]; then
  ext=zip
  name=gitleaks.exe
else
  ext=tar.gz
  name=gitleaks
fi

asset="gitleaks_${version}_${os}_${arch}.${ext}"
base="https://github.com/gitleaks/gitleaks/releases/download/v${version}"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"

# The release host answers the odd 429 or 5xx under load, and a job that dies on one of
# those teaches people to re-run CI instead of reading the result. Retries are safe here
# because the checksum below, not the download, is what decides whether the bytes are
# right: a truncated or swapped asset still fails the check.
#
# A 404 or 410 is the exception. The pinned asset belongs to a release that is either
# published or not, so the next attempt finds it just as missing — waiting out the backoff
# only delays the report. To tell those apart from a 5xx, the status is read rather than
# curl's exit code: `-f` would flatten a missing asset and a busy host into the same
# failure, which is why the download is fetched without it and the code inspected here.
fetch() {
  url=$1
  out=$2
  attempt=1
  while :; do
    code=$(curl -sSLo "$out" -w '%{http_code}' "$url") || code=${code:-000}
    case "$code" in
      2??) return 0 ;;
      404 | 410)
        echo "$url is gone (HTTP $code); the pinned release no longer publishes it" >&2
        return 1
        ;;
    esac
    if [ "$attempt" -ge 5 ]; then
      echo "could not download $url after $attempt attempts (last HTTP $code)" >&2
      return 1
    fi
    echo "download of $url failed (HTTP $code), retrying in $((attempt * 5))s" >&2
    sleep $((attempt * 5))
    attempt=$((attempt + 1))
  done
}

fetch "$base/$asset" "$work/$asset"
fetch "$base/gitleaks_${version}_checksums.txt" "$work/checksums.txt"
# The checksum file lists one line per asset; only this platform's line is checked,
# and `--check` fails the script if the digest does not match.
(cd "$work" && grep -F "  $asset" checksums.txt | sha256sum --check -)

if [ "$ext" = zip ]; then
  unzip -qo "$work/$asset" -d "$work/bin"
else
  tar -xzf "$work/$asset" -C "$work/bin" gitleaks
fi

mkdir -p "$dest"
if [ -w "$dest" ]; then
  install -m 0755 "$work/bin/$name" "$dest/"
else
  sudo install -m 0755 "$work/bin/$name" "$dest/"
fi

"$dest/$name" version
