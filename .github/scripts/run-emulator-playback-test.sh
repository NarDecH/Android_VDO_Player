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

status=0
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace || status=$?

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
