package com.nardech.vdoplayer.playback

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the probe cache is worth, measured on a device instead of argued on the JVM.
 *
 * The claim the cache makes is one sentence — a file already probed is not opened
 * again — and `MediaExtractor` offers no way to observe an open from outside. So the
 * probe takes the read as a parameter, and these tests pass a reader that counts.
 * That is the only reason the parameter exists: the counting reader still goes through
 * the real files in `androidTest/assets`, and [theRealExtractorFindsTheAc3Track] keeps
 * the stand-in honest by asking the production reader for the same answer.
 *
 * The failures matter as much as the hit: a file with no audio, or one that could not
 * be read at all, must come back with a `null` and *no* remembered answer, because a
 * cached "no audio" would outlive the reason it was cached.
 */
@RunWith(AndroidJUnit4::class)
class AudioCodecProbeDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context: Context get() = instrumentation.targetContext

    /**
     * Every test starts from an empty cache: the probe's cache is one per process, and
     * a remembered answer from the previous test would turn its first read into a hit.
     */
    @Before
    fun forgetEarlierProbes() {
        AudioCodecProbe.forgetRemembered()
    }

    @Test
    fun theRealExtractorFindsTheAc3Track() {
        val ac3 = localFile(AC3_CLIP)
        assertEquals(
            "the default reader must be the real extractor, or the counts below prove nothing; " +
                "it said ${extractorReport(ac3.first)}",
            "AC3",
            AudioCodecProbe.probe(context, ac3.first, ac3.second),
        )

        val silent = localFile(VIDEO_ONLY_CLIP)
        assertNull(
            "a clip with no audio track has no codec to switch to",
            AudioCodecProbe.probe(context, silent.first, silent.second),
        )
    }

    @Test
    fun aFileAlreadyProbedIsNotOpenedAgain() {
        val (uri, key) = localFile(AC3_CLIP)
        val opens = AtomicInteger()
        val reader = { _: Context, _: Uri ->
            opens.incrementAndGet()
            listOf("video/avc", "audio/ac3")
        }

        assertEquals("AC3", AudioCodecProbe.probe(context, uri, key, reader))
        assertEquals("the first probe has to open the file", 1, opens.get())

        assertEquals(
            "the second play of the same file must answer from memory",
            "AC3",
            AudioCodecProbe.probe(context, uri, key, reader),
        )
        assertEquals(
            "the same file, unchanged, must not be opened a second time",
            1,
            opens.get(),
        )
    }

    /**
     * The library asks a cheaper question than the player: what is already known, without
     * touching the file. A miss has to stay a miss — the badge must never cost a header
     * read per row — and only a real probe may turn it into an answer.
     */
    @Test
    fun aRecallNeverOpensTheFile() {
        val (uri, key) = localFile(AC3_CLIP, copyName = "recall-ac3.mkv")
        val opens = AtomicInteger()
        val reader = { _: Context, _: Uri ->
            opens.incrementAndGet()
            listOf("video/avc", "audio/ac3")
        }

        assertNull(
            "an unprobed file has nothing to recall, and must not be opened to find out",
            AudioCodecProbe.recall(context, key),
        )
        assertEquals("recall is a question for the cache, not for the file", 0, opens.get())
        assertNull(
            "a file whose identity nobody knows can never be recalled",
            AudioCodecProbe.recall(context, key = null),
        )

        AudioCodecProbe.probeAudioMimes(context, uri, key, reader)

        assertEquals(
            "what a probe learned is what the library gets to mark",
            listOf("audio/ac3"),
            AudioCodecProbe.recall(context, key),
        )
        assertEquals("and recalling it costs no second open", 1, opens.get())
    }

    /**
     * The key is the file's identity only as long as it changes with the file. A size
     * that moved means different bytes, and different bytes deserve their own read.
     */
    @Test
    fun aFileWhoseBytesChangedIsReadAgain() {
        val (uri, key) = localFile(AC3_CLIP)
        val opens = AtomicInteger()
        val reader = { _: Context, _: Uri ->
            opens.incrementAndGet()
            listOf("audio/ac3")
        }

        AudioCodecProbe.probe(context, uri, key, reader)
        AudioCodecProbe.probe(context, uri, "$key|replaced", reader)

        assertEquals("a replaced file must not inherit the old answer", 2, opens.get())
    }

    @Test
    fun aFileWithoutAudioIsNotRemembered() {
        val (uri, key) = localFile(AC3_CLIP)
        val opens = AtomicInteger()
        val reader = { _: Context, _: Uri ->
            opens.incrementAndGet()
            listOf("video/avc")
        }

        assertNull(AudioCodecProbe.probe(context, uri, key, reader))
        assertNull(AudioCodecProbe.probe(context, uri, key, reader))

        assertEquals(
            "silence is not an answer worth remembering — the track list may not be final yet",
            2,
            opens.get(),
        )
    }

    /**
     * A file still being written is the realistic case: the first read fails, and the
     * cache must not turn that failure into a permanent silence.
     */
    @Test
    fun aReadThatFailedIsTriedAgain() {
        val (uri, key) = localFile(AC3_CLIP)
        val opens = AtomicInteger()
        val reader = { _: Context, _: Uri ->
            if (opens.incrementAndGet() == 1) throw IOException("still being written")
            listOf("audio/ac3")
        }

        assertNull(
            "an unreadable file must not fail playback",
            AudioCodecProbe.probe(context, uri, key, reader),
        )
        assertEquals(
            "and the next attempt must reach the file rather than a remembered failure",
            "AC3",
            AudioCodecProbe.probe(context, uri, key, reader),
        )
        assertEquals(2, opens.get())
    }

    /**
     * The store the probe attaches is a real `SharedPreferences` file, so what it writes
     * has to come back from a second instance — the JVM tests can only prove the rule,
     * not that this file name and key are the ones being read again.
     */
    @Test
    fun theStoreKeepsWhatWasWritten() {
        val store = PrefsAudioCodecStore(context)
        val before = store.load()
        // The value is the track's MIME type: a bare codec name is not a MIME type and the
        // format drops it, so writing one here would only prove the drop.
        val entry = "probe-test|1|2" to "audio/ac3"
        try {
            store.save(listOf(entry))
            assertEquals(
                "a codec written by one run must be readable by the next",
                "audio/ac3",
                PrefsAudioCodecStore(context).load().toMap()[entry.first],
            )
        } finally {
            store.save(before)
        }
    }

    /**
     * The verdicts are a second file with its own key, and the library draws its badges
     * from nothing else. A wrong name here would not fail anything — the library would
     * simply never seem to know a file — so the round-trip is the whole test.
     */
    @Test
    fun theVerdictsKeptOutliveTheProbeThatLearnedThem() {
        val store = PrefsAudioVerdictStore(context)
        val before = store.load()
        val entry = "probe-test|1|3" to AudioVerdict.UNDECODABLE
        try {
            store.save(mapOf(entry))
            assertEquals(
                "a badge decided by one run must be drawn by the next, without opening the file",
                AudioVerdict.UNDECODABLE,
                PrefsAudioVerdictStore(context).load()[entry.first],
            )
        } finally {
            store.save(before)
        }
    }

    /**
     * The preflight rule needs two lists no JVM has: the platform's decoders and NextLib's
     * FFmpeg build. Only a device can say whether the second one really names AC-3 — the
     * codec every rescue in this app exists for — and whether a codec nothing ships is
     * reported instead of being passed over.
     */
    @Test
    fun theDeviceCanBeAskedWhatItCannotDecode() {
        val (uri, key) = localFile(AC3_CLIP)
        assertEquals(
            "the probe must read what the file holds; the extractor said ${extractorReport(uri)}",
            listOf("audio/ac3"),
            AudioCodecProbe.probeAudioMimes(context, uri, key),
        )
        assertEquals(
            "AC-3 plays here, so a warning about it would be wrong",
            AudioOutlook.PLAYABLE,
            AudioPreflight.outlook(listOf("audio/ac3")),
        )
        assertEquals(
            "a codec no decoder names must be reported as one, not passed over",
            AudioOutlook.UNDECODABLE,
            AudioPreflight.outlook(listOf("audio/x-vdo-nothing-here")),
        )
    }

    /**
     * What the platform extractor reports for [uri], for the assertion messages above.
     *
     * A failed probe only says "nothing was read", and the two reasons for that — an open
     * that threw, a container whose tracks were not listed — need different fixes; the
     * report keeps the next failing run from having to guess which one it was.
     */
    private fun extractorReport(uri: Uri): String = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(requireNotNull(uri.path))
            val mimes = (0 until extractor.trackCount).map { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            }
            "${extractor.trackCount} track(s) $mimes"
        } finally {
            runCatching { extractor.release() }
        }
    }.getOrElse { "an extractor that threw $it" }

    /**
     * Copies a clip out of the test APK's assets and describes it the way the library
     * does: the URI, and the size/timestamp key built from what is on disk.
     */
    private fun localFile(name: String, copyName: String = name): Pair<Uri, String> {
        val target = File(context.cacheDir, "probe-$copyName")
        instrumentation.context.assets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val key = AudioCodecProbe.cacheKey(
            uri = target.absolutePath,
            sizeBytes = target.length(),
            changedAt = target.lastModified(),
        )
        return Uri.fromFile(target) to requireNotNull(key) { "a file on disk always has a size" }
    }

    private companion object {
        /** The clip whose audio is AC-3, the codec the rescue exists for. */
        const val AC3_CLIP = "ac3.mkv"

        /** The same picture without any audio track. */
        const val VIDEO_ONLY_CLIP = "smoke.mp4"
    }
}
