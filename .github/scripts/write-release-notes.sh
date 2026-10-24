#!/usr/bin/env bash
#
# Writes the body of a GitHub release: the prose kept in docs/releases/<tag>.md
# when there is one, followed by a verification block generated from the APKs
# that are about to be uploaded.
#
# Those numbers used to be retyped by hand out of the run summary when the draft
# was published — the one step where a wrong digit reaches the download page, and
# nothing checked that the four files really carry one certificate. Here the
# table, the sizes and the digests are read from the files themselves, and a
# mismatch fails the job instead of shipping a release that says otherwise.
#
# The digests appear twice on purpose: once in the table, where the eye finds the
# file it is about to install, and once as a `SHA256SUMS` block, which is the form
# that can be saved next to the downloads and handed to `sha256sum -c` instead of
# being compared character by character.
#
# Usage: write-release-notes.sh <apk-dir> <tag> <output-file>
#
# Requires aapt2 and apksigner from $ANDROID_HOME/build-tools/37.0.0.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $(basename "$0") <apk-dir> <tag> <output-file>" >&2
  exit 2
fi

apk_dir=$1
tag=$2
out=$3

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
build_tools="${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools/37.0.0"
aapt2="$build_tools/aapt2"
# The Windows SDK ships the wrapper only; the workflow itself runs on Linux.
apksigner="$build_tools/apksigner"
if [[ ! -x "$apksigner" ]]; then
  apksigner="$build_tools/apksigner.bat"
fi
for tool in "$aapt2" "$apksigner"; do
  if [[ ! -f "$tool" ]]; then
    echo "missing $tool — is Android SDK build-tools 37.0.0 installed?" >&2
    exit 1
  fi
done

# Grouping by hand: numfmt and printf %'d follow LC_NUMERIC, and the locale of a
# GitHub runner is not something this table should depend on.
group_digits() {
  awk -v n="$1" 'BEGIN {
    s = sprintf("%d", n); out = ""
    while (length(s) > 3) {
      out = "," substr(s, length(s) - 2) out
      s = substr(s, 1, length(s) - 3)
    }
    print s out
  }'
}

shopt -s nullglob

# Release order, not alphabetical: the recommended download first and the
# universal build last.
apks=()
for variant in arm64-v8a armeabi-v7a x86_64 universal; do
  candidate="$apk_dir/app-$variant-release.apk"
  if [[ -f "$candidate" ]]; then
    apks+=("$candidate")
  fi
done
# A split this list has not heard of still belongs in the notes: an APK the
# workflow uploads while the table omits it is a file nobody can verify.
for apk in "$apk_dir"/*.apk; do
  case "$apk" in
    */app-arm64-v8a-release.apk | */app-armeabi-v7a-release.apk | */app-x86_64-release.apk | */app-universal-release.apk) ;;
    *) apks+=("$apk") ;;
  esac
done
if [[ ${#apks[@]} -eq 0 ]]; then
  echo "no APKs in $apk_dir" >&2
  exit 1
fi

rows=()
sums=()
builds=()
certs=()

for apk in "${apks[@]}"; do
  file=$(basename "$apk")
  echo "--- $file"

  badging=$("$aapt2" dump badging "$apk")
  pkg=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | tr -d '\r')
  code=$(sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | tr -d '\r')
  version=$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<<"$badging" | tr -d '\r')
  min_sdk=$(sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging" | tr -d '\r')
  abis=$(sed -n 's/^native-code: //p' <<<"$badging" | tr -d "'\r" | sed 's/^ *//; s/ *$//')
  abis=${abis// /, }
  if [[ -z $pkg || -z $code || -z $version || -z $min_sdk ]]; then
    echo "could not read the manifest of $file" >&2
    exit 1
  fi

  verify=$("$apksigner" verify --verbose --print-certs "$apk")
  if ! grep -Eq '^Verified using v2 scheme \(APK Signature Scheme v2\): true' <<<"$verify"; then
    echo "$file is not signed with the v2 scheme" >&2
    exit 1
  fi
  signers=$(sed -n 's/^Number of signers: //p' <<<"$verify" | tr -d '\r')
  if [[ $signers != 1 ]]; then
    echo "$file has $signers signers, expected 1" >&2
    exit 1
  fi
  # Matches both spellings the tool has used: "Signer #1 certificate DN:" and
  # the "V2 Signer: certificate DN:" of newer build-tools.
  dn=$(sed -n 's/^.*certificate DN: //p' <<<"$verify" | tr -d '\r')
  cert_sha=$(sed -n 's/^.*certificate SHA-256 digest: //p' <<<"$verify" | tr -d '\r')
  key_alg=$(sed -n 's/^.*key algorithm: //p' <<<"$verify" | tr -d '\r')
  key_bits=$(sed -n 's/^.*key size (bits): //p' <<<"$verify" | tr -d '\r')
  if [[ -z $dn || -z $cert_sha ]]; then
    echo "could not read the signing certificate of $file" >&2
    exit 1
  fi
  printf 'certificate: %s (%s %s bits)\n  SHA-256: %s\n' "$dn" "$key_alg" "$key_bits" "$cert_sha"

  size=$(stat -c %s "$apk")
  mib=$(awk -v bytes="$size" 'BEGIN { printf "%.1f", bytes / 1048576 }')
  digest=$(sha256sum "$apk" | cut -d' ' -f1)
  rows+=("| \`$file\` | ${abis:-none} | $(group_digits "$size") bytes ($mib MiB) | \`$digest\` |")
  # Two spaces between digest and name: the format `sha256sum -c` parses.
  sums+=("$digest  $file")

  builds+=("$pkg|$code|$version|$min_sdk")
  certs+=("$dn|$cert_sha|$key_alg|$key_bits")
done

# One version, one certificate: the release page says the files are the same
# build, and each installs over an older version rather than beside it. Both are
# facts of the upload, not of the workflow, so they are checked here.
unique_build=$(printf '%s\n' "${builds[@]}" | sort -u)
if [[ $(wc -l <<<"$unique_build") -ne 1 ]]; then
  echo "the APKs were not all built from the same version:" >&2
  printf '  %s\n' "$unique_build" >&2
  exit 1
fi
unique_cert=$(printf '%s\n' "${certs[@]}" | sort -u)
if [[ $(wc -l <<<"$unique_cert") -ne 1 ]]; then
  echo "the APKs are not all signed by the same key:" >&2
  printf '  %s\n' "$unique_cert" >&2
  exit 1
fi

IFS='|' read -r pkg code version min_sdk <<<"$unique_build"
IFS='|' read -r dn cert_sha key_alg key_bits <<<"$unique_cert"

prose="$repo_root/docs/releases/$tag.md"
# The workflow runs on GitHub Actions; a local run is only a preview of the body.
if [[ -n ${GITHUB_ACTIONS:-} ]]; then
  builder=' by GitHub Actions'
else
  builder=''
fi
# A debug-signed release is a documented fallback when the signing secrets are
# absent, so the body says which key signed it instead of claiming the release one.
if [[ $dn == *"Android Debug"* ]]; then
  debug_signed=1
  key_label='the Android debug key'
else
  debug_signed=''
  key_label="the project's own release key"
fi
{
  if [[ -f "$prose" ]]; then
    cat "$prose"
    printf '\n'
  fi

  printf '## APKs\n\n'
  printf '| File | Native code | Size | SHA-256 |\n'
  printf '| --- | --- | --- | --- |\n'
  printf '%s\n' "${rows[@]}"
  printf '\n'
  printf '`arm64-v8a` fits almost every phone sold in the last few years; '
  printf '`universal` carries every ABI, for anything unusual, and is about twice the size.\n\n'
  printf 'Version `%s` (versionCode %s), package `%s`, minimum Android API %s.\n\n' \
    "$version" "$code" "$pkg" "$min_sdk"
  printf 'Built%s from the `%s` tag and signed with %s: `%s` (%s %s bits), certificate SHA-256\n`%s`,\n' \
    "$builder" "$tag" "$key_label" "$dn" "$key_alg" "$key_bits" "$cert_sha"
  printf 'the digest `apksigner verify --print-certs` prints for a downloaded file. '
  printf 'One certificate across all of them, so each installs over an older version signed with it and never beside one.\n\n'

  # Read out of the same `sha256sum` calls that fill the table, so the two can
  # never disagree; the line order is release order, which is the order the table
  # shows and the order a reader downloads them in.
  printf '## SHA-256\n\n'
  printf 'Save this block beside the downloads as `SHA256SUMS`, then run `sha256sum -c SHA256SUMS`:\n\n'
  printf '```\n'
  printf '%s\n' "${sums[@]}"
  printf '```\n'

  if [[ -n $debug_signed ]]; then
    printf '\n> **Debug signed** — the signing secrets were missing from the workflow run, so these APKs cannot replace an installed release build.\n'
  fi
} > "$out"

echo "wrote $out (${#apks[@]} APKs, ${version} / versionCode ${code})"
