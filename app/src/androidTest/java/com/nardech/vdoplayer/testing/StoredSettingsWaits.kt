package com.nardech.vdoplayer.testing

import android.os.SystemClock
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** How long a stored write is given before the wait gives up and the test fails. */
const val STORED_SETTINGS_TIMEOUT_MS = 5_000L

/**
 * How much of that window one read may hold the test thread. The read has to happen
 * somewhere, and only the test thread can hand the composition back its turn, so it is
 * held in slices and given back in between.
 */
private const val READ_SLICE_MS = 100L

/**
 * Waits for the stored settings to match [predicate] while the composition keeps running.
 *
 * A tap hands its write to the composition's own scope, and under a Compose test rule that
 * scope is driven from the test thread: whatever it has queued — the coroutine the tap
 * launched, and the rest of it after the store answers — only runs when the test returns to
 * the framework. A wait that parks this thread for its whole window therefore freezes the
 * write instead of watching it: the window expires with the store still not matching, and
 * the store holds the new value as soon as the wait lets the thread go. That is how the
 * settings chip failed three times on a loaded emulator while the feature worked, and how
 * round 2 of the 2026-11-24 run failed at exactly five seconds — in the same round this
 * repository committed three direct writes in 0.536s, so the store was never the slow part.
 *
 * A longer window cannot fix that shape of failure; it only moves the expiry. So the read
 * happens on [Dispatchers.IO] in slices of [READ_SLICE_MS], and between slices the test
 * thread gives the composition its turn through [ComposeTestRule.waitForIdle]. The window
 * itself is unchanged: `null` still means nothing matched within [timeoutMs], and the
 * caller fails the test on it.
 */
fun ComposeTestRule.awaitStoredSettings(
    repository: SettingsRepository,
    timeoutMs: Long = STORED_SETTINGS_TIMEOUT_MS,
    predicate: (AppSettings) -> Boolean,
): AppSettings? {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0L) return null
        val stored = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(minOf(READ_SLICE_MS, remaining)) {
                repository.settings.first(predicate)
            }
        }
        if (stored != null) return stored
        waitForIdle()
    }
}
