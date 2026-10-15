package com.nardech.vdoplayer.logging

import android.content.Context
import androidx.media3.common.Player

/**
 * Installs a last line of defence: any uncaught exception is written to the log
 * file (with a device summary) before the process dies, so a crash on the user's
 * phone is still diagnosable from the exported log.
 */
object CrashGuard {

    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                LogBus.e(LogTag.CRASH, "FATAL uncaught exception on '${thread.name}'", error)
                LogBus.i(LogTag.CRASH, Diagnostics.summary(context, player = null))
                LogBus.log(LogLevel.ERROR, LogTag.CRASH, "Thread state: ${thread.state}")
                LogBus.flushBlocking(3_000)
            }
            previous?.uncaughtException(thread, error)
        }
        LogBus.d(LogTag.CRASH, "Crash guard installed")
    }

    /** Extra context attached when a crash happens during playback. */
    fun attachPlayerSnapshot(player: Player?) {
        if (player == null) return
        runCatching {
            LogBus.i(
                LogTag.CRASH,
                "Player at crash time: state=${player.playbackState} playing=${player.isPlaying} " +
                    "position=${player.currentPosition} mediaId=${player.currentMediaItem?.mediaId}",
            )
        }
    }
}
