package com.nardech.vdoplayer.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nardech.vdoplayer.data.DecoderPreference
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.Locale

/**
 * The player and the decoder selector that belongs to it.
 *
 * Both have to be created together: NextLib's [DecoderManager] only accepts a
 * player whose renderers came from a [NextRenderersFactory] that was handed the
 * same manager, and it has to be attached before the first media item is
 * prepared. Keeping them in one object makes that pairing impossible to break.
 */
class PlaybackEngine(
    val player: ExoPlayer,
    private val decoderManager: DecoderManager,
) {

    /**
     * Switches decoding while the player keeps running. Returns the modes now in
     * effect for the log, or `null` when NextLib refused the change.
     */
    fun applyDecoderPreference(preference: DecoderPreference): String? = runCatching {
        decoderManager.selectVideoDecoder(preference.videoMode)
        decoderManager.selectAudioDecoder(preference.audioMode)
        "video=${decoderManager.activeVideoMode} audio=${decoderManager.activeAudioMode}"
    }.onFailure {
        LogBus.e(LogTag.DECODER, "NextLib refused the decoder switch to '${preference.name}'", it)
    }.getOrNull()

    /** Detaches the decoder manager first: it holds a listener on the player. */
    fun release() {
        runCatching { decoderManager.detach() }
            .onFailure { LogBus.w(LogTag.DECODER, "Detaching the decoder manager failed", it) }
        player.release()
    }
}

/**
 * Builds the single ExoPlayer instance used across the app.
 *
 * The renderers factory is NextLib's, which adds FFmpeg software decoders on top
 * of the platform ones. That is what makes containers and codecs the SoC decoder
 * refuses (odd AC-3/E-AC-3 audio, VP9/AV1 on old chips, exotic MKV audio) play
 * anyway. The priority is user controlled in Settings, and can be changed while
 * playback runs through the decoder manager.
 */
object PlayerFactory {

    fun build(context: Context, decoderPreference: DecoderPreference): PlaybackEngine {
        LogBus.i(
            LogTag.PLAYER,
            "Creating ExoPlayer with decoder preference '${decoderPreference.name}' " +
                "(extension renderer mode ${decoderPreference.extensionMode}, " +
                "video mode ${decoderPreference.videoMode}, " +
                "audio mode ${decoderPreference.audioMode}) and " +
                "${Runtime.getRuntime().availableProcessors()} cores",
        )

        val decoderManager = DecoderManager(
            initialVideoMode = decoderPreference.videoMode,
            initialAudioMode = decoderPreference.audioMode,
        )

        // setDecoderManager() is declared on NextRenderersFactory and returns it, while
        // setExtensionRendererMode() is inherited from Media3 and returns the base
        // factory, so the NextLib call has to come first.
        val renderersFactory = NextRenderersFactory(context)
            .setDecoderManager(decoderManager)
            .setExtensionRendererMode(decoderPreference.extensionMode)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(Locale.getDefault().language)
                    .setPreferredTextLanguage(Locale.getDefault().language)
                    .setSelectUndeterminedTextLanguage(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true),
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .build()

        val player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        // NextLib requires this before the first item is prepared, otherwise the
        // runtime switch has nothing to drive.
        decoderManager.attach(player)
        LogBus.i(
            LogTag.DECODER,
            "Decoder manager attached (video ${decoderManager.videoMode}, " +
                "audio ${decoderManager.audioMode})",
        )

        return PlaybackEngine(player, decoderManager)
    }
}
