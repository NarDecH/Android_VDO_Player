package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The persisted map is one string in `SharedPreferences`, so the format is the whole
 * contract of [PrefsAudioCodecStore]. Nothing here touches Android: a line that cannot
 * be read back is a miss, and a miss only costs the header read, but a line that is
 * *misread* would remember the wrong codec for a file.
 */
class AudioCodecStoreFormatTest {

    @Test
    fun `what was written is read back`() {
        val entries = listOf(
            // One track, and a file with two — the comma is part of the value, not a separator.
            "content://media/external/video/media/42|41000|1700000000" to "audio/ac3",
            "content://media/external/video/media/43|900|1700000100" to
                "audio/vnd.dts,audio/mp4a-latm",
        )

        assertEquals(entries, AudioCodecStoreFormat.decode(AudioCodecStoreFormat.encode(entries)))
    }

    @Test
    fun `a key keeps its own punctuation`() {
        // The key is a URI plus two numbers: pipes, colons and slashes are normal in it,
        // and splitting on anything but the tab would cut the key in half.
        val key = "content://media/external/video/media/42?volumeName=primary:0|41000|1700000000"
        val decoded =
            AudioCodecStoreFormat.decode(AudioCodecStoreFormat.encode(listOf(key to "audio/eac3")))

        assertEquals(listOf(key to "audio/eac3"), decoded)
    }

    @Test
    fun `an empty store reads as nothing remembered`() {
        assertEquals(emptyList<Pair<String, String>>(), AudioCodecStoreFormat.decode(null))
        assertEquals(emptyList<Pair<String, String>>(), AudioCodecStoreFormat.decode(""))
    }

    @Test
    fun `a line that does not split is dropped, not guessed at`() {
        val raw = listOf(
            "no separator at all",
            "\tcodec-without-a-key",
            "key-without-a-codec\t",
            "good|key|1\taudio/ac3",
        ).joinToString("\n")

        assertEquals(
            "only the line that names both a key and a MIME type survives",
            listOf("good|key|1" to "audio/ac3"),
            AudioCodecStoreFormat.decode(raw),
        )
    }

    @Test
    fun `a name from the version that kept only names is dropped`() {
        // The value used to be the short codec name. It cannot answer whether a decoder
        // exists, so keeping it would mean either a wrong verdict or a file that never
        // gets one; dropping it costs exactly one header read.
        val raw = "good|key|1\tAC3\nanother|key|2\taudio/ac3"

        assertEquals(
            listOf("another|key|2" to "audio/ac3"),
            AudioCodecStoreFormat.decode(raw),
        )
    }
}
