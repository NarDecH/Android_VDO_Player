#!/usr/bin/env bash
#
# Proves the guards in write-release-notes.sh actually fire.
#
# That script is the last thing between a mistake and the download page: it refuses
# a set of APKs that disagree on version or key, one that is not v2 signed, and an
# empty directory. A check nobody has seen fail is a check nobody knows works, so
# this builds small APKs of its own and feeds the real script one broken set at a
# time — plus the good set, which has to pass.
#
# The fixtures are made here rather than taken from a Gradle build so the test
# needs nothing compiled: aapt2 turns a three-line manifest into a valid APK and
# apksigner signs it with a throwaway key. That is a few seconds on the SDK the
# release job already installs, and it lets the mismatches be exact — a second
# version, a second key, a signature scheme left out.
#
# Usage: test-release-notes.sh
#
# Requires ANDROID_HOME with build-tools 37.0.0 and a platform, plus keytool (on
# PATH, or under $JAVA_HOME/bin).
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$repo_root/.github/scripts/write-release-notes.sh"
build_tools="${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools/37.0.0"

# The Windows SDK ships the wrapper only, exactly as write-release-notes.sh expects.
apksigner="$build_tools/apksigner"
if [[ ! -x $apksigner ]]; then
  apksigner="$build_tools/apksigner.bat"
fi
aapt2="$build_tools/aapt2"
zipalign="$build_tools/zipalign"
for tool in "$aapt2" "$zipalign" "$apksigner"; do
  if [[ ! -f $tool ]]; then
    echo "missing $tool — is Android SDK build-tools 37.0.0 installed?" >&2
    exit 1
  fi
done

# Whatever platform the project currently compiles against, highest first.
android_jar=''
for jar in "$ANDROID_HOME"/platforms/android-*/android.jar; do
  if [[ -f $jar ]]; then
    android_jar=$jar
  fi
done
if [[ -z $android_jar ]]; then
  echo "no android.jar under $ANDROID_HOME/platforms" >&2
  exit 1
fi

keytool_bin=$(command -v keytool || true)
if [[ -z $keytool_bin && -n ${JAVA_HOME:-} && -x $JAVA_HOME/bin/keytool ]]; then
  keytool_bin="$JAVA_HOME/bin/keytool"
fi
if [[ -z $keytool_bin ]]; then
  echo "no keytool on PATH or under JAVA_HOME — a JDK is required" >&2
  exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

store_pass=fixture-pass

failures=0
pass() { printf 'ok    %s\n' "$1"; }
fail() {
  printf 'FAIL  %s\n' "$1"
  printf '%s\n' "$2" | sed 's/^/      /'
  failures=$((failures + 1))
}

make_keystore() { # <file> <distinguished-name>
  "$keytool_bin" -genkeypair -keystore "$1" -storetype PKCS12 \
    -storepass "$store_pass" -keypass "$store_pass" -alias fixture \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "$2" >/dev/null 2>&1
}

# The v1-only fixture needs a target SDK that still allows a v1-only signature;
# from 30 up, apksigner rejects the signing request before the script is reached.
fixture_target_sdk=37

# make_apk <out> <version-code> <version-name> <keystore> [extra apksigner args...]
make_apk() {
  local out=$1 code=$2 name=$3 keystore=$4
  shift 4
  local dir
  dir=$(mktemp -d "$work/build.XXXXXX")
  cat >"$dir/AndroidManifest.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.fixture">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="$fixture_target_sdk" />
    <application android:label="fixture" />
</manifest>
EOF
  "$aapt2" link -o "$dir/unaligned.apk" -I "$android_jar" \
    --manifest "$dir/AndroidManifest.xml" \
    --version-code "$code" --version-name "$name"
  "$zipalign" -f -p 4 "$dir/unaligned.apk" "$dir/aligned.apk"
  "$apksigner" sign --ks "$keystore" --ks-pass "pass:$store_pass" \
    --ks-key-alias fixture --out "$out" "$@" "$dir/aligned.apk" >/dev/null
}

# The four names the release publishes. Every fixture set below differs from the
# good one in exactly one of them, so the failure is never ambiguous.
release_variants=(arm64-v8a armeabi-v7a x86_64 universal)

fill() { # <dir> <apk> [<overrides: file=apk> ...]
  local dir=$1 apk=$2
  shift 2
  mkdir -p "$dir"
  local variant
  for variant in "${release_variants[@]}"; do
    cp "$apk" "$dir/app-$variant-release.apk"
  done
  local override file
  for override in "$@"; do
    file=${override%%=*}
    cp "${override#*=}" "$dir/app-$file-release.apk"
  done
}

echo "fixtures in $work"
make_keystore "$work/a.p12" "CN=Fixture A,O=Release Notes Test"
make_keystore "$work/b.p12" "CN=Fixture B,O=Release Notes Test"
make_apk "$work/one.apk" 1 1.0 "$work/a.p12"
make_apk "$work/two.apk" 2 2.0 "$work/a.p12"
make_apk "$work/other-key.apk" 1 1.0 "$work/b.p12"
# v1 only: the signature a stock `apksigner sign` does not produce, and the one
# this project has never shipped — but a hand-signed file can arrive that way.
fixture_target_sdk=26 make_apk "$work/v1-only.apk" 1 1.0 "$work/a.p12" \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false

good="$work/good"
fill "$good" "$work/one.apk"
fill "$work/other-key" "$work/one.apk" "universal=$work/other-key.apk"
fill "$work/other-version" "$work/one.apk" "universal=$work/two.apk"
fill "$work/v1-only" "$work/one.apk" "universal=$work/v1-only.apk"
fill "$work/unlisted" "$work/one.apk"
cp "$work/one.apk" "$work/unlisted/app-mystery-release.apk"
mkdir -p "$work/empty"

run() { # <apk-dir> <tag> <out>; leaves combined output in $output, returns the status
  local status=0
  output=$(bash "$script" "$1" "$2" "$3" 2>&1) || status=$?
  return $status
}

expect_pass() { # <label> <apk-dir> <tag>
  local label=$1
  if ! run "$2" "$3" "$work/notes.md"; then
    fail "$label" "$output"
    return 1
  fi
  pass "$label"
}

expect_refusal() { # <label> <expected message> <apk-dir> <tag>
  local label=$1 want=$2
  if run "$3" "$4" "$work/refused.md"; then
    fail "$label — expected a failure, got exit 0" "$output"
    return 1
  fi
  if [[ $output != *"$want"* ]]; then
    fail "$label — failed for the wrong reason, wanted '$want'" "$output"
    return 1
  fi
  pass "$label"
}

notes=$work/notes.md

# The good set: this is also the shape the release page is meant to have.
if expect_pass 'four APKs from one build and key pass' "$good" v9.9.9; then
  missing=''
  for variant in "${release_variants[@]}"; do
    file="app-$variant-release.apk"
    if ! grep -qF "| \`$file\` |" "$notes"; then
      missing+=" $file"
    fi
  done
  if [[ -n $missing ]]; then
    fail 'every uploaded APK is listed in the table' "not listed:$missing"
  else
    pass 'every uploaded APK is listed in the table'
  fi

  # A tag with no prose file gets a body that starts at the table, not a blank
  # section where the copy would have been.
  if [[ $(head -n 1 "$notes") == '## APKs' ]]; then
    pass 'a tag without prose starts at the APK table'
  else
    fail 'a tag without prose starts at the APK table' "$(head -n 3 "$notes")"
  fi

  # The digests are written twice (table and block) from one sha256sum call; the
  # block is the half a reader can feed to `sha256sum -c`, so it has to verify.
  awk '/^## SHA-256$/{inside=1; next} inside && /^```$/{fences++; next} inside && fences==1' \
    "$notes" >"$work/SHA256SUMS"
  if [[ -s $work/SHA256SUMS ]] && (cd "$good" && sha256sum -c "$work/SHA256SUMS" >/dev/null 2>&1); then
    pass 'the SHA256SUMS block verifies against the files'
  else
    fail 'the SHA256SUMS block verifies against the files' "$(cat "$work/SHA256SUMS" 2>/dev/null)"
  fi
fi

# A split this script has not heard of is still uploaded, so it has to be in the
# table as well; this is the one case where the list of known names is not enough.
if expect_pass 'an APK outside the four known variants passes' "$work/unlisted" v9.9.9; then
  if grep -qF '| `app-mystery-release.apk` |' "$notes"; then
    pass 'an APK outside the four known variants is listed too'
  else
    fail 'an APK outside the four known variants is listed too' "$(cat "$notes")"
  fi
fi

# The prose is the release's own copy, read from docs/releases/<tag>.md.
if expect_pass 'a tag with prose gets it prepended' "$good" v1.3.0; then
  if grep -qF "## What's new" "$notes"; then
    pass 'the prose appears above the APK table'
  else
    fail 'a tag with prose gets it prepended' "$(head -n 5 "$notes")"
  fi
fi

expect_refusal 'APKs signed with two keys are refused' \
  'not all signed by the same key' "$work/other-key" v9.9.9
expect_refusal 'APKs from two versions are refused' \
  'not all built from the same version' "$work/other-version" v9.9.9
expect_refusal 'an APK without a v2 signature is refused' \
  'is not signed with the v2 scheme' "$work/v1-only" v9.9.9
expect_refusal 'an empty APK directory is refused' \
  'no APKs in' "$work/empty" v9.9.9

if ((failures)); then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
echo 'all release-note checks passed'
