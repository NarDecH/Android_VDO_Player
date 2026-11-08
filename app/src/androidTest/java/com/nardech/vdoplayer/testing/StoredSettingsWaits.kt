package com.nardech.vdoplayer.testing

import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** How long a stored write is given before the wait gives up and the test fails. */
const val STORED_SETTINGS_TIMEOUT_MS = 5_000L

/**
 * Waits for the stored settings to match [predicate], one thread away from the UI.
 *
 * A tap made through the composition starts a DataStore write that resumes on the main
 * thread. `ComposeTestRule.waitUntil` runs its condition on that same thread, so a
 * condition that reads the store back blocks the very write it is waiting for: the flow
 * never emits, the wait runs out, and the failure reads as though the tap stored
 * nothing — which is what 1.5.5's control-bar test was reporting while the feature
 * worked.
 *
 * This suspends on [Dispatchers.IO] instead, leaving the main thread free to finish the
 * write, and returns the settings once they match, or `null` when [timeoutMs] passes —
 * which the caller asserts on, so a write that really did not happen still fails.
 *
 * On the `junit4.v2` rules the tap's coroutine is queued on a `StandardTestDispatcher`, so
 * the caller has to let it run first: `compose.waitForIdle()` after the interaction and
 * before this call. Waiting here cannot do that itself — this function has no rule.
 */
fun SettingsRepository.awaitStored(
    timeoutMs: Long = STORED_SETTINGS_TIMEOUT_MS,
    predicate: (AppSettings) -> Boolean,
): AppSettings? = runBlocking(Dispatchers.IO) {
    withTimeoutOrNull(timeoutMs) { settings.first(predicate) }
}
