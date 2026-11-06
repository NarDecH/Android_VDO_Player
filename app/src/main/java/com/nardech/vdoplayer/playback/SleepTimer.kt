package com.nardech.vdoplayer.playback

/**
 * A sleep timer: stop playback after a stretch of time, or when the video playing now
 * ends.
 *
 * Kept as a value rather than a running job so the player can redraw from it and so the
 * rules below can be tested without a device: a timer that fires while the viewer is
 * asleep is worth getting right the first time.
 */
data class SleepTimer(
    val deadlineMs: Long? = null,
    val endOfVideo: Boolean = false,
) {
    val isActive: Boolean get() = deadlineMs != null || endOfVideo
}

/**
 * The rules behind the player's sleep timer, free of Compose and the player.
 *
 * A timed stop is a deadline; the end-of-video stop is told by the caller, because only
 * the player knows that the file ended or that the queue moved on.
 */
object SleepTimerPolicy {

    /** Durations the dialog offers, in minutes. */
    val MINUTES = listOf(10, 20, 30, 60)

    /** Marker for "when this video ends" among the duration choices. */
    const val END_OF_VIDEO = 0

    val OFF = SleepTimer()

    fun after(minutes: Int, nowMs: Long): SleepTimer =
        SleepTimer(deadlineMs = nowMs + minutes.coerceAtLeast(1).toLong() * 60_000L)

    fun untilEndOfVideo(): SleepTimer = SleepTimer(endOfVideo = true)

    /** Time left on a timed stop; `null` when the timer is off or waits for the end. */
    fun remainingMs(timer: SleepTimer, nowMs: Long): Long? =
        timer.deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }

    fun isDue(timer: SleepTimer, nowMs: Long): Boolean =
        timer.deadlineMs?.let { nowMs >= it } ?: false

    /** Whether playback should stop now. */
    fun shouldStop(timer: SleepTimer, nowMs: Long, videoEnded: Boolean): Boolean =
        isDue(timer, nowMs) || (timer.endOfVideo && videoEnded)
}
