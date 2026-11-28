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
# A second shape does the same thing without a test rule: a `runBlocking` (or
# `runBlockingTest`) whose block parks the test thread until the store holds the value it
# waits for. The composition's scope — which a tap hands its write to — is driven from
# that thread, so the write cannot resume until the block returns: the window expires and
# the store is right a moment later. Only the waits that need a value are refused
# (`first { … }`, `first(predicate)`, `filter`, `collect`, a `delay` poll). A block that
# writes, or that reads `first()` — the value stored now, with nothing to wait for — is
# ordinary setup and stays allowed, as does one dispatched to another thread, which is the
# shape `awaitStoredSettings` itself uses.
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
# The suspending calls that wait for a value, as opposed to reading the one stored now:
# `first()` returns what is there, `first { … }` waits for it to match.
stored_wait='(first[[:space:]]*[({][[:space:]]*[^)[:space:]]|(filter|collect|delay)[[:space:]]*[({])'

files=0
offenders=0
polled=0
blocked=0

while IFS= read -r -d '' path; do
  files=$((files + 1))

  if ! grep -qE "$store" "$path"; then
    continue
  fi

  # An import names the function without calling it, and a commented-out line is not
  # code; both are skipped so the rule reports what the compiler would run.
  hits=$(grep -nE "$waits" "$path" | grep -vE '^[0-9]+:[[:space:]]*(//|import )' || true)
  while IFS= read -r hit; do
    [[ -n $hit ]] || continue
    printf '%s:%s: waits with compose.waitUntil on a test that reads the stored settings\n' \
      "${path#"$root"/}" "${hit%%:*}"
    polled=1
    offenders=$((offenders + 1))
  done <<<"$hits"

  # The second shape, one record per call: `<line> <tab> <off the test thread?> <tab>
  # <the block on one line>`. Braces are counted rather than pattern-matched, so a
  # multi-line block and a lambda inside it are read as one call; comments are dropped
  # first, so the explanation of this rule does not trip it.
  blocks=$(awk '
    {
      text = $0
      sub(/^[ \t]*\/\/.*$/, "", text)
      sub(/[ \t]\/\/.*$/, "", text)
      end = length(text)
      i = 1
      while (i <= end) {
        if (!open) {
          if (substr(text, i, 11) == "runBlocking") {
            j = i + 11
            if (substr(text, j, 4) == "Test") j += 4
            while (j <= end && substr(text, j, 1) ~ /[ \t]/) j++
            following = substr(text, j, 1)
            if (following == "(" || following == "{") {
              open = 1; saw = 0; parens = 0; braces = 0
              head = ""; body = ""; started = NR
              i = j
              continue
            }
          }
          i++
          continue
        }

        char = substr(text, i, 1)
        if (!saw) {
          if (char == "(") parens++
          else if (char == ")") parens--
          else if (char == "{" && parens <= 0) { saw = 1; braces = 1; i++; continue }
          head = head char
          i++
          continue
        }

        if (char == "{") braces++
        else if (char == "}") {
          braces--
          if (braces == 0) {
            print started "\t" (head ~ /Dispatchers\./ ? 1 : 0) "\t" body
            open = 0; saw = 0; head = ""; body = ""
            i++
            continue
          }
        }
        body = body char
        i++
      }
      if (open && saw) body = body " "
    }
  ' "$path")

  while IFS=$'\t' read -r started dispatched body; do
    [[ -n $started ]] || continue
    # Handed to another thread — `runBlocking(Dispatchers.IO) { … }`, the shape the
    # helper itself uses — it cannot starve the thread the composition runs on.
    [[ $dispatched == 1 ]] && continue
    # A block that does not reach the store has nothing to wait for, and a block that
    # writes or reads `first()` gives the composition its turn inside the window.
    printf '%s\n' "$body" | grep -qE "$store" || continue
    printf '%s\n' "$body" | grep -qE "$stored_wait" || continue
    printf '%s:%s: waits inside runBlocking for a value the stored settings write\n' \
      "${path#"$root"/}" "$started"
    blocked=1
    offenders=$((offenders + 1))
  done <<<"$blocks"
done < <(find "$target" -type f -name '*.kt' -print0)

if ((offenders)); then
  cat >&2 <<'EOF'

Wait for the stored value with the helper that keeps the composition running:

    val stored = compose.awaitStoredSettings(container.settings) {
        it.controlsEdge == ControlsEdge.LEFT
    }
    assertNotNull("tapping the chip did not store the edge", stored)

`awaitStoredSettings` lives in app/src/androidTest/java/com/nardech/vdoplayer/testing/.
EOF
  if ((polled)); then
    cat >&2 <<'EOF'

A `waitUntil` runs its condition on the UI thread, which is where the composition runs
the write a tap handed it, so it blocks the write it waits for and makes a working
feature look broken. Waiting for a node is still fine in a file that does not read the
store.
EOF
  fi
  if ((blocked)); then
    cat >&2 <<'EOF'

A `runBlocking` that waits parks the test thread the composition is driven from, so it
holds the same write for the whole window. Read the current value with `first()` and let
the helper above do the waiting, or hand the block to another thread —
`runBlocking(Dispatchers.IO) { … }` — which is what the helper itself does.
EOF
  fi
  exit 1
fi

printf 'no instrumented test waits on the stored settings from the test thread (%d file(s) read)\n' \
  "$files"
