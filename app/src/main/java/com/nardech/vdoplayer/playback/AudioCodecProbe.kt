package com.nardech.vdoplayer.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.nardech.vdoplayer.data.VideoItem
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag

/**
 * The identity [this] file's probe result is remembered under, or `null` for a file whose
 * size nobody knows yet — see [AudioCodecProbe.cacheKey].
 *
 * Shared by everything that asks the cache about an item rather than about a URI: the
 * player deciding a decoder, and the library deciding a badge. One spelling of a file's
 * identity, or the two would remember the same file under two keys.
 */
fun VideoItem.probeKey(): String? = AudioCodecProbe.cacheKey(
    uri = uri.toString(),
    sizeBytes = sizeBytes,
    changedAt = dateAddedSeconds,
)

/**
 * Reads a file's audio codec before the player has opened it.
 *
 * The remembered-codec rescue used to wait for evidence: the player had to go silent,
 * the UI had to notice, and only then could the audio decoder change — a stretch of
 * picture without sound. Knowing the codec up front lets the audio decoder be picked
 * *before* the first item is prepared, so the first frame already has its sound.
 *
 * Only the track list is read: no packet, no decode. Call [probe] off the main thread.
 * Anything unexpected — an unreadable container, a file without audio — comes back as
 * `null`, which leaves playback on the old path where the no-sound card and the live
 * rescue still work.
 */
object AudioCodecProbe {

    /** Results already read, so the same file is opened once and not on every play. */
    private val cache = AudioCodecCache()

    /**
     * The MIME type of the first audio track in [mimeTypes], or `null` when there is
     * none.
     *
     * The first audio track is the one Media3's default selectors take when no language
     * preference decides otherwise. A file whose first audio track is not the one that
     * would play can only cost a needless FFmpeg audio decoder, never the sound itself.
     */
    fun firstAudioMime(mimeTypes: List<String?>): String? =
        mimeTypes.firstOrNull { mime -> isAudio(mime) }

    /** Tracks of other kinds are not audio, and a shy track that names nothing is not either. */
    internal fun isAudio(mime: String?): Boolean = mime?.startsWith("audio/") == true

    /**
     * The key a file's probe result is remembered under, or `null` when the file's
     * identity cannot be trusted and probing has to stand on its own.
     *
     * Size is what a re-downloaded file usually changes; the timestamp catches an edit
     * that kept the length. A file whose size nobody knows yet — an "open with" intent
     * before MediaStore has scanned it — gets no key rather than a guess, because a
     * stale answer would pick the wrong decoder for a file that is perfectly playable.
     */
    fun cacheKey(uri: String, sizeBytes: Long, changedAt: Long): String? =
        if (sizeBytes <= 0) null else "$uri|$sizeBytes|$changedAt"

    /**
     * The MIME types of every audio track in [uri], in track order, empty when it has no
     * readable audio. This is the answer [AudioPreflight] needs, and the one the cache
     * keeps: every track, because a file with one undecodable track can still have sound,
     * and the warning must be about the file rather than about one line of its header.
     *
     * A [key] from [cacheKey] lets a file already read skip the extractor entirely. Only
     * hits are remembered: a file that cannot be read right now (still being written, a
     * provider that refused) must get another chance instead of a cached "no audio".
     *
     * [readTrackMimes] is how the file is opened, and is a parameter only so a device
     * test can count those opens — the count is the whole claim the cache makes, and
     * `MediaExtractor` gives no way to observe it from outside. Production takes the
     * default, which reads the real container.
     */
    fun probeAudioMimes(
        context: Context,
        uri: Uri,
        key: String? = null,
        readTrackMimes: (Context, Uri) -> List<String?> = MediaExtractorTracks::read,
    ): List<String> =
        probeAudioMimesOrNull(context, uri, key, readTrackMimes).orEmpty()

    /**
     * [probeAudioMimes] with the failure told apart from a file that simply has no audio:
     * `null` is a file that could not be read, an empty list a file with no audio track.
     *
     * The library's badge sweep needs that difference — an unreadable file must be tried
     * again, while a silent film is a settled answer — and an empty list alone cannot
     * carry it, so the failure has to be visible to the caller.
     */
    fun probeAudioMimesOrNull(
        context: Context,
        uri: Uri,
        key: String? = null,
        readTrackMimes: (Context, Uri) -> List<String?> = MediaExtractorTracks::read,
    ): List<String>? {
        keepResults(context)
        return resolveAudioMimesOrNull(key, describe = "'$uri'") { readTrackMimes(context, uri) }
    }

    /**
     * What an earlier probe learned about a file, answered without opening it.
     *
     * A miss returns `null`, and unlike [probeAudioMimes] nothing is read to find out: this
     * is the question the library asks about every row it draws, where opening a header per
     * file would spend the viewer's battery and time to decorate a list. So the answer only
     * ever holds what this device has already learned by playing or probing a file — a file
     * nobody has looked at yet is simply unknown, which is the honest thing to draw.
     */
    fun recall(context: Context, key: String?): List<String>? {
        keepResults(context)
        return key?.let { identity -> cache.get(identity) }?.split(MIME_SEPARATOR)
    }

    /**
     * The MIME type of [uri]'s first audio track, or `null` when it has no readable
     * audio.
     *
     * The first track is the one Media3's default selectors take, so it is also the one
     * a rescue has to name.
     */
    fun probeMime(
        context: Context,
        uri: Uri,
        key: String? = null,
        readTrackMimes: (Context, Uri) -> List<String?> = MediaExtractorTracks::read,
    ): String? = probeAudioMimes(context, uri, key, readTrackMimes).firstOrNull()

    /**
     * The codec key ([AudioSilence.codecKey]) of [uri]'s first audio track, or `null`
     * when it has no readable audio.
     *
     * The name is derived from the MIME type rather than cached on its own, so a file and
     * its decoder question share one entry: [probeAudioMimes] remembers the MIME types
     * and this reads the name off the first, and the two can never disagree.
     */
    fun probe(
        context: Context,
        uri: Uri,
        key: String? = null,
        readTrackMimes: (Context, Uri) -> List<String?> = MediaExtractorTracks::read,
    ): String? = AudioSilence.codecKey(probeMime(context, uri, key, readTrackMimes))

    /**
     * Makes the first probe of a process read what the last one learned.
     *
     * The store needs a `Context` and this is an object, so it is attached here, on the
     * caller's thread — which is already a background thread, because probing a header
     * is. Every later call sees the store and does nothing.
     */
    private fun keepResults(context: Context) {
        if (cache.isPersistent()) return
        cache.attach(PrefsAudioCodecStore(context))
    }

    /**
     * The rule [probeAudioMimes] applies, with Android left out of it: a remembered
     * answer wins, a read that fails is not remembered, and only real tracks are.
     *
     * Split out so the rule is tested where CI runs it — on the JVM, in every build —
     * instead of only on a device that has to be booted first. [probeAudioMimes] is the
     * same rule with the real extractor behind it.
     */
    internal fun resolveAudioMimes(
        key: String?,
        describe: String,
        read: () -> List<String?>,
    ): List<String> = resolveAudioMimesOrNull(key, describe, read).orEmpty()

    /**
     * [resolveAudioMimes] with an unreadable file told apart from a silent one: `null`
     * when the header could not be read, the audio MIME types otherwise, empty when there
     * is no audio track.
     */
    internal fun resolveAudioMimesOrNull(
        key: String?,
        describe: String,
        read: () -> List<String?>,
    ): List<String>? {
        key?.let { identity ->
            cache.get(identity)?.let { remembered ->
                LogBus.d(LogTag.DECODER, "Cached $describe: audio mimes=$remembered")
                return remembered.split(MIME_SEPARATOR)
            }
        }
        val mimes = try {
            read()
        } catch (e: Exception) {
            // A probe is an optimisation, so a file this cannot read must not fail
            // playback: throw the answer away and let the player go silent as before.
            LogBus.w(LogTag.DECODER, "Could not read the audio codec of $describe", e)
            return null
        }
        val audio = mimes.filterNotNull().filter { mime -> isAudio(mime) }
        if (key != null && audio.isNotEmpty()) {
            cache.put(key, audio.joinToString(MIME_SEPARATOR))
        }
        LogBus.d(
            LogTag.DECODER,
            "Probed $describe: audio mimes=$audio codec=${AudioSilence.codecKey(audio.firstOrNull())}",
        )
        return audio
    }

    /** [resolveAudioMimes] with the first track's codec name, which is what a rescue names. */
    internal fun resolveMime(key: String?, describe: String, read: () -> List<String?>): String? =
        resolveAudioMimes(key, describe, read).firstOrNull()

    /** The same, one step further: the name playback remembers a rescue under. */
    internal fun resolve(key: String?, describe: String, read: () -> List<String?>): String? =
        AudioSilence.codecKey(resolveMime(key, describe, read))

    /** Drops what earlier probes remembered; tests start from a known empty cache. */
    internal fun forgetRemembered() = cache.clear()

    /** Between the audio MIME types of one file in a cache entry; a MIME type has no comma. */
    private const val MIME_SEPARATOR = ","
}

/**
 * The single place a file is actually opened, and the reason [AudioCodecProbe.probe]'s
 * default argument exists: a test that counts opens needs the production read to be one
 * named thing it can stand in for, not extractor calls scattered through the probe.
 *
 * The extractor is released on every path, including a `setDataSource` that throws —
 * a file the app cannot read must not leave a descriptor behind.
 */
private object MediaExtractorTracks {

    fun read(context: Context, uri: Uri): List<String?> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            (0 until extractor.trackCount).map { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            }
        } finally {
            runCatching { extractor.release() }
        }
    }
}
