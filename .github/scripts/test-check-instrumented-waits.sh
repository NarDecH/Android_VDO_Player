#!/usr/bin/env bash
#
# Proves check-instrumented-waits.sh refuses the shape it exists for.
#
# The guard is the only thing standing between a future test and another run that fails
# for a reason that reads like a broken feature, so it is tested like code: one fixture
# per rule, each in its own directory. A rule that stops firing — a regex that no longer
# matches the call as it is written, a filter that swallows the line — turns into a
# failing job here, on the commit that broke it, instead of a mystery timeout on an
# emulator eleven months later.
#
# The fixtures are three-line Kotlin files, so nothing needs compiling and the whole
# check costs a second.
#
# Usage: test-check-instrumented-waits.sh
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
script="$root/.github/scripts/check-instrumented-waits.sh"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

failures=0
pass() { printf 'ok    %s\n' "$1"; }
fail() {
  printf 'FAIL  %s\n' "$1"
  [ -n "${2:-}" ] && printf '%s\n' "$2" | sed 's/^/      /'
  failures=$((failures + 1))
}

# run <dir>; leaves the combined output in $output and returns the script's status.
run() {
  local status=0
  output=$(bash "$script" "$1" 2>&1) || status=$?
  return $status
}

expect_pass() { # <label> <dir>
  if run "$2"; then
    pass "$1"
    return 0
  fi
  fail "$1 — expected a pass, got a failure" "$output"
  return 1
}

expect_refusal() { # <label> <expected message> <dir>
  local want=$2
  if run "$3"; then
    fail "$1 — expected a failure, got exit 0" "$output"
    return 1
  fi
  if [[ $output != *"$want"* ]]; then
    fail "$1 — failed for the wrong reason, wanted '$want'" "$output"
    return 1
  fi
  pass "$1"
}

# The shape that cost 1.5.5 a CI run: a screen driven by the stored settings, polled
# from a waitUntil instead of awaited off the main thread.
mkdir -p "$work/polled"
cat >"$work/polled/SettingsEdgeTest.kt" <<'EOF'
class SettingsEdgeTest {
    private val container = AppContainer(context)

    @Test
    fun theChipStoresTheEdge() {
        compose.onNodeWithText("Left").performClick()
        compose.waitUntil(5_000) {
            container.settings.settings.value.controlsEdge == ControlsEdge.LEFT
        }
    }
}
EOF

# The same store reached through the repository directly, which is the other way a test
# gets at it: the rule keys on the store, not on how the file names it.
mkdir -p "$work/polled-repository"
cat >"$work/polled-repository/StoreTest.kt" <<'EOF'
class StoreTest {
    @Test
    fun waitsForTheWrite() {
        compose.waitUntil(timeoutMillis = 5_000) { repository.settings.first().edge == LEFT }
    }

    private val repository = SettingsRepository(context)
}
EOF

# The rest of the family has to be refused too, otherwise the ban is one function wide
# and `waitUntilNodeCount` walks straight past it.
mkdir -p "$work/polled-node-count"
cat >"$work/polled-node-count/CountTest.kt" <<'EOF'
class CountTest {
    private val container = AppContainer(context)

    @Test
    fun theRowsAppear() {
        compose.waitUntilNodeCount(hasText("Playback"), 2, 5_000)
    }
}
EOF

# Waiting for a node in a file with no store in it is what most tests do, and it stays
# allowed: there is no write for the condition to starve.
mkdir -p "$work/waiting-on-a-node"
cat >"$work/waiting-on-a-node/PlayerTextTest.kt" <<'EOF'
class PlayerTextTest {
    @Test
    fun theTitleAppears() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Test clip").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
EOF

# The sanctioned way, in the same kind of file as the first fixture: the helper is not
# a waitUntil, so the ban must not fire on it.
mkdir -p "$work/awaited"
cat >"$work/awaited/SettingsEdgeTest.kt" <<'EOF'
class SettingsEdgeTest {
    private val container = AppContainer(context)

    @Test
    fun theChipStoresTheEdge() {
        compose.onNodeWithText("Left").performSemanticsAction(SemanticsActions.OnClick)
        val stored = container.settings.awaitStored { it.controlsEdge == ControlsEdge.LEFT }
        assertNotNull(stored)
    }
}
EOF

# Idle waits run no condition of the test's own, so they cannot hold the thread a write
# needs; a store-backed file that uses one is fine.
mkdir -p "$work/waited-for-idle"
cat >"$work/waited-for-idle/SettingsEdgeTest.kt" <<'EOF'
class SettingsEdgeTest {
    private val container = AppContainer(context)

    @Test
    fun theScreenSettles() {
        compose.waitForIdle()
        assertEquals(ControlsEdge.LEFT, runBlocking { container.settings.settings.first() }.controlsEdge)
    }
}
EOF

# A commented-out wait is not a call. The filter that drops comments is easy to lose in
# a rewrite of the guard, and losing it turns every explanation of this rule into a
# failure.
mkdir -p "$work/commented"
cat >"$work/commented/SettingsEdgeTest.kt" <<'EOF'
class SettingsEdgeTest {
    private val container = AppContainer(context)

    @Test
    fun theChipStoresTheEdge() {
        // Not this: compose.waitUntil(5_000) { container.settings.first().edge == LEFT }
        val stored = container.settings.awaitStored { it.controlsEdge == ControlsEdge.LEFT }
        assertNotNull(stored)
    }
}
EOF

# Nothing to read is a pass that says so, not a crash.
mkdir -p "$work/empty"

want='waits with compose.waitUntil on a test that reads the stored settings'

expect_refusal 'a store-backed waitUntil is refused' "$want" "$work/polled"
expect_refusal 'the store is recognised through the repository' "$want" \
  "$work/polled-repository"
expect_refusal 'the rest of the wait family is refused too' "$want" "$work/polled-node-count"
expect_pass 'waiting for a node without the store passes' "$work/waiting-on-a-node"
expect_pass 'awaitStored in a store-backed file passes' "$work/awaited"
expect_pass 'waitForIdle in a store-backed file passes' "$work/waited-for-idle"
expect_pass 'a commented-out wait passes' "$work/commented"

if expect_pass 'a directory with no tests passes' "$work/empty"; then
  if [[ $output != *'0 file(s) read'* ]]; then
    fail 'a directory with no tests reports how many it read' "$output"
  fi
fi

# The repository's own tests are the case this exists for, so they are run too: a
# fixture suite that passes while the real tree fails would be worse than none.
if run "$root/app/src/androidTest"; then
  pass "the repository's instrumented tests pass"
else
  fail "the repository's instrumented tests pass" "$output"
fi

if ((failures)); then
  printf '\n%d check(s) failed\n' "$failures" >&2
  exit 1
fi
echo 'all instrumented-wait checks passed'
