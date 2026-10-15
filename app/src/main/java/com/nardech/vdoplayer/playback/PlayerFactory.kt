package com.nardech.vdoplayer.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.nardech.vdoplayer.data.DecoderPreference
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import java.util.Locale

/**
 * Builds the single ExoPlayer instance used across the app.
 *
 * The renderers factory is NextLib's, which adds FFmpeg software decoders on top
 * of the platform ones. That is what makes containers and codecs the SoC decoder
 * refuses (odd AC-3/E-AC-3 audio, VP9/AV1 on old chips, exotic MKV audio) play
 * anyway. The priority is user controlled in Settings.
 */
object PlayerFactory {

    fun build(context: Context, decoderPreference: DecoderPreference): ExoPlayer {
        LogBus.i(
            LogTag.PLAYER,
            "Creating ExoPlayer with decoder preference '${decoderPreference.name}' " +
                "(extension renderer mode ${decoderPreference.extensionMode}) and " +
                "${Runtime.getRuntime().availableProcessors()} cores",
        )

        val renderersFactory = NextRenderersFactory(context)
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

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }
}
