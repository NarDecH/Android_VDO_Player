#!/usr/bin/env bash
#
# Runs the instrumented playback test on the emulator that
# `reactivecircus/android-emulator-runner` just booted, and collects what a failure
# needs while the device is still alive.
#
# Why this is a script and not the `script:` block of the action: the action runs
# each line of that block as its own shell invocation. The first CI attempt put the
# `if [ "$status" -ne 0 ]` branch there, and the log shows exactly what happened —
# `[command]/usr/bin/sh -c if [ "$status" -ne 0 ]; then` followed by
# `Syntax error: end of file unexpected (expecting "fi")`. The gradle status was lost
# and the diagnostics never ran.
#
# Why the diagnostics live here: after the action finishes, the emulator is gone and
# `adb` waits forever for a device that is not coming back, so anything read from the
# device has to be read inside this window. They are written to files as well as
# printed, because the workflow uploads that directory as an artifact — the console
# only keeps an excerpt, and the line that explains a decoder problem is often the
# one the filter dropped.
#
# Why the suite runs more than once: one instrumented test in this project writes through
# DataStore and then waits for the write, which is a race against whatever else the
# emulator is doing, and it failed on one run in three while the feature worked. A single
# green run cannot tell "fixed" from "did not happen to lose the race this time", so the
# run repeats — three times by default, `PLAYBACK_TEST_ROUNDS` to change it — and every
# round's report is copied out before the next round overwrites it, with the round's wall
# clock and the device log as it stood then: logcat is a ring buffer, so the round that
# failed is the one whose lines the next round pushes out first.
# `emulator-diagnostics/round-N` is what the summary in ci.yml reads to say which round
# did what.
set -u

# Where the workflow looks for what a failure left behind (see ci.yml).
diagnostics_dir="emulator-diagnostics"

adb_bin="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/platform-tools/adb"
if [ ! -x "$adb_bin" ]; then
  adb_bin=adb
fi

# A test that waits for something that never happens does not fail, it stops. The v2
# Compose rules once left this step running for over half an hour where the same suite
# finishes in about two minutes, and the only way to read its log was to sit through it.
# The wall clock is capped here instead: `timeout` stops the run while the emulator is
# still up, so the diagnostics below still explain what it was doing.
#
# The cap is on the whole run rather than on one test, and that is deliberate: JUnit's
# `Timeout` rule runs its test on a second thread, which an instrumentation test cannot
# do, and the runner's own `timeout_msec` argument is deprecated. The started/finished
# lines collected below name the test that was running when the cap hit.
#
# The cap is per round: a round that hangs still fails the job in minutes, and a slow but
# working set of rounds is not cut off by the first one having used most of the budget.
playback_timeout="${PLAYBACK_TEST_TIMEOUT:-12m}"
rounds="${PLAYBACK_TEST_ROUNDS:-3}"

# The test this repetition exists for. Its name is reported per round, so "passed in
# every round" is something the run says out loud instead of something a reader has to
# infer.
#
# The name is written out as well as used: the summary step in ci.yml names the same test
# in its annotations, and it reads the name from this file rather than keeping a second
# copy of it. One name, one place to change when the guarded test is renamed.
repeated_test="thePickerInSettingsWritesTheStoredChoice"
results_dir="app/build/outputs/androidTest-results"

mkdir -p "$diagnostics_dir"
printf '%s\n' "$repeated_test" > "$diagnostics_dir/repeated-test.txt"

if command -v timeout >/dev/null 2>&1; then
  have_timeout=1
else
  have_timeout=0
fi

run_round() {
  gradle_status=0
  if [ "$have_timeout" -eq 1 ]; then
    timeout --signal=INT --kill-after=60s "$playback_timeout"       ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace || gradle_status=$?
  else
    # macOS has no coreutils `timeout` unless one is installed by hand, and a developer
    # running this by hand should get the run rather than an error about the cap.
    ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace || gradle_status=$?
  fi
}

snapshot_round() { # <round> — keeps what the next round is about to overwrite
  round=$1
  directory="$diagnostics_dir/round-$round"
  mkdir -p "$directory"
  # The report sits two directories down (`connected/debug/TEST-test(AVD) - 14.xml`) and
  # its name contains spaces and parentheses, so `find -print0` rather than a glob: a
  # one-level glob here silently copied nothing, which the stub test caught. `-exec cp … +`
  # cannot replace this loop — find wants `{}` immediately before the `+`, so the target
  # directory cannot follow it, and `cp -t` does not exist on macOS.
  find "$results_dir" -name 'TEST-*.xml' -print0 2>/dev/null |
    while IFS= read -r -d '' report; do
      cp "$report" "$directory/"
    done

  # The device log from the round that just ran, not from the end of the run: logcat keeps
  # a fixed number of lines, so round 3 has already pushed round 1's lines out by the time
  # the end-of-run block below reads the buffer — and a round that failed early is exactly
  # the one whose evidence has to survive the rounds after it.
  timeout 120 "$adb_bin" logcat -d -t 4000 > "$directory/logcat.txt" 2>&1 || true
  grep -E 'TestRunner: (started|finished|failed)' "$directory/logcat.txt" |
    tail -60 > "$directory/test-runner.txt" || true
}

report_round() { # <round> <seconds> — one line the log can be read from
  round=$1
  elapsed=$2
  directory="$diagnostics_dir/round-$round"
  total=0
  failed=0
  for report in "$directory"/*.xml; do
    [ -f "$report" ] || continue
    total=$((total + $(grep -c '<testcase' "$report" || true)))
    failed=$((failed + $(grep -c '<failure\|<error' "$report" || true)))
  done

  # A passing case in this report is a self-closing tag; a failing one is followed by its
  # `<failure>`, which is why the line's ending is what separates them.
  outcome=missing
  for report in "$directory"/*.xml; do
    [ -f "$report" ] || continue
    line=$(grep -m1 "name=\"$repeated_test\"" "$report" || true)
    [ -n "$line" ] || continue
    case "$line" in
      *'/>'*) outcome=passed ;;
      *) outcome=failed ;;
    esac
    break
  done

  # The wall clock is what the cap measures; the JUnit times counted above are the tests
  # alone, so a round killed at twelve minutes can have spent most of them on the install
  # and the runner starting, where no report looks.
  echo "=== round $round: $total test(s), $failed failed; $repeated_test $outcome; ${elapsed}s wall clock"
}

overall=0
round=1
while [ "$round" -le "$rounds" ]; do
  echo "=== instrumented round $round of $rounds"
  # Each round writes over the last one's report, so the old one goes first: a round that
  # dies before producing a report must not leave the previous round's green report behind
  # to be counted as if it were this round's.
  rm -rf "$results_dir" app/build/reports/androidTests
  # `SECONDS` is bash's own counter, read on either side of the call the cap wraps: the
  # report copy and the logcat snapshot that follow are diagnostics, not the round.
  round_started=$SECONDS
  run_round
  status=$gradle_status
  elapsed=$((SECONDS - round_started))

  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    echo "=== round $round was still going after $playback_timeout and was stopped"
  fi
  if [ "$status" -ne 0 ]; then
    echo "=== round $round: gradle exited with $status"
    # The first failure decides the job, but the loop keeps going: a second data point is
    # the reason this repeats, and a failing first round is exactly when it is wanted.
    [ "$overall" -eq 0 ] && overall=$status
  fi

  snapshot_round "$round"
  echo "$elapsed" > "$diagnostics_dir/round-$round/elapsed.txt"
  report_round "$round" "$elapsed"
  round=$((round + 1))
done

status=$overall
if [ "$status" -ne 0 ]; then
  # The directory was made before the first round, so the files below always have a home.
  echo "=== a round failed (status $status)"

  # `VDO/` is the app's own log tag, covering the player, the decoder manager and
  # NextLib's adapter; the rest is what the platform decoder and FFmpeg print while
  # they open a stream. The filtered copy keeps the console readable, the whole
  # buffer goes into the artifact.
  timeout 120 "$adb_bin" logcat -d -t 4000 > "$diagnostics_dir/logcat.txt" 2>&1 || true
  grep -E 'TestRunner|AndroidJUnitRunner|PlaybackSmokeTest|VDO/|ExoPlayer|MediaCodec|avcodec|NextLib' \
    "$diagnostics_dir/logcat.txt" > "$diagnostics_dir/logcat-playback.txt" || true
  echo '=== player and test-runner lines from logcat'
  tail -200 "$diagnostics_dir/logcat-playback.txt" || true

  # A run that was stopped has no stack trace — nothing failed on its own — so the
  # runner's own lines are what names the test it was sitting in.
  grep -E 'TestRunner: (started|finished|failed)' "$diagnostics_dir/logcat.txt" | tail -60     > "$diagnostics_dir/test-runner.txt" || true
  echo '=== the tests the runner started and finished'
  tail -20 "$diagnostics_dir/test-runner.txt" || true

  # A device that died mid-run says so here and nowhere else.
  timeout 60 "$adb_bin" shell dmesg > "$diagnostics_dir/dmesg.txt" 2>&1 || true

  # Which decoders this image actually has is the first question a decoding failure
  # asks, and `media.codec` answers it for the platform side. FFmpeg's own inventory
  # is in the logcat (the NextLib adapter prints it when it opens a stream).
  timeout 60 "$adb_bin" shell dumpsys media.codec > "$diagnostics_dir/platform-codecs.txt" 2>&1 || true

  echo '=== the image under test'
  timeout 60 "$adb_bin" shell getprop > "$diagnostics_dir/getprop.txt" 2>&1 || true
  grep -E '^\[(ro\.build\.fingerprint|ro\.product\.cpu\.abi|ro\.build\.version\.sdk|ro\.kernel\.qemu)' \
    "$diagnostics_dir/getprop.txt" || true
fi

# The original status decides the job; a failure here must not look like a pass.
exit "$status"
