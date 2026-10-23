package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The verdict index is one string in `SharedPreferences`, so the format is its whole
 * contract. None of this touches Android: a line that cannot be read back costs one
 * header read, but a line that is *misread* would mark a playable file as silent.
 */
class AudioVerdictFormatTest {

    @Test
    fun `what was written is read back`() {
        val verdicts = linkedMapOf(
            "content://media/external/video/media/42|41000|1700000000" to AudioVerdict.UNDECODABLE,
            "content://media/external/video/media/43|900|1700000100" to AudioVerdict.PLAYABLE,
            "content://media/external/video/media/44|900|1700000200" to AudioVerdict.NO_AUDIO,
        )

        assertEquals(verdicts, AudioVerdictFormat.decode(AudioVerdictFormat.encode(verdicts)))
    }

    @Test
    fun `a key keeps its own punctuation`() {
        // The key is a URI plus two numbers, so pipes, colons and slashes are normal in
        // it; splitting on anything but the tab would cut the key in half.
        val key = "content://media/external/video/media/42?volumeName=primary:0|41000|1700000000"

        assertEquals(
            mapOf(key to AudioVerdict.UNDECODABLE),
            AudioVerdictFormat.decode(
                AudioVerdictFormat.encode(mapOf(key to AudioVerdict.UNDECODABLE)),
            ),
        )
    }

    @Test
    fun `an empty file reads as nothing decided`() {
        assertEquals(emptyMap<String, AudioVerdict>(), AudioVerdictFormat.decode(null))
        assertEquals(emptyMap<String, AudioVerdict>(), AudioVerdictFormat.decode(""))
    }

    @Test
    fun `a line that does not split is dropped, not guessed at`() {
        val raw = listOf(
            "no separator at all",
            "\tundecodable-without-a-key",
            "key-without-a-verdict\t",
            "good|key|1\tu",
        ).joinToString("\n")

        assertEquals(
            "only the line that names both a key and a verdict survives",
            mapOf("good|key|1" to AudioVerdict.UNDECODABLE),
            AudioVerdictFormat.decode(raw),
        )
    }

    @Test
    fun `a flag this build does not know is dropped`() {
        val raw = "old|key|1\tX\nnewer|key|2\tu"

        assertEquals(
            "an unknown flag is not a verdict, and guessing one would mark a playable file",
            mapOf("newer|key|2" to AudioVerdict.UNDECODABLE),
            AudioVerdictFormat.decode(raw),
        )
    }
}
