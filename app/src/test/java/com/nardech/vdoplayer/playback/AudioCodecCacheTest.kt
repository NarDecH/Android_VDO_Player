package com.nardech.vdoplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The probe's cache is a pure map, so its two rules are pinned here rather than hoped
 * for on a phone: a file is only remembered under an identity that changes when the
 * bytes change, and the memory stays bounded however many files get played.
 */
class AudioCodecCacheTest {

    @Test
    fun `a probed file is answered from memory the second time`() {
        val cache = AudioCodecCache()
        val key = AudioCodecProbe.cacheKey("content://video/7", 900L, 1_700_000_000L)!!

        assertNull("an unread file must not have a codec yet", cache.get(key))
        cache.put(key, "AC3")

        assertEquals("AC3", cache.get(key))
    }

    @Test
    fun `a replaced file is a different file`() {
        val before = AudioCodecProbe.cacheKey("content://video/7", 900L, 1_700_000_000L)
        val longer = AudioCodecProbe.cacheKey("content://video/7", 901L, 1_700_000_000L)
        val rewritten = AudioCodecProbe.cacheKey("content://video/7", 900L, 1_700_000_100L)

        assertNotEquals("a re-downloaded file must be probed again", before, longer)
        assertNotEquals("an in-place edit must be probed again", before, rewritten)
        assertEquals(
            "the same untouched file has to keep its key",
            before,
            AudioCodecProbe.cacheKey("content://video/7", 900L, 1_700_000_000L),
        )
    }

    @Test
    fun `a file of unknown size is never remembered`() {
        assertNull(
            "an unscanned file must not be cached under an identity that could be reused",
            AudioCodecProbe.cacheKey("content://downloads/9", 0L, 1_700_000_000L),
        )
    }

    @Test
    fun `the memory stays bounded`() {
        val cache = AudioCodecCache(capacity = 3)
        repeat(10) { index -> cache.put("file-$index", "AC3") }

        assertEquals(3, cache.size())
        assertEquals("the most recent file must survive", "AC3", cache.get("file-9"))
        assertNull("the oldest file must have been dropped", cache.get("file-0"))
    }

    @Test
    fun `the file used recently survives the eviction`() {
        val cache = AudioCodecCache(capacity = 2)
        cache.put("first", "AC3")
        cache.put("second", "AC3")

        // Reading "first" makes it the most recently used, so "second" is the one to go.
        cache.get("first")
        cache.put("third", "AC3")

        assertEquals("AC3", cache.get("first"))
        assertNull("the untouched file is the one evicted", cache.get("second"))
    }

    @Test
    fun `a codec learned in one run is known in the next`() {
        val store = FakeStore()
        AudioCodecCache(store = store).put("file-a", "AC3")

        val nextRun = AudioCodecCache(store = store)

        assertEquals(
            "a restart used to pay for the same header read, which is the whole point of the store",
            "AC3",
            nextRun.get("file-a"),
        )
    }

    @Test
    fun `what is written stays bounded`() {
        val store = FakeStore()
        val cache = AudioCodecCache(capacity = 2, store = store)
        cache.put("first", "AC3")
        cache.put("second", "DTS")
        cache.put("third", "EAC3")

        assertEquals(
            "the file must not grow past the handful of entries the memory keeps",
            2,
            store.written.size,
        )
        assertEquals(
            listOf("second", "third"),
            store.written.map { it.first },
        )
    }

    @Test
    fun `an answer heard again is not written again`() {
        val store = FakeStore()
        val cache = AudioCodecCache(store = store)
        cache.put("file-a", "AC3")
        val writes = store.writes

        assertFalse("a repeated answer is not a new one", cache.put("file-a", "AC3"))
        assertEquals(
            "writing the same map on every hit would put a disk write in the path of a play",
            writes,
            store.writes,
        )
    }

    @Test
    fun `a cache without a store keeps working`() {
        val cache = AudioCodecCache()
        cache.put("file-a", "AC3")

        assertFalse(cache.isPersistent())
        assertEquals("AC3", cache.get("file-a"))
    }

    /** The store as the rules see it: a map that records how often it was written. */
    private class FakeStore(
        var written: List<Pair<String, String>> = emptyList(),
    ) : AudioCodecStore {

        var writes = 0
            private set

        override fun load(): List<Pair<String, String>> = written

        override fun save(entries: List<Pair<String, String>>) {
            written = entries
            writes++
        }
    }
}
