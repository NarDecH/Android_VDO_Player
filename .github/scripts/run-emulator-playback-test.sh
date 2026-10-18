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
# `adb` waits forever for a device that is not coming back, so `adb logcat` has to
# run inside this window. The JUnit XML does not: it stays on disk, which is why the
# workflow also prints it from a later step.
set -u

adb_bin="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/platform-tools/adb"
if [ ! -x "$adb_bin" ]; then
  adb_bin=adb
fi

status=0
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace || status=$?

if [ "$status" -ne 0 ]; then
  echo "=== gradle exited with $status"
  echo '=== player and test-runner lines from logcat'

  # `VDO/` is the app's own log tag, covering the player, the decoder manager and
  # NextLib's adapter; the rest is what the platform decoder and FFmpeg print while
  # they open a stream. Filtering first keeps the dump short enough to read.
  timeout 120 "$adb_bin" logcat -d -t 4000 |
    grep -E 'TestRunner|AndroidJUnitRunner|PlaybackSmokeTest|VDO/|ExoPlayer|MediaCodec|avcodec|NextLib' |
    tail -200 || true
fi

# The original status decides the job; a failure here must not look like a pass.
exit "$status"
