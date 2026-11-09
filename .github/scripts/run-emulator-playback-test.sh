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
playback_timeout="${PLAYBACK_TEST_TIMEOUT:-12m}"
status=0
if command -v timeout >/dev/null 2>&1; then
  timeout --signal=INT --kill-after=60s "$playback_timeout"     ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace || status=$?
else
  # macOS has no coreutils `timeout` unless one is installed by hand, and a developer
  # running this by hand should get the run rather than an error about the cap.
  ./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace || status=$?
fi

if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
  echo "=== the run was still going after $playback_timeout and was stopped"
fi

if [ "$status" -ne 0 ]; then
  mkdir -p "$diagnostics_dir"
  echo "=== gradle exited with $status"

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
