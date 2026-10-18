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

curl -fsSLo "$work/$asset" "$base/$asset"
curl -fsSLo "$work/checksums.txt" "$base/gitleaks_${version}_checksums.txt"
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
