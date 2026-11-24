#!/usr/bin/env bash
#
# Fails when the emulator job's own timeout cannot cover the rounds it runs.
#
# Three numbers decide whether a failure is reported or the job is killed from outside:
# the cap on one round and the number of rounds (both in
# run-emulator-playback-test.sh, both overridable through the environment), and
# `timeout-minutes` on the job (in ci.yml). At three rounds of twelve minutes they fit
# inside 60 with a few minutes spare. A fourth round, or a longer cap, would not: the
# runner would be killed mid-round, and the diagnostics that explain a stall are collected
# by the script while the emulator is still up, so losing that window turns the next
# failure into a log with no explanation — which is the whole reason the cap exists.
#
# The budget here is the worst case, not the usual run: every round reaches its own cap,
# the boot takes as long as `emulator-boot-timeout` allows, and setup, the APK install and
# the artifact upload take `setup_minutes`. A run where the suite passes is far faster;
# this is about the run that does not.
#
# Usage:
#   bash .github/scripts/check-emulator-budget.sh [runner-script] [workflow]
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
runner=${1:-$root/.github/scripts/run-emulator-playback-test.sh}
workflow=${2:-$root/.github/workflows/ci.yml}
job=playback-on-an-emulator

# JDK and SDK setup, the KVM rule, gradle's configuration, installing the test APK on the
# device and uploading what a failure left behind. None of it is inside a round cap, so it
# is budgeted rather than assumed free.
setup_minutes=5

for file in "$runner" "$workflow"; do
  if [[ ! -f $file ]]; then
    printf 'missing %s\n' "$file" >&2
    exit 1
  fi
done

# The defaults the runner uses, read from the runner: a copy here would be a second copy to
# keep in step with it.
runner_rounds=$(sed -n 's/^rounds="\${PLAYBACK_TEST_ROUNDS:-\([0-9]*\)}"$/\1/p' "$runner" | head -1)
runner_cap=$(sed -n 's/^playback_timeout="\${PLAYBACK_TEST_TIMEOUT:-\([0-9]*\)m}"$/\1/p' "$runner" | head -1)

# A job that sets either variable is judged by what it sets, not by the default: otherwise
# the check would pass on three rounds while the job runs four.
workflow_rounds=$(tr -d '\r' <"$workflow" |
  awk -F: '/^[[:space:]]*PLAYBACK_TEST_ROUNDS:/ { value = $2; gsub(/[^0-9]/, "", value); print value; exit }')
workflow_cap=$(tr -d '\r' <"$workflow" |
  awk -F: '/^[[:space:]]*PLAYBACK_TEST_TIMEOUT:/ { value = $2; gsub(/[^0-9]/, "", value); print value; exit }')

rounds=${workflow_rounds:-$runner_rounds}
cap=${workflow_cap:-$runner_cap}

# The job's limit, and the boot inside it: `- name: playback on an emulator`/`timeout-minutes`
# and the action's own `emulator-boot-timeout` in seconds. Both are read from ci.yml so a
# job that grows a step or a longer boot is measured, not remembered.
job_timeout=$(tr -d '\r' <"$workflow" |
  awk -v job="  $job:" '
    $0 == job { inside = 1; next }
    inside && /^  [A-Za-z0-9_-]+:/ { exit }
    inside && /^    timeout-minutes:/ { value = $2; gsub(/[^0-9]/, "", value); print value; exit }
  ')
boot_seconds=$(tr -d '\r' <"$workflow" |
  awk '/^[[:space:]]*emulator-boot-timeout:/ { value = $2; gsub(/[^0-9]/, "", value); print value; exit }')

if [[ -z $job_timeout ]]; then
  printf 'no timeout-minutes on the `%s` job in %s\n' "$job" "${workflow#"$root"/}" >&2
  printf 'the job has to state its own limit for this check to mean anything\n' >&2
  exit 1
fi
if [[ -z $boot_seconds ]]; then
  printf 'no `emulator-boot-timeout` in %s\n' "${workflow#"$root"/}" >&2
  printf 'the boot is part of the budget; this check reads the action for it\n' >&2
  exit 1
fi
for pair in "rounds:$rounds" "the per-round cap:$cap"; do
  if [[ ! ${pair#*:} =~ ^[0-9]+$ ]]; then
    printf 'could not read %s from %s\n' "${pair%%:*}" "${runner#"$root"/}" >&2
    printf 'this check reads the `rounds=` and `playback_timeout=` assignments there\n' >&2
    exit 1
  fi
done

boot_minutes=$(((boot_seconds + 59) / 60))
total=$((boot_minutes + rounds * cap + setup_minutes))
printf 'emulator budget: %d round(s) x %dm + %dm boot + %dm setup = %dm, job timeout %dm\n' \
  "$rounds" "$cap" "$boot_minutes" "$setup_minutes" "$total" "$job_timeout"

if ((total > job_timeout)); then
  printf 'the worst case does not fit: %d round(s) x %dm + %dm boot + %dm setup = %dm > %dm\n' \
    "$rounds" "$cap" "$boot_minutes" "$setup_minutes" "$total" "$job_timeout" >&2
  printf 'a job killed by its own timeout stops no round gracefully, so the run that needed\n' >&2
  printf 'the diagnostics is the one that will not have them: lower the rounds or the cap in\n' >&2
  printf '%s, or raise timeout-minutes on `%s` in %s.\n' \
    "${runner#"$root"/}" "$job" "${workflow#"$root"/}" >&2
  exit 1
fi

printf 'the worst case leaves %d minute(s) of the job unused\n' "$((job_timeout - total))"
