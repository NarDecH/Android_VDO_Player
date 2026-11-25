#!/usr/bin/env bash
#
# Fails when an instrumented test polls for a stored setting on the main thread.
#
# `ComposeTestRule.waitUntil` runs its condition on the UI thread. A DataStore write
# started by a tap resumes on that same thread, so a condition that reads the store
# back blocks the write it is waiting for: the flow never emits, the wait runs out, and
# the test reports that the tap stored nothing while the feature works. 1.5.5 lost a CI
# run to exactly that — "tapping the chip did not store the edge" was read as a broken
# settings screen and was not one.
#
# The fix is `awaitStoredSettings` in
# app/src/androidTest/java/com/nardech/vdoplayer/testing/StoredSettingsWaits.kt: it reads
# the store on Dispatchers.IO in short slices and gives the test thread back to the
# composition in between, because the composition's scope — which a tap hands its write
# to — is driven from that thread. It still returns null when nothing matched inside the
# window, so a write that really did not happen still fails the test.
#
# So the rule is narrow and total: a file that reaches the real store (AppContainer,
# SettingsRepository, settingsDataStore, container.settings) may not use the waitUntil
# family at all. Waiting for a node in a file with no store in it stays fine, which is
# nearly every instrumented test here.
#
# Usage:
#   bash .github/scripts/check-instrumented-waits.sh [dir]   # default app/src/androidTest
#
# The self-test beside this file (test-check-instrumented-waits.sh) feeds the script one
# fixture per rule, so a rule that stops firing is a failing job rather than a quiet one.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
target=${1:-$root/app/src/androidTest}

if [[ ! -d $target ]]; then
  printf 'no instrumented test directory at %s\n' "$target" >&2
  exit 1
fi

# The wait family. `waitForIdle` and the main clock are deliberately absent: neither
# runs a condition of the test's own, so neither can hold the thread a write needs.
waits='\b(waitUntil|waitUntilExactlyOneExists|waitUntilAtLeastOneExists|waitUntilDoesNotExist|waitUntilNodeCount)[[:space:]]*[({]'
# The ways a test gets hold of the real, stored settings — as opposed to a settings
# value passed in as a parameter, which says nothing about where it came from.
store='\b(AppContainer|SettingsRepository|settingsDataStore)\b|container\.settings'

files=0
offenders=0

while IFS= read -r -d '' path; do
  files=$((files + 1))

  if ! grep -qE "$store" "$path"; then
    continue
  fi

  # An import names the function without calling it, and a commented-out line is not
  # code; both are skipped so the rule reports what the compiler would run.
  hits=$(grep -nE "$waits" "$path" | grep -vE '^[0-9]+:[[:space:]]*(//|import )' || true)
  if [[ -z $hits ]]; then
    continue
  fi

  while IFS= read -r hit; do
    printf '%s:%s: waits with compose.waitUntil on a test that reads the stored settings\n' \
      "${path#"$root"/}" "${hit%%:*}"
    offenders=$((offenders + 1))
  done <<<"$hits"
done < <(find "$target" -type f -name '*.kt' -print0)

if ((offenders)); then
  cat >&2 <<'EOF'

Wait for the stored value with the helper that keeps the composition running:

    val stored = compose.awaitStoredSettings(container.settings) {
        it.controlsEdge == ControlsEdge.LEFT
    }
    assertNotNull("tapping the chip did not store the edge", stored)

`awaitStoredSettings` lives in app/src/androidTest/java/com/nardech/vdoplayer/testing/.
A `waitUntil` runs its condition on the UI thread, which is where the composition runs
the write a tap handed it, so it blocks the write it waits for and makes a working
feature look broken. Waiting for a node is still fine in a file that does not read the
store.
EOF
  exit 1
fi

printf 'no instrumented test waits on the stored settings from the main thread (%d file(s) read)\n' \
  "$files"
