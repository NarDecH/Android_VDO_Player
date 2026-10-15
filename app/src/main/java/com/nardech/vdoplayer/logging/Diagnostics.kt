package com.nardech.vdoplayer.logging

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.nardech.vdoplayer.BuildConfig
import java.util.Locale

/**
 * Snapshot of everything that usually decides whether a video plays: device,
 * OS, ABI, free space and the hardware codecs the SoC exposes.
 */
object Diagnostics {

    fun summary(context: Context, player: Player?): String = buildString {
        appendLine("=== VDO Player diagnostics ===")
        appendLine("App version      : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Build type       : ${BuildConfig.BUILD_TYPE}")
        appendLine("Package          : ${context.packageName}")
        appendLine("Device           : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Hardware / board : ${Build.HARDWARE} / ${Build.BOARD}")
        appendLine("Android          : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Security patch   : ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Supported ABIs   : ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Primary ABI      : ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        appendLine("Locale           : ${Locale.getDefault()}")
        appendLine("CPU cores        : ${Runtime.getRuntime().availableProcessors()}")

        val metrics = context.resources.displayMetrics
        appendLine("Display          : ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.density} density")
        appendLine("Refresh rate     : ${runCatching { (context.getSystemService(android.view.WindowManager::class.java))?.defaultDisplay?.refreshRate }.getOrNull() ?: 0f} Hz")

        val runtime = Runtime.getRuntime()
        appendLine(
            "Heap             : ${mb(runtime.totalMemory() - runtime.freeMemory())} used / " +
                "${mb(runtime.maxMemory())} max",
        )
        appendLine("App external dir : ${context.getExternalFilesDir(null)?.absolutePath ?: "unavailable"}")

        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            appendLine("Free internal    : ${stat.availableBytes / (1024 * 1024)} MB of ${stat.totalBytes / (1024 * 1024)} MB")
        }

        if (player != null) {
            appendLine()
            append(playerSummary(player))
        }
    }

    fun playerSummary(player: Player): String = buildString {
        appendLine("--- Player state ---")
        appendLine("Playback state   : ${stateName(player.playbackState)}")
        appendLine("Is playing       : ${player.isPlaying}")
        appendLine("Is loading       : ${player.isLoading}")
        appendLine("Speed            : ${player.playbackParameters.speed}x")
        appendLine("Position / dur   : ${player.currentPosition} / ${player.duration}")
        appendLine("Buffered         : ${player.bufferedPosition}")
        appendLine("Media id         : ${player.currentMediaItem?.mediaId ?: "-"}")
        appendLine("Media uri        : ${player.currentMediaItem?.localConfiguration?.uri ?: "-"}")
        appendLine("Playlist index   : ${player.currentMediaItemIndex} of ${player.mediaItemCount}")
        appendLine("Repeat / shuffle : ${player.repeatMode} / ${player.shuffleModeEnabled}")
        append(playerTracks(player.currentTracks))
    }

    fun playerTracks(tracks: Tracks): String = buildString {
        appendLine("--- Active tracks ---")
        if (tracks.isEmpty) {
            appendLine("(no tracks reported)")
        }
        tracks.groups.forEach { group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSelected(index)) continue
                appendLine(describe(group.getTrackFormat(index)))
            }
        }
    }

    fun describe(format: Format): String = buildString {
        append("${typeName(format)}: ")
        append(format.sampleMimeType ?: "unknown")
        format.width.takeIf { it > 0 }?.let { append(" ${it}x${format.height}") }
        format.frameRate.takeIf { it > 0 }?.let { append(", ${"%.2f".format(it)} fps") }
        format.bitrate.takeIf { it > 0 }?.let { append(", ${it / 1000} kbps") }
        format.channelCount.takeIf { it > 0 }?.let { append(", ${it} ch") }
        format.sampleRate.takeIf { it > 0 }?.let { append(", ${it} Hz") }
        format.codecs?.let { append(", profile '$it'") }
        format.label?.let { append(" — $it") }
    }

    /** Hardware and software decoders the device reports, grouped by MIME type. */
    fun codecInventory(): String = buildString {
        appendLine("=== MediaCodec inventory ===")
        val listed = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrElse { emptyArray() }

        if (listed.isEmpty()) {
            appendLine("(MediaCodecList unavailable)")
        }

        listed
            .filter { !it.isEncoder }
            .forEach { info ->
                val kinds = when {
                    info.isHardwareAccelerated -> "HW"
                    info.isSoftwareOnly -> "SW"
                    else -> "--"
                }
                appendLine("$kinds  ${info.name}")
                info.supportedTypes.forEach { type ->
                    val caps = runCatching { info.getCapabilitiesForType(type) }.getOrNull()
                    appendLine(
                        "     - $type" +
                            (caps?.let { profileSummary(it) } ?: ""),
                    )
                }
            }
    }

    private fun profileSummary(capabilities: MediaCodecInfo.CodecCapabilities): String =
        runCatching {
            val profiles = capabilities.profileLevels
                ?.take(6)
                ?.joinToString { "${it.profile}/${it.level}" }
                ?: "-"
            " profiles=$profiles"
        }.getOrDefault("")

    private fun typeName(format: Format): String =
        if (format.sampleMimeType?.startsWith("audio/") == true) "audio" else "video"

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun mb(bytes: Long): String = "${bytes / (1024 * 1024)} MB"
}
