#!/usr/bin/env bash
#
# Proves install-gitleaks.sh treats a broken download the way it claims to.
#
# Both workflows run that installer before anything else, and every branch in it is a
# branch CI only reaches when the release host misbehaves — the one moment nobody wants
# to be reading the script for the first time. `curl` is stubbed so the answers are
# scripted: a 410 and a 404 that must not be retried, a 503 and a connection that never
# happened, which must be, a 302 that must be followed to the CDN, and a 200 that must
# go straight on to the checksum file.
#
# `sleep` is stubbed too, and that is the point of the arrangement: the shipped backoff
# is asserted exactly (5, 10, 15, 20) without the test waiting a minute for it, so
# shortening the pauses in the installer to make CI faster would fail here rather than
# quietly change what a developer sees. Nothing here touches the network.
#
# Usage: test-install-gitleaks.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$root/.github/scripts/install-gitleaks.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
pass() { printf 'ok    %s\n' "$1"; }
fail() {
  printf 'FAIL  %s\n' "$1"
  [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/      /'
  failures=$((failures + 1))
}

stub="$work/stub"
mkdir -p "$stub"

# Answers with the next scripted status. The last line repeats, so a scenario can say
# "503 forever" in one line and let the installer exhaust its own limit.
cat >"$stub/curl" <<'STUB'
#!/usr/bin/env bash
out=''
url=''
follow=0
while [ $# -gt 0 ]; do
  case "$1" in
    -w) shift 2 ;;
    -o | -sSLo | --output)
      # -L is what reaches a release asset at all, since the host redirects to its CDN,
      # so it is recorded rather than assumed: a call without it is answered the way curl
      # answers one.
      case "$1" in *L*) follow=1 ;; esac
      out=$2
      shift 2
      ;;
    -*) case "$1" in *L*) follow=1 ;; esac; shift ;;
    *) url=$1; shift ;;
  esac
done
# A parsing mistake in this stub would otherwise look like an installer failure, so it
# is loud instead: the arguments this repo's call uses are the ones handled above.
if [ -z "$url" ] || [ -z "$out" ]; then
  echo "stub curl: could not read the url and output file from: $*" >&2
  exit 99
fi

# Hands out the next scripted status; the last line repeats, so one entry means "forever".
next_code() {
  n=$(cat "$STUB_INDEX" 2>/dev/null || printf 0)
  code=$(sed -n "$((n + 1))p" "$STUB_CODES")
  if [ -z "$code" ]; then
    code=$(tail -n 1 "$STUB_CODES")
  fi
  printf '%s\n' "$((n + 1))" >"$STUB_INDEX"
  printf '%s' "$code"
}

printf '%s\n' "$url" >>"$STUB_LOG"
code=$(next_code)
# GitHub does not serve a release asset itself: it answers 302 and points at its CDN, and
# real curl follows that inside the one call and reports the last hop. Only a call that
# carried -L follows it, which is what makes the redirect case about the install rather
# than about this stub.
if [ "$code" != 000 ] && [ "${code#3}" != "$code" ] && [ "$follow" = 1 ]; then
  printf '%s\n' "https://objects.example.invalid/asset/$(basename "$url")" >>"$STUB_LOG"
  code=$(next_code)
fi

# Real curl writes the body before reporting the status, so the file exists even on a
# failure; the installer must not trust it, and the checksum stage is what catches it.
printf 'stub body\n' >"$out"
if [ "$code" = 000 ]; then
  # A connection that never happened: nothing on stdout, and curl's own failure code.
  exit 7
fi
printf '%s' "$code"
STUB

cat >"$stub/sleep" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_SLEEP_LOG"
STUB

chmod +x "$stub/curl" "$stub/sleep"

# run <name> <codes...>; leaves the installer's $status and $output, plus $calls (the
# urls curl was asked for) and $pauses (the sleeps the installer asked for).
run() {
  local name=$1
  shift
  export STUB_CODES="$work/$name.codes" STUB_INDEX="$work/$name.index"
  export STUB_LOG="$work/$name.calls" STUB_SLEEP_LOG="$work/$name.sleeps"
  printf '%s\n' "$@" >"$STUB_CODES"
  : >"$STUB_LOG"
  : >"$STUB_SLEEP_LOG"
  rm -f "$STUB_INDEX"

  status=0
  output=$(PATH="$stub:$PATH" bash "$script" "$work/$name.bin" 2>&1) || status=$?
  calls=$(wc -l <"$STUB_LOG" | tr -d ' ')
  pauses=$(tr '\n' ' ' <"$STUB_SLEEP_LOG")
  pauses=${pauses% }
}

expect_status() { # <label> <want>
  if [ "$status" -eq "$2" ]; then
    pass "$1"
  else
    fail "$1 — exited $status, wanted $2" "$output"
  fi
}

expect_failure() { # <label>
  if [ "$status" -ne 0 ]; then
    pass "$1"
  else
    fail "$1 — exited 0" "$output"
  fi
}

expect_text() { # <label> <substring>
  case $output in
    *"$2"*) pass "$1" ;;
    *) fail "$1 — output never said '$2'" "$output" ;;
  esac
}

expect_no_text() { # <label> <substring>
  case $output in
    *"$2"*) fail "$1 — output said '$2'" "$output" ;;
    *) pass "$1" ;;
  esac
}

expect_calls() { # <label> <count>
  if [ "$calls" -eq "$2" ]; then
    pass "$1"
  else
    fail "$1 — curl was asked $calls time(s), wanted $2" \
      "$(cat "$STUB_LOG" 2>/dev/null)"
  fi
}

expect_pauses() { # <label> <space separated seconds>
  if [ "$pauses" = "$2" ]; then
    pass "$1"
  else
    fail "$1 — waited '$pauses', wanted '$2'" "$output"
  fi
}

expect_nothing_installed() { # <label> <dir>
  if [ -e "$2" ]; then
    fail "$1 — installed into $2 anyway"
  else
    pass "$1"
  fi
}

# A pinned release that was deleted. The asset will be just as missing on the second
# attempt, so the attempt must not be made: 1.5.x spent a minute of the job's time here
# and reported "could not download" instead of naming the release.
run gone 410
expect_status 'a 410 fails the install' 1
expect_text 'a 410 names the missing asset' 'is gone (HTTP 410); the pinned release no longer publishes it'
expect_no_text 'a 410 is not retried' 'retrying'
expect_calls 'a 410 is asked for once' 1
expect_pauses 'a 410 waits for nothing' ''
expect_nothing_installed 'a 410 installs nothing' "$work/gone.bin"

# The same branch by its other status.
run missing 404
expect_status 'a 404 fails the install' 1
expect_text 'a 404 names the missing asset' 'is gone (HTTP 404)'
expect_calls 'a 404 is asked for once' 1
expect_pauses 'a 404 waits for nothing' ''
expect_nothing_installed 'a 404 installs nothing' "$work/missing.bin"

# A busy release host: worth retrying, but the pauses are the shipped ones and the last
# status is what a reader needs to tell "gone" from "GitHub had a bad minute".
run busy 503
expect_status 'a 5xx eventually fails the install' 1
expect_calls 'a 5xx is tried five times' 5
expect_pauses 'the pauses widen on a 5xx' '5 10 15 20'
expect_text 'a 5xx reports how many attempts and the last status' \
  'after 5 attempts (last HTTP 503)'
expect_nothing_installed 'a 5xx installs nothing' "$work/busy.bin"

# No connection at all: curl prints no status and exits non-zero, so the installer has to
# supply a code of its own or the message loses the only clue about what happened.
run offline 000
expect_status 'a dead connection eventually fails the install' 1
expect_calls 'a dead connection is tried five times' 5
expect_pauses 'the pauses widen on a dead connection' '5 10 15 20'
expect_text 'a dead connection is reported as 000, not as success' \
  'after 5 attempts (last HTTP 000)'
expect_nothing_installed 'a dead connection installs nothing' "$work/offline.bin"

# Host recovered on the next attempt: the retry has to hand control back to the normal
# path, which then asks for the checksum file. Three calls, one pause, and a stop.
run recovered 503 200
expect_status 'a recovered host still fails at the checksum stage' 1
expect_calls 'a recovered download moves on to the checksum file' 3
expect_pauses 'a recovery waits once, then stops' '5'
case "$(tail -n 1 "$STUB_LOG")" in
  *gitleaks_*_checksums.txt) pass 'the third request is the checksum file' ;;
  *) fail 'the third request is the checksum file' "$(cat "$STUB_LOG")" ;;
esac
expect_no_text 'a recovery is not reported as a missing asset' 'is gone'

# The normal day: GitHub answers a release asset with a 302 to its CDN, so the install
# has to follow it inside the same call. If it stopped asking curl to do that, a working
# download would look like a busy host: four pauses, then "last HTTP 302".
run redirected 302 200
expect_status 'a redirected download still fails at the checksum stage' 1
expect_calls 'a redirect is followed, not retried' 3
expect_pauses 'a redirect waits for nothing' ''
expect_no_text 'a redirect is not retried' 'retrying'
expect_no_text 'a redirect is not reported as a busy host' 'last HTTP 302'
case "$(sed -n 2p "$STUB_LOG")" in
  *objects.example.invalid*) pass 'the second request goes to the redirected host' ;;
  *) fail 'the second request goes to the redirected host' "$(cat "$STUB_LOG")" ;;
esac

# A good answer: two requests (asset, checksums), no pause, and the empty stub checksum
# file is what stops it — which is the stage that exists so a swapped asset cannot be
# installed.
run immediate 200
expect_status 'a good answer still fails on an unverified checksum' 1
expect_calls 'a good answer asks for the asset and the checksum file' 2
expect_pauses 'a good answer waits for nothing' ''
expect_nothing_installed 'an unverified download installs nothing' "$work/immediate.bin"

if [ ! -f "$script" ]; then
  fail "the installer exists at $script"
fi

if ((failures)); then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
echo 'all gitleaks installer checks passed'
