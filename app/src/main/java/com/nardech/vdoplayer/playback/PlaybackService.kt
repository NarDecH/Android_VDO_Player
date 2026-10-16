package com.nardech.vdoplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.DecoderPreference
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag

/**
 * Hosts the player so playback survives the UI: audio keeps going with the screen
 * off, and the media notification gives play/pause/seek without opening the app.
 *
 * The service also owns the decoder: switching between hardware and FFmpeg has to
 * happen on the player's application thread, on the very player NextLib's decoder
 * manager was attached to, so the UI asks for it over the session (see
 * [PlaybackCommands]) and this class applies it.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var engine: PlaybackEngine? = null

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        LogBus.install(this)
        LogBus.i(LogTag.PLAYER, "PlaybackService created")

        val preference = runCatching {
            DecoderPreference.fromName(
                getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DECODER, null),
            )
        }.getOrNull() ?: DecoderPreference.PLATFORM_FIRST

        val built = PlayerFactory.build(this, preference)
        engine = built

        // Media3 builds the playback notification itself; naming the channel here
        // keeps the notification in the app language instead of Media3's default.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.notification_channel_name)
                .build(),
        )

        // Tapping the playback notification should land back in the player screen.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.nardech.vdoplayer.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val callback = object : MediaSession.Callback {

            /**
             * The app's own decoder command is not part of the Media3 defaults, so
             * it has to be granted to every controller that connects.
             */
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val commands: SessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(PlaybackCommands.setDecoderPreference)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction != PlaybackCommands.ACTION_SET_DECODER) {
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
                val requested = DecoderPreference.fromName(args.getString(PlaybackCommands.KEY_DECODER))
                if (requested == null) {
                    LogBus.w(LogTag.DECODER, "Decoder switch asked for an unknown preference")
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                return Futures.immediateFuture(applyDecoderPreference(requested))
            }
        }

        mediaSession = MediaSession.Builder(this, built.player)
            .setSessionActivity(sessionActivity)
            .setCallback(callback)
            .build()
    }

    /**
     * Applies a decoder preference to the running player and remembers it for the
     * next time the service is created, because the service cannot read DataStore.
     */
    private fun applyDecoderPreference(preference: DecoderPreference): SessionResult {
        val applied = engine?.applyDecoderPreference(preference)
        if (applied == null) {
            return SessionResult(SessionError.ERROR_INVALID_STATE)
        }
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_DECODER, preference.name)
            .apply()
        LogBus.i(
            LogTag.DECODER,
            "Decoder switched live to '${preference.name}' — $applied",
        )
        return SessionResult(SessionResult.RESULT_SUCCESS)
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
        mediaSession?.release()
        mediaSession = null
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        const val PREFS = "vdo_player_runtime"
        const val KEY_DECODER = "decoder_preference"
        const val NOTIFICATION_CHANNEL_ID = "vdo_player_playback"
    }
}
