#!/usr/bin/env bash
#
# Proves run-emulator-playback-test.sh reports the rounds it ran, and that the two checks
# in front of the emulator refuse the shapes they exist for — without an emulator.
#
# The runner cannot be run on this machine (no hypervisor, no device), so it is run with a
# `gradlew` that writes the report a round would leave behind and exits with the status a
# plan names, and an `adb` that prints what a device prints. What is checked is the part
# that decides what CI says afterwards: how many rounds ran, that each round's report is
# copied out before the next round overwrites it, that a round which leaves nothing behind
# is not counted as the previous round's green report, that a stopped round still collects
# its diagnostics, and that the first failure decides the job while the remaining rounds
# still run.
#
# The checks in front of the emulator are fed fixtures here too, one shape they must
# refuse and one they must accept, because a guard that stops firing is a green job that
# guards nothing: check-repeated-test.sh watches the test the rounds are for, and
# check-emulator-budget.sh compares the rounds with the job's own timeout.
#
# Usage: test-run-emulator-playback-test.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$root/.github/scripts/run-emulator-playback-test.sh"
watched_check="$root/.github/scripts/check-repeated-test.sh"
budget_check="$root/.github/scripts/check-emulator-budget.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
pass() { printf 'ok    %s\n' "$1"; }
fail() {
  printf 'FAIL  %s\n' "$1"
  [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/      /'
  failures=$((failures + 1))
}

# What a round leaves behind. The file name is the real one, spaces and parentheses
# included: a copy step that word-splits it copies nothing, which is how this shape first
# went wrong.
fixtures="$work/fixtures"
mkdir -p "$fixtures"

cat >"$fixtures/passing.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" tests="2" failures="0">
  <testcase name="everyEdgeReadsBack" classname="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" time="0.4" />
  <testcase name="thePickerInSettingsWritesTheStoredChoice" classname="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" time="1.2" />
</testsuite>
EOF

cat >"$fixtures/failing.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" tests="2" failures="1">
  <testcase name="everyEdgeReadsBack" classname="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" time="0.4" />
  <testcase name="thePickerInSettingsWritesTheStoredChoice" classname="com.nardech.vdoplayer.ui.settings.SettingsControlsPositionTest" time="5.1">
    <failure>tapping the chip did not store the edge: the store says BOTTOM</failure>
  </testcase>
</testsuite>
EOF

# A case is a directory the runner can be started in: a wrapper that follows a plan
# (`STUB_ROUND_N=pass|fail|none|hang`) and an adb that answers like a booted device.
new_case() {
  unit=$(mktemp -d "$work/case.XXXXXX")
  mkdir -p "$unit/bin"
  cat >"$unit/gradlew" <<'STUB'
#!/usr/bin/env bash
calls="$PWD/gradle-calls"
[ -f "$calls" ] || : >"$calls"
round=$(( $(wc -l <"$calls") + 1 ))
printf 'round %s\n' "$round" >>"$calls"
plan_var="STUB_ROUND_$round"
plan=${!plan_var:-pass}
report="app/build/outputs/androidTest-results/connected/debug/TEST-test(AVD) - 14.xml"
mkdir -p "$(dirname "$report")"
case $plan in
  pass) cp "$STUB_FIXTURES/passing.xml" "$report" ;;
  fail) cp "$STUB_FIXTURES/failing.xml" "$report" ;;
  none) ;;
  hang) exit 124 ;;
esac
if [ "$plan" = fail ]; then
  exit 1
fi
exit 0
STUB
  cat >"$unit/bin/adb" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$PWD/adb-calls"
if [ "$1" = logcat ]; then
  printf '%s\n' \
    'I/TestRunner: started: thePickerInSettingsWritesTheStoredChoice' \
    'I/VDO/Player: opened the clip' \
    'I/MediaCodec: c2.android.avc.decoder' \
    'I/Unrelated: noise the filter drops'
elif [ "$1" = shell ]; then
  case $2 in
    getprop) printf '%s\n' '[ro.build.fingerprint]: [google/sdk_gphone64_x86_64/test:14/UE1A/1:userdebug/dev-keys]' '[ro.product.cpu.abi]: [x86_64]' ;;
    dmesg) printf '%s\n' 'no oops' ;;
    dumpsys) printf '%s\n' 'MediaCodecList: avc' ;;
  esac
fi
exit 0
STUB
  chmod +x "$unit/gradlew" "$unit/bin/adb"
}

# Runs the real runner inside the case. The SDK variables are dropped so the script takes
# `adb` from the case's PATH, and the cap is five seconds so a message names a duration
# that is recognisably the one under test.
run_rounds() { # <case> <rounds> [STUB_ROUND_N=...]
  local dir=$1 rounds=$2
  shift 2
  local status=0
  output=$(cd "$dir" && env -u ANDROID_SDK_ROOT -u ANDROID_HOME \
    PATH="$dir/bin:$PATH" STUB_FIXTURES="$fixtures" \
    PLAYBACK_TEST_ROUNDS="$rounds" PLAYBACK_TEST_TIMEOUT=5s "$@" \
    bash "$script" 2>&1) || status=$?
  return $status
}

check_script() { # <script> [args...] — sets $output, returns the status
  local status=0
  output=$(bash "$@" 2>&1) || status=$?
  return $status
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

expect_file() { # <label> <path> [needle]
  if [[ ! -f $2 ]]; then
    fail "$1 — missing $2"
    return
  fi
  if [[ -n ${3:-} ]] && ! grep -qF -- "$3" "$2"; then
    fail "$1 — $2 does not contain '$3'" "$(cat "$2")"
    return
  fi
  pass "$1"
}

expect_no_file() { # <label> <path>
  if [[ -e $2 ]]; then
    fail "$1 — $2 should not exist" "$(cat "$2" 2>/dev/null || true)"
  else
    pass "$1"
  fi
}

expect_calls() { # <label> <calls-file> <count>
  local got=0
  [ -f "$2" ] && got=$(wc -l <"$2" | tr -d ' ')
  if [[ $got -eq $3 ]]; then
    pass "$1"
  else
    fail "$1 — expected $3 call(s), saw $got" "$(cat "$2" 2>/dev/null || true)"
  fi
}

expect_no_xml() { # <label> <directory>
  if [[ -n $(find "$2" -name '*.xml' -print -quit 2>/dev/null) ]]; then
    fail "$1 — $2 still has a report in it" "$(find "$2" -name '*.xml')"
  else
    pass "$1"
  fi
}

name='thePickerInSettingsWritesTheStoredChoice'

# --- the runner: three passing rounds ------------------------------------------------
printf '\n# run-emulator-playback-test.sh\n'

new_case
status=0
run_rounds "$unit" 3 STUB_ROUND_1=pass STUB_ROUND_2=pass STUB_ROUND_3=pass || status=$?
expect_status 'three passing rounds exit 0' 0 "$status"
expect_output 'every round is announced with its number' '=== instrumented round 3 of 3'
expect_output 'a round reports what it counted' \
  "=== round 2: 2 test(s), 0 failed; $name passed"
expect_calls 'the suite runs once per round' "$unit/gradle-calls" 3
expect_file 'the watched test is written down for the summary' \
  "$unit/emulator-diagnostics/repeated-test.txt" "$name"
for round in 1 2 3; do
  expect_file "round $round keeps its own copy of the report" \
    "$unit/emulator-diagnostics/round-$round/TEST-test(AVD) - 14.xml"
done
expect_no_file 'a green run collects no device diagnostics' "$unit/emulator-diagnostics/logcat.txt"

# --- the runner: a failing round in the middle ----------------------------------------
new_case
status=0
run_rounds "$unit" 3 STUB_ROUND_1=pass STUB_ROUND_2=fail STUB_ROUND_3=pass || status=$?
expect_status 'the first failure decides the job' 1 "$status"
expect_output 'the failing round says which test failed' \
  "=== round 2: 2 test(s), 1 failed; $name failed"
expect_output 'the earlier green report is still readable afterwards' \
  "=== round 1: 2 test(s), 0 failed; $name passed"
expect_output 'the failure is announced once' '=== a round failed (status 1)'
expect_output 'the round after the failure still runs' '=== instrumented round 3 of 3'
expect_calls 'gradle runs every round, not only up to the failure' "$unit/gradle-calls" 3
expect_file 'the whole logcat is kept' "$unit/emulator-diagnostics/logcat.txt"
expect_file 'the filtered logcat keeps the player lines' \
  "$unit/emulator-diagnostics/logcat-playback.txt" 'VDO/'
if grep -qF 'Unrelated' "$unit/emulator-diagnostics/logcat-playback.txt"; then
  fail 'the filtered logcat drops lines from other tags'
else
  pass 'the filtered logcat drops lines from other tags'
fi
expect_file 'the runner lines name what was running' \
  "$unit/emulator-diagnostics/test-runner.txt" "started: $name"
expect_file 'the device properties are kept' "$unit/emulator-diagnostics/getprop.txt" 'ro.build.fingerprint'
expect_file 'the decoder list is kept' "$unit/emulator-diagnostics/platform-codecs.txt" 'MediaCodecList'
expect_file 'dmesg is kept' "$unit/emulator-diagnostics/dmesg.txt"
expect_file 'adb was asked for the device log' "$unit/adb-calls" 'logcat -d -t 4000'

# --- the runner: a round that leaves nothing behind -----------------------------------
# The second round writes no report at all. The first round's copy must not stand in for
# it: the runner deletes gradle's output directory before each round, and without that a
# round that died early would be summarised as the previous round's green.
new_case
status=0
run_rounds "$unit" 2 STUB_ROUND_1=pass STUB_ROUND_2=none || status=$?
expect_status 'a round with nothing to report does not fail on its own' 0 "$status"
expect_output 'the empty round is reported as missing, not as passed' \
  "=== round 2: 0 test(s), 0 failed; $name missing"
expect_file 'the first round kept its copy' \
  "$unit/emulator-diagnostics/round-1/TEST-test(AVD) - 14.xml"
expect_no_xml 'the second round did not inherit the first one' \
  "$unit/emulator-diagnostics/round-2"

# --- the runner: a round the cap stopped ----------------------------------------------
new_case
status=0
run_rounds "$unit" 1 STUB_ROUND_1=hang || status=$?
expect_status 'a stopped round fails the job with the timeout status' 124 "$status"
expect_output 'the cap is named with the duration that fired' \
  '=== round 1 was still going after 5s and was stopped'
expect_output 'the stopped round still reports its status' '=== round 1: gradle exited with 124'
expect_output 'a stopped round is reported as missing' \
  "=== round 1: 0 test(s), 0 failed; $name missing"
expect_file 'a stopped round still collects the device log while it is alive' \
  "$unit/emulator-diagnostics/logcat.txt"
expect_no_file 'a single round does not invent a second' "$unit/emulator-diagnostics/round-2"

# --- check-repeated-test.sh -----------------------------------------------------------
printf '\n# check-repeated-test.sh\n'

mkdir -p "$work/watched/runner" "$work/watched/tests"
cat >"$work/watched/runner/run.sh" <<'EOF'
set -u
repeated_test="thePickerInSettingsWritesTheStoredChoice"
EOF
cat >"$work/watched/tests/SettingsControlsPositionTest.kt" <<'EOF'
class SettingsControlsPositionTest {
    @Test
    fun thePickerInSettingsWritesTheStoredChoice() {
        assertTrue(true)
    }
}
EOF

status=0
check_script "$watched_check" "$work/watched/runner/run.sh" "$work/watched/tests" || status=$?
expect_status 'a declared test is accepted' 0 "$status"
expect_output 'the check names the test and where it found it' \
  "the repeated run watches $name, declared at"

mkdir -p "$work/renamed/tests"
cat >"$work/renamed/tests/SettingsControlsPositionTest.kt" <<'EOF'
class SettingsControlsPositionTest {
    @Test
    fun thePickerInSettingsWritesTheChoice() {
        assertTrue(true)
    }
}
EOF
status=0
check_script "$watched_check" "$work/watched/runner/run.sh" "$work/renamed/tests" || status=$?
expect_status 'a renamed test is refused' 1 "$status"
expect_output 'the refusal says nothing declares the watched name' \
  "but no \`@Test\` in"

mkdir -p "$work/helper/tests"
cat >"$work/helper/tests/SettingsControlsPositionTest.kt" <<'EOF'
class SettingsControlsPositionTest {
    fun thePickerInSettingsWritesTheStoredChoice() {
        assertTrue(true)
    }
}
EOF
status=0
check_script "$watched_check" "$work/watched/runner/run.sh" "$work/helper/tests" || status=$?
expect_status 'a same-named helper without @Test is refused' 1 "$status"

cat >"$work/no-assignment.sh" <<'EOF'
set -u
repeated_test = "thePickerInSettingsWritesTheStoredChoice"
EOF
status=0
check_script "$watched_check" "$work/no-assignment.sh" "$work/watched/tests" || status=$?
expect_status 'a runner whose assignment cannot be read is refused' 1 "$status"
expect_output 'that refusal says what it reads' 'could not read'

status=0
check_script "$watched_check" || status=$?
expect_status 'the repository declares the watched test' 0 "$status"

# --- check-emulator-budget.sh ---------------------------------------------------------
printf '\n# check-emulator-budget.sh\n'

status=0
check_script "$budget_check" || status=$?
expect_status 'the repository fits its rounds inside the job' 0 "$status"
expect_output 'the worst case is printed with every part of it' '= 56m, job timeout 60m'

mkdir -p "$work/budget"

# The job's limit lowered: three rounds plus boot and setup no longer fit.
cat >"$work/budget/short-job.yml" <<'EOF'
jobs:
  playback-on-an-emulator:
    timeout-minutes: 40
    steps:
      - name: Run the playback smoke test
        with:
          emulator-boot-timeout: 900
  unit-tests:
    timeout-minutes: 60
EOF
status=0
check_script "$budget_check" "$script" "$work/budget/short-job.yml" || status=$?
expect_status 'a job too short for its rounds is refused' 1 "$status"
expect_output 'the refusal does the arithmetic' '= 56m > 40m'

# A fourth round, set on the job rather than as a default: the check has to measure what
# the job will actually run.
cat >"$work/budget/four-rounds.yml" <<'EOF'
jobs:
  playback-on-an-emulator:
    timeout-minutes: 60
    env:
      PLAYBACK_TEST_ROUNDS: 4
    steps:
      - name: Run the playback smoke test
        with:
          emulator-boot-timeout: 900
EOF
status=0
check_script "$budget_check" "$script" "$work/budget/four-rounds.yml" || status=$?
expect_status 'a fourth round on the job is refused' 1 "$status"
expect_output 'the refusal counts the rounds the job sets' '4 round(s) x 12m'
expect_output 'and says the worst case' '= 68m > 60m'

# A longer cap in the runner, with the job unchanged.
cat >"$work/budget/longer-cap.sh" <<'EOF'
playback_timeout="${PLAYBACK_TEST_TIMEOUT:-15m}"
rounds="${PLAYBACK_TEST_ROUNDS:-3}"
EOF
status=0
check_script "$budget_check" "$work/budget/longer-cap.sh" || status=$?
expect_status 'a longer per-round cap is refused' 1 "$status"
expect_output 'the refusal uses the cap from the runner' '3 round(s) x 15m'

# A workflow that no longer names the job: the check must say so instead of measuring
# nothing and passing.
cat >"$work/budget/no-job.yml" <<'EOF'
jobs:
  unit-tests:
    timeout-minutes: 60
EOF
status=0
check_script "$budget_check" "$script" "$work/budget/no-job.yml" || status=$?
expect_status 'a workflow without the job is refused' 1 "$status"
expect_output 'that refusal names the job it looked for' 'no timeout-minutes on the'

if ((failures)); then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
echo 'all emulator-run checks passed'
