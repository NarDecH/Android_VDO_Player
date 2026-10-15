package com.nardech.vdoplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nardech.vdoplayer.data.DecoderPreference
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag

/**
 * Hosts the player so playback survives the UI: audio keeps going with the screen
 * off, and the media notification gives play/pause/seek without opening the app.
 *
 * The decoder preference is fixed for the lifetime of the process, because
 * changing it means rebuilding the renderers, which Media3 does not allow on a
 * live player instance.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        LogBus.install(this)
        LogBus.i(LogTag.PLAYER, "PlaybackService created")

        val preference = runCatching {
            DecoderPreference.valueOf(
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_DECODER, DecoderPreference.PLATFORM_FIRST.name)
                    ?: DecoderPreference.PLATFORM_FIRST.name,
            )
        }.getOrDefault(DecoderPreference.PLATFORM_FIRST)

        val exoPlayer = PlayerFactory.build(this, preference)
        player = exoPlayer

        // Tapping the playback notification should land back in the player screen.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.nardech.vdoplayer.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Called when the user swipes the app away from recents. Playback stops with
     * the task unless the user asked for background audio, in which case the
     * service is simply left running.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = mediaSession?.player
        if (currentPlayer == null || !currentPlayer.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        LogBus.i(LogTag.PLAYER, "PlaybackService destroyed")
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        const val PREFS = "vdo_player_runtime"
        const val KEY_DECODER = "decoder_preference"
    }
}
