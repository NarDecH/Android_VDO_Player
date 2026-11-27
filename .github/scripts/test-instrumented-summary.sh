#!/usr/bin/env bash
#
# Proves the summary step in ci.yml raises its hand when a round has spent most of the cap
# it will be stopped at, and stays quiet when it has not.
#
# The summary is a python heredoc inside the workflow, so it cannot be sourced: this lifts
# it out of the `Summarise the instrumented run` step and runs it in a scratch directory
# holding what the runner leaves behind (`repeated-test.txt`, `round-N/TEST-*.xml` and
# `round-N/elapsed.txt`). The cap is read from the runner script, so the fixture copy of
# that script decides the numbers here — including one case with a shorter cap, because a
# share of twelve minutes hard-coded in the summary would pass every other case and still
# be wrong the day the cap changes.
#
# Usage: test-instrumented-summary.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
workflow="$root/.github/workflows/ci.yml"
runner="$root/.github/scripts/run-emulator-playback-test.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
pass() { printf 'ok    %s\n' "$1"; }
fail() {
  printf 'FAIL  %s\n' "$1"
  [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/      /'
  failures=$((failures + 1))
}
expect_status() { # <label> <expected> <actual>
  if [[ ${3:-} -eq $2 ]]; then
    pass "$1"
  else
    fail "$1 — expected exit $2, got ${3:-none}" "$output"
  fi
}
expect_output() { # <label> <needle>
  if [[ $output == *"$2"* ]]; then
    pass "$1"
  else
    fail "$1 — output did not contain '$2'" "$output"
  fi
}
expect_no_output() { # <label> <needle>
  if [[ $output == *"$2"* ]]; then
    fail "$1 — output should not contain '$2'" "$output"
  else
    pass "$1"
  fi
}

# Lift the heredoc out of its step: the step's name starts the copy, the closing `PY` at
# the heredoc's own indent ends it, and the ten spaces it is indented by come off so python
# can parse it. Another `python3 - <<'PY'` elsewhere in the workflow is why this starts at
# the step rather than at the first heredoc in the file.
summary="$work/summarise.py"
awk '
  /- name: Summarise the instrumented run/ { step = 1 }
  step && /python3 - <<.PY./ { copying = 1; next }
  copying && /^          PY$/ { exit }
  copying { print }
' "$workflow" | sed 's/^          //' >"$summary"

if [ ! -s "$summary" ]; then
  fail 'the summary step was not found in ci.yml'
  printf '\n%s failure(s)\n' "$failures"
  exit 1
fi

# `command -v python3` succeeds on a Windows install that only has the store stub, so the
# interpreter has to run something before it is trusted with the lifted step.
python_bin=
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then
    python_bin=$candidate
    break
  fi
done
if [ -z "$python_bin" ]; then
  fail 'no python interpreter can run the summary'
  printf '\n%s failure(s)\n' "$failures"
  exit 1
fi
if ! "$python_bin" -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$summary"; then
  fail 'the lifted summary is not valid python'
  printf '\n%s failure(s)\n' "$failures"
  exit 1
fi

# A scratch run: the diagnostics the runner writes, and a copy of the runner so the summary
# reads the cap from a file this test controls.
new_case() { # <name> [cap]
  unit="$work/$1"
  mkdir -p "$unit/.github/scripts" "$unit/emulator-diagnostics"
  if [ -n "${2:-}" ]; then
    sed "s/PLAYBACK_TEST_TIMEOUT:-12m/PLAYBACK_TEST_TIMEOUT:-$2/" "$runner" \
      >"$unit/.github/scripts/run-emulator-playback-test.sh"
  else
    cp "$runner" "$unit/.github/scripts/run-emulator-playback-test.sh"
  fi
  printf '%s\n' 'thePickerInSettingsWritesTheStoredChoice' \
    >"$unit/emulator-diagnostics/repeated-test.txt"
}

add_round() { # <case> <round> <seconds|none>
  local dir=$1 round=$2 seconds=$3
  mkdir -p "$dir/emulator-diagnostics/round-$round"
  cat >"$dir/emulator-diagnostics/round-$round/TEST-test(AVD) - 14.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" tests="2" failures="0">
  <testcase name="everyEdgeReadsBack" classname="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" time="0.4" />
  <testcase name="thePickerInSettingsWritesTheStoredChoice" classname="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" time="1.2" />
</testsuite>
XML
  if [ "$seconds" != none ]; then
    printf '%s\n' "$seconds" >"$dir/emulator-diagnostics/round-$round/elapsed.txt"
  fi
}

summarise() { # <case> — sets $output, returns the summary's status
  local status=0
  output=$(cd "$1" && "$python_bin" "$summary" 2>&1) || status=$?
  return $status
}

# --- the summary: rounds well inside the cap ------------------------------------------
printf '\n# ci.yml summary\n'

new_case 'quick'
add_round "$unit" 1 60
add_round "$unit" 2 120
add_round "$unit" 3 45
status=0
summarise "$unit" || status=$?
expect_status 'rounds inside the cap leave the job alone' 0 "$status"
expect_output 'every round is still announced' 'round 3 of 3: 2 instrumented test(s)'
expect_output 'the notice carries the wall clock against the cap' \
  'wall clock 2m 00.0s of the 12m round cap'
expect_no_output 'a quick round warns about nothing' '::warning title=Slow round::'

# --- a round most of the way to the cap -----------------------------------------------
new_case 'slow'
add_round "$unit" 1 600
status=0
summarise "$unit" || status=$?
expect_status 'a slow round is a warning, not a failure' 0 "$status"
expect_output 'a round past the share says so as a warning' \
  '::warning title=Slow round::round 1 of 1 used 10m 00.0s of its 12m cap, 83% of it'
expect_output 'the warning does not replace the notice' \
  '::notice title=Instrumented results::round 1 of 1: 2 instrumented test(s)'

# --- the share itself, and a second under it ------------------------------------------
new_case 'at-the-share'
add_round "$unit" 1 576
status=0
summarise "$unit" || status=$?
expect_output 'the share itself is close enough to warn' \
  'used 9m 36.0s of its 12m cap, 80% of it'

new_case 'under-the-share'
add_round "$unit" 1 575
status=0
summarise "$unit" || status=$?
expect_no_output 'a second under the share is not flagged' '::warning title=Slow round::'

# --- a round with no clock beside its report ------------------------------------------
new_case 'unclocked'
add_round "$unit" 1 none
status=0
summarise "$unit" || status=$?
expect_status 'a round with no clock does not fail the job' 0 "$status"
expect_no_output 'a round with no clock is not judged against the cap' 'wall clock'
expect_no_output 'and nothing warns about a time nobody measured' '::warning title=Slow round::'

# --- the cap the runner names, not a twelve-minute constant ---------------------------
new_case 'shorter-cap' 5m
add_round "$unit" 1 250
status=0
summarise "$unit" || status=$?
expect_output 'the share is taken from the runner, not from twelve minutes' \
  'used 4m 10.0s of its 5m cap, 83% of it'
expect_output 'the notice names the same cap' 'of the 5m round cap'

if [ "$failures" -ne 0 ]; then
  printf '\n%s failure(s)\n' "$failures"
  exit 1
fi
printf '\nall cases passed\n'
