package com.nardech.vdoplayer.playback

/**
 * What probing found for a file, remembered by the file's own identity.
 *
 * Opening a video only reads its header to answer one question: is this the audio codec
 * the viewer already sent to FFmpeg? The answer cannot change until the bytes do, yet the
 * same file comes back often — the next episode played again, an app restart, a return to
 * the same video after leaving the player — and each of those paid for a fresh extractor
 * and header read.
 *
 * What is kept is the file's audio MIME types, not their short names. A name is only a
 * spelling of the MIME type ([AudioSilence.codecKey]), while the other question the header
 * answers — can anything on this device decode it — can only be asked of the MIME types
 * themselves, and every track has to be in the answer: one playable track is sound.
 *
 * Keys are built by [AudioCodecProbe.cacheKey] from the file's size and timestamp, so a
 * re-downloaded or replaced file lands on a different key instead of inheriting a stale
 * answer. The map is bounded and evicts the least recently used entry, because a viewer
 * can walk through an unbounded number of files and a decoder nobody will re-check must
 * not stay in the heap for the life of the process.
 *
 * A [store], when one is attached, keeps the same bounded map across restarts: what is
 * learned is written where [AudioCodecStore] points, and read back the next time the
 * process starts. The bound is the cache's own capacity, so the file cannot grow past
 * the handful of entries the memory keeps.
 */
class AudioCodecCache(
    capacity: Int = DEFAULT_CAPACITY,
    private var store: AudioCodecStore? = null,
) {

    private val capacity = capacity.coerceAtLeast(1)

    /** `accessOrder = true` is what makes this least-recently-used rather than oldest-first. */
    private val entries = object : LinkedHashMap<String, String>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?,
        ): Boolean = size > capacity
    }

    init {
        store?.load()?.forEach { (key, mime) -> entries[key] = mime }
    }

    /**
     * Attaches where results are kept, once, and loads what an earlier run wrote.
     *
     * The probe knows the `Context` only when it runs, while this cache is a field on a
     * singleton, so the store arrives after construction. A second call does nothing,
     * which is what keeps the load from happening on every play.
     */
    @Synchronized
    fun attach(store: AudioCodecStore) {
        if (this.store != null) return
        this.store = store
        // Loading writes nothing: the file was written by an earlier `put`, so it already
        // holds a bounded snapshot, and the first play of a launch must not pay for a
        // write it did not need.
        store.load().forEach { (key, mime) -> entries[key] = mime }
    }

    /** Whether results already survive a restart, so no store has to be built. */
    @Synchronized
    fun isPersistent(): Boolean = store != null

    /** The audio MIME type remembered for [key], or `null` when this file was never probed. */
    @Synchronized
    fun get(key: String): String? = entries[key]

    /**
     * Remembers [mime] for [key] and writes the map when a store is attached.
     *
     * Returns whether this was a new answer, so a caller that only cares about changes
     * can skip the rest of its work; an entry heard again is not rewritten to disk.
     */
    @Synchronized
    fun put(key: String, mime: String): Boolean {
        val previous = entries.put(key, mime)
        if (previous == mime) return false
        persistLocked()
        return true
    }

    /** The entries in least-recently-used order, which is the order they are written in. */
    @Synchronized
    fun snapshot(): List<Pair<String, String>> = entries.map { (key, mime) -> key to mime }

    private fun persistLocked() {
        store?.save(snapshot())
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        /**
         * Enough for the handful of files a session actually revisits. A miss only costs
         * the header read, so keeping this small costs nothing and bounds the memory.
         */
        const val DEFAULT_CAPACITY = 8

        private const val LOAD_FACTOR = 0.75f
    }
}
