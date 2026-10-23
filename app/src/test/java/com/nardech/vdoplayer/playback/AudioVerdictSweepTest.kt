package com.nardech.vdoplayer.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sweep is the reason a badge can appear on a file that was never played, and it is
 * also the only thing in the app that opens many videos nobody asked to play. So both
 * halves are pinned here: every file gets one verdict, and each visit reads only the
 * files that still need one.
 */
class AudioVerdictSweepTest {

    private val decided = mutableMapOf<String, AudioVerdict>()
    private val reads = mutableListOf<String>()

    @Test
    fun `a file decided on an earlier visit is not read again`() {
        decided["a"] = AudioVerdict.UNDECODABLE

        val swept = sweep(
            keys = listOf("a"),
            readings = mapOf("a" to listOf("audio/vnd.dts.hd")),
        )

        assertEquals(0, swept)
        assertEquals("a settled library must cost no file opens", emptyList<String>(), reads)
    }

    @Test
    fun `a file the probe already read is not read again`() {
        val swept = sweep(
            keys = listOf("a"),
            remembered = mapOf("a" to listOf("audio/vnd.dts.hd")),
        )

        assertEquals(1, swept)
        assertEquals("the probe's answer is the same answer without the read", emptyList<String>(), reads)
        assertEquals(AudioVerdict.UNDECODABLE, decided["a"])
    }

    @Test
    fun `a file nothing can decode is marked`() {
        sweep(
            keys = listOf("a"),
            readings = mapOf("a" to listOf("audio/vnd.dts.hd")),
        )

        assertEquals(
            "this is the badge: a file the player would have warned about after the fact",
            AudioVerdict.UNDECODABLE,
            decided["a"],
        )
        assertEquals(listOf("a"), reads)
    }

    @Test
    fun `a playable file is decided too, so nobody asks about it again`() {
        sweep(
            keys = listOf("a"),
            readings = mapOf("a" to listOf("audio/mp4a-latm")),
        )

        assertEquals(AudioVerdict.PLAYABLE, decided["a"])
    }

    @Test
    fun `a file with no audio track is decided rather than re-read forever`() {
        sweep(
            keys = listOf("a"),
            readings = mapOf("a" to emptyList()),
        )

        assertEquals(AudioVerdict.NO_AUDIO, decided["a"])
    }

    @Test
    fun `a file that cannot be read is left undecided`() {
        sweep(
            keys = listOf("a"),
            readings = mapOf("a" to null),
        )

        assertNull(
            "a provider that refused must get another chance, not a permanent badge",
            decided["a"],
        )
    }

    @Test
    fun `a codec nobody can be asked about is left undecided`() {
        val swept = runBlocking {
            AudioVerdictSweep.sweep(
                keys = listOf("a"),
                known = { key -> decided[key] },
                remembered = { null },
                readMimes = { listOf("audio/ac3") },
                outlook = { AudioOutlook.UNKNOWN },
                onVerdict = { key, verdict -> decided[key] = verdict },
            )
        }

        assertEquals(0, swept)
        assertNull(decided["a"])
    }

    @Test
    fun `a visit stops at its budget`() {
        val keys = (1..5).map { number -> "file-$number" }
        val readings = keys.associateWith { listOf("audio/mp4a-latm") }

        val swept = sweep(keys = keys, budget = 2, readings = readings)

        assertEquals(2, swept)
        assertEquals(listOf("file-1", "file-2"), reads)
    }

    @Test
    fun `the next visit continues where this one stopped`() {
        val keys = (1..4).map { number -> "file-$number" }
        val readings = keys.associateWith { listOf("audio/vnd.dts.hd") }

        sweep(keys = keys, budget = 2, readings = readings)
        reads.clear()
        sweep(keys = keys, budget = 2, readings = readings)

        assertEquals(
            "the badge has to reach the whole library, however many visits that takes",
            listOf("file-3", "file-4"),
            reads,
        )
        assertEquals(setOf("file-1", "file-2", "file-3", "file-4"), decided.keys)
    }

    private fun sweep(
        keys: List<String>,
        budget: Int = AudioVerdictSweep.DEFAULT_BUDGET,
        remembered: Map<String, List<String>> = emptyMap(),
        readings: Map<String, List<String>?> = emptyMap(),
    ): Int = runBlocking {
        AudioVerdictSweep.sweep(
            keys = keys,
            budget = budget,
            known = { key -> decided[key] },
            remembered = { key -> remembered[key] },
            readMimes = { key ->
                reads += key
                readings[key]
            },
            outlook = { mimes ->
                when {
                    mimes.isEmpty() -> AudioOutlook.UNKNOWN
                    mimes.any { mime -> mime == DTS } -> AudioOutlook.UNDECODABLE
                    else -> AudioOutlook.PLAYABLE
                }
            },
            onVerdict = { key, verdict ->
                decided[key] = verdict
            },
        )
    }

    private companion object {
        const val DTS = "audio/vnd.dts.hd"
    }
}
