#!/usr/bin/env bash
#
# Proves the guards in write-release-notes.sh actually fire.
#
# That script is the last thing between a mistake and the download page: it refuses
# a set of APKs that disagree on version or key, one that is not v2 signed or
# carries more than one signer, and an empty directory. It also writes the table
# out of the files themselves, which is why some sets here are good on purpose: the
# listing and the native-code column have to match the APK they describe, not the
# name it was given. A check nobody has seen fail is a check nobody knows works, so
# this builds small APKs of its own and feeds the real script one broken set at a
# time — plus the good sets, which have to pass.
#
# The fixtures are made here rather than taken from a Gradle build so the test
# needs nothing compiled: aapt2 turns a three-line manifest into a valid APK,
# apksigner signs it with a throwaway key, and the JDK's jar adds the lib/<abi>/
# entries that give an APK native code. That is a few seconds on the SDK the release
# job already installs, and it lets the mismatches be exact — a second version, a
# second key, a signature scheme left out.
#
# Usage: test-release-notes.sh
#
# Requires ANDROID_HOME with build-tools 37.0.0 and a platform, plus a full JDK
# (keytool and jar; on PATH, or under $JAVA_HOME/bin).
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

# jar sits beside keytool in every JDK; neither `zip` nor any other archiver is
# assumed, because the release job's images and a developer's machine need not
# agree on one.
jar_bin="$(dirname "$keytool_bin")/jar"
if [[ ! -x $jar_bin && -x $jar_bin.exe ]]; then
  jar_bin="$jar_bin.exe"
fi
if [[ ! -x $jar_bin ]]; then
  echo "no jar beside $keytool_bin — a full JDK is required" >&2
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

make_keystore() { # <file> <distinguished-name> <alias>
  "$keytool_bin" -genkeypair -keystore "$1" -storetype PKCS12 \
    -storepass "$store_pass" -keypass "$store_pass" -alias "$3" \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "$2" >/dev/null 2>&1
}

# The v1-only fixture needs a target SDK that still allows a v1-only signature;
# from 30 up, apksigner rejects the signing request before the script is reached.
fixture_target_sdk=37

# make_unsigned <out> <version-code> <version-name>
make_unsigned() {
  local out=$1 code=$2 name=$3 dir
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
  "$zipalign" -f -p 4 "$dir/unaligned.apk" "$out"
}

# make_apk <out> <version-code> <version-name> <keystore> <alias> [extra apksigner args...]
make_apk() {
  local out=$1 code=$2 name=$3 keystore=$4 alias=$5
  shift 5
  local dir
  dir=$(mktemp -d "$work/build.XXXXXX")
  make_unsigned "$dir/unsigned.apk" "$code" "$name"
  "$apksigner" sign --ks "$keystore" --ks-pass "pass:$store_pass" \
    --ks-key-alias "$alias" --out "$out" "$@" "$dir/unsigned.apk" >/dev/null
}

# make_apk_with_libs <out> <version-code> <version-name> <keystore> <alias> <abi>...
#
# The native-code line of `aapt2 dump badging` comes from the lib/<abi>/ entries in
# the archive, so an APK that should look like a per-ABI split needs entries there.
# A few bytes named like a library are enough: the column is the directory name.
# jar updates the zip in place and appends nothing else, so the file stays an APK.
make_apk_with_libs() {
  local out=$1 code=$2 name=$3 keystore=$4 alias=$5
  shift 5
  local dir abi
  dir=$(mktemp -d "$work/build.XXXXXX")
  make_unsigned "$dir/unsigned.apk" "$code" "$name"
  for abi in "$@"; do
    mkdir -p "$dir/lib/$abi"
    printf 'fixture' >"$dir/lib/$abi/libfixture.so"
  done
  "$jar_bin" --update --file "$dir/unsigned.apk" -C "$dir" lib
  "$zipalign" -f -p 4 "$dir/unsigned.apk" "$dir/aligned.apk"
  "$apksigner" sign --ks "$keystore" --ks-pass "pass:$store_pass" \
    --ks-key-alias "$alias" --out "$out" "$dir/aligned.apk" >/dev/null
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
make_keystore "$work/a.p12" "CN=Fixture A,O=Release Notes Test" fixture-a
make_keystore "$work/b.p12" "CN=Fixture B,O=Release Notes Test" fixture-b
make_apk "$work/one.apk" 1 1.0 "$work/a.p12" fixture-a
make_apk "$work/two.apk" 2 2.0 "$work/a.p12" fixture-a
make_apk "$work/other-key.apk" 1 1.0 "$work/b.p12" fixture-b
# v1 only: the signature a stock `apksigner sign` does not produce, and the one
# this project has never shipped — but a hand-signed file can arrive that way.
fixture_target_sdk=26 make_apk "$work/v1-only.apk" 1 1.0 "$work/a.p12" fixture-a \
  --v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false
# Two signers in one file: the v2 scheme carries both, but v3 refuses two
# certificates without a rotation lineage and v1 insists the signer names differ,
# so the other schemes are turned off to build the file at all.
make_unsigned "$work/two-signers-unsigned.apk" 1 1.0
"$apksigner" sign \
  --ks "$work/a.p12" --ks-pass "pass:$store_pass" --ks-key-alias fixture-a \
  --next-signer \
  --ks "$work/b.p12" --ks-pass "pass:$store_pass" --ks-key-alias fixture-b \
  --v1-signing-enabled false --v3-signing-enabled false --v4-signing-enabled false \
  --out "$work/two-signers.apk" "$work/two-signers-unsigned.apk" >/dev/null

good="$work/good"
fill "$good" "$work/one.apk"
fill "$work/other-key" "$work/one.apk" "universal=$work/other-key.apk"
fill "$work/other-version" "$work/one.apk" "universal=$work/two.apk"
fill "$work/v1-only" "$work/one.apk" "universal=$work/v1-only.apk"
fill "$work/two-signers" "$work/one.apk" "universal=$work/two-signers.apk"
fill "$work/unlisted" "$work/one.apk"
cp "$work/one.apk" "$work/unlisted/app-mystery-release.apk"

# Native code: the shape the real release has, one ABI per split and three in the
# universal file. The universal one is the file no variant name can imply, which is
# what makes the column worth checking at all.
make_apk_with_libs "$work/arm64.apk" 1 1.0 "$work/a.p12" fixture-a arm64-v8a
make_apk_with_libs "$work/v7a.apk" 1 1.0 "$work/a.p12" fixture-a armeabi-v7a
make_apk_with_libs "$work/x86.apk" 1 1.0 "$work/a.p12" fixture-a x86_64
make_apk_with_libs "$work/universal.apk" 1 1.0 "$work/a.p12" fixture-a \
  arm64-v8a armeabi-v7a x86_64
fill "$work/abis" "$work/one.apk" \
  "arm64-v8a=$work/arm64.apk" "armeabi-v7a=$work/v7a.apk" \
  "x86_64=$work/x86.apk" "universal=$work/universal.apk"

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

  # These fixtures carry no native code at all, so the column has to say so
  # instead of leaving an empty cell a reader would take for a bug.
  if grep -qF '| `app-arm64-v8a-release.apk` | none |' "$notes"; then
    pass 'an APK without native code says none'
  else
    fail 'an APK without native code says none' "$(grep arm64 "$notes")"
  fi
fi

# The column is read out of the archive, so it has to follow the file it is in and
# not the name the workflow gave it.
if expect_pass 'APKs that carry native code pass' "$work/abis" v9.9.9; then
  wrong=''
  while IFS='|' read -r file native; do
    if ! grep -qF "| \`$file\` | $native |" "$notes"; then
      wrong+=" ${file}: wanted '$native'"
    fi
  done <<'EOF'
app-arm64-v8a-release.apk|arm64-v8a
app-armeabi-v7a-release.apk|armeabi-v7a
app-x86_64-release.apk|x86_64
app-universal-release.apk|arm64-v8a, armeabi-v7a, x86_64
EOF
  if [[ -n $wrong ]]; then
    fail 'the native-code column follows each APK' "$wrong"
  else
    pass 'the native-code column follows each APK'
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
expect_refusal 'an APK with more than one signer is refused' \
  'has 2 signers, expected 1' "$work/two-signers" v9.9.9
expect_refusal 'an empty APK directory is refused' \
  'no APKs in' "$work/empty" v9.9.9

if ((failures)); then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
echo 'all release-note checks passed'
