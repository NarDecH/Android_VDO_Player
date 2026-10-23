package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The verdict index exists to outlive the probe cache: a badge must not disappear because
 * the player happened to open eight other files since. Its rules are pinned here — one
 * bit per file, least recently used evicted, written only when a visit ends, and read back
 * on the next launch.
 */
class AudioVerdictIndexTest {

    @Test
    fun `a file nobody looked at has no verdict`() {
        assertNull(AudioVerdictIndex().get("file-a"))
    }

    @Test
    fun `a decided file keeps its verdict`() {
        val index = AudioVerdictIndex()
        index.put("file-a", AudioVerdict.UNDECODABLE)

        assertEquals(AudioVerdict.UNDECODABLE, index.get("file-a"))
    }

    @Test
    fun `the map stays bounded`() {
        val index = AudioVerdictIndex(capacity = 3)
        repeat(10) { number -> index.put("file-$number", AudioVerdict.PLAYABLE) }

        assertEquals(3, index.size())
        assertEquals(
            "the file just decided must be the one that survives",
            AudioVerdict.PLAYABLE,
            index.get("file-9"),
        )
        assertNull("the oldest verdict must have been dropped", index.get("file-0"))
    }

    @Test
    fun `a file still on screen is the last to go`() {
        val index = AudioVerdictIndex(capacity = 2)
        index.put("first", AudioVerdict.UNDECODABLE)
        index.put("second", AudioVerdict.PLAYABLE)

        // Asking about "first" is what the library does on every draw, and it must not
        // make the file it is drawing the next one evicted.
        index.get("first")
        index.put("third", AudioVerdict.PLAYABLE)

        assertEquals(AudioVerdict.UNDECODABLE, index.get("first"))
        assertNull(index.get("second"))
    }

    @Test
    fun `a verdict learned in one run is known in the next`() {
        val store = FakeVerdictStore()
        AudioVerdictIndex(store = store).apply {
            put("file-a", AudioVerdict.UNDECODABLE)
            save()
        }

        assertEquals(
            "a badge that only lasts one launch would be re-earned by reading the file again",
            AudioVerdict.UNDECODABLE,
            AudioVerdictIndex(store = store).get("file-a"),
        )
    }

    @Test
    fun `deciding without saving writes nothing`() {
        val store = FakeVerdictStore()
        val index = AudioVerdictIndex(store = store)

        index.put("file-a", AudioVerdict.PLAYABLE)

        assertEquals(
            "a sweep decides a hundred files in a row; one write per file is a hundred rewrites",
            0,
            store.writes,
        )
        assertEquals(AudioVerdict.PLAYABLE, index.get("file-a"))
    }

    @Test
    fun `a store attached later loads what the last run decided`() {
        val store = FakeVerdictStore()
        AudioVerdictIndex(store = store).apply {
            put("file-a", AudioVerdict.UNDECODABLE)
            save()
        }

        val index = AudioVerdictIndex()
        index.attach(store)

        assertEquals(AudioVerdict.UNDECODABLE, index.get("file-a"))
        assertEquals("and is not attached twice", true, index.isPersistent())
    }

    @Test
    fun `a visit that decided nothing writes nothing`() {
        val store = FakeVerdictStore()
        val index = AudioVerdictIndex(store = store)
        index.put("file-a", AudioVerdict.PLAYABLE)
        index.save()

        // Every visit to a settled library ends with a save; rewriting the same map there
        // would put a disk write in front of a screen the viewer only looked at.
        index.save()

        assertEquals(1, store.writes)
    }

    @Test
    fun `saving writes the whole map`() {
        val store = FakeVerdictStore()
        val index = AudioVerdictIndex(store = store)
        index.put("file-a", AudioVerdict.UNDECODABLE)
        index.put("file-b", AudioVerdict.NO_AUDIO)
        index.save()

        assertEquals(
            mapOf("file-a" to AudioVerdict.UNDECODABLE, "file-b" to AudioVerdict.NO_AUDIO),
            store.written,
        )
    }

    @Test
    fun `an index without a store keeps working`() {
        val index = AudioVerdictIndex()
        index.put("file-a", AudioVerdict.NO_AUDIO)

        assertFalse(index.isPersistent())
        index.save()

        assertEquals(AudioVerdict.NO_AUDIO, index.get("file-a"))
    }

    /** The store as the rules see it: a map that records how often it was written. */
    private class FakeVerdictStore(
        var written: Map<String, AudioVerdict> = emptyMap(),
    ) : AudioVerdictStore {

        var writes = 0
            private set

        override fun load(): Map<String, AudioVerdict> = written

        override fun save(verdicts: Map<String, AudioVerdict>) {
            written = verdicts
            writes++
        }
    }
}
