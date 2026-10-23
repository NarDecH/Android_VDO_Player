package com.nardech.vdoplayer.playback

import android.content.Context

/**
 * What this device already decided about a file's sound, kept by the file's own identity.
 *
 * The probe cache answers "which codec is this" for the handful of files a session
 * revisits, and it is deliberately small: eight entries, so the heap stays flat. A badge
 * in the library has the opposite shape — it wants one bit for every file in the
 * library — and a session's worth of cache cannot hold that.
 *
 * So the verdict is kept on its own. It is a decision, not evidence: the MIME types that
 * led to it stay in the probe cache and can be read again, while the answer here fits in
 * a byte and survives both a restart and the cache evicting the file.
 *
 * Only the two decided outcomes are kept. A read that failed, a file whose header says
 * nothing, or a decoder list that could not be asked are *not* verdicts: those files get
 * another chance instead of a permanent mark.
 */
enum class AudioVerdict {
    /** At least one audio track has a decoder on this device. */
    PLAYABLE,

    /** No track does, so the picture runs without sound. */
    UNDECODABLE,

    /** The file has no audio track at all — decided, but nothing to warn about. */
    NO_AUDIO,
}

/** Where verdicts go when the process is not running. */
interface AudioVerdictStore {

    fun load(): Map<String, AudioVerdict>

    fun save(verdicts: Map<String, AudioVerdict>)
}

/**
 * The index as one line per entry, `key<TAB>flag`, where the flag is `p`, `u` or `n`.
 *
 * A key is a URI plus two numbers, and a URI's tab and newline are percent-encoded, so
 * neither field can contain the separator. A line that does not split cleanly, names no
 * key, or carries a flag this build does not know is dropped rather than guessed at: a
 * missing verdict only costs one header read, while an invented one would mark a
 * perfectly playable file as silent.
 */
internal object AudioVerdictFormat {

    private const val SEPARATOR = '\t'

    fun encode(verdicts: Map<String, AudioVerdict>): String = verdicts.entries.joinToString("\n") {
        (key, verdict) -> "$key$SEPARATOR${flagOf(verdict)}"
    }

    fun decode(raw: String?): Map<String, AudioVerdict> {
        val decoded = LinkedHashMap<String, AudioVerdict>()
        raw?.lineSequence()?.forEach { line ->
            val separator = line.indexOf(SEPARATOR)
            if (separator <= 0 || separator == line.lastIndex) return@forEach
            val verdict = verdictOf(line.substring(separator + 1)) ?: return@forEach
            decoded[line.substring(0, separator)] = verdict
        }
        return decoded
    }

    private fun flagOf(verdict: AudioVerdict): Char = when (verdict) {
        AudioVerdict.PLAYABLE -> 'p'
        AudioVerdict.UNDECODABLE -> 'u'
        AudioVerdict.NO_AUDIO -> 'n'
    }

    private fun verdictOf(flag: String): AudioVerdict? = when (flag) {
        "p" -> AudioVerdict.PLAYABLE
        "u" -> AudioVerdict.UNDECODABLE
        "n" -> AudioVerdict.NO_AUDIO
        else -> null
    }
}

/**
 * The verdicts, in memory and — when a store is attached — on disk.
 *
 * Bigger than the probe cache on purpose, because a verdict is one byte where a probe
 * result is a list of MIME types, and still bounded: a viewer can collect files for
 * years, and a map nobody will ever look at must not grow without end. The least
 * recently used entry goes first, so the files the library keeps showing stay known.
 */
class AudioVerdictIndex(
    capacity: Int = DEFAULT_CAPACITY,
    private var store: AudioVerdictStore? = null,
) {

    private val capacity = capacity.coerceAtLeast(1)

    /** `accessOrder = true` makes the eviction least-recently-used rather than oldest-first. */
    private val entries = object : LinkedHashMap<String, AudioVerdict>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AudioVerdict>?,
        ): Boolean = size > capacity
    }

    /** Whether anything was decided since the last [save], so a settled library writes nothing. */
    private var dirty = false

    init {
        store?.load()?.forEach { (key, verdict) -> entries[key] = verdict }
    }

    /**
     * Attaches where verdicts are kept, once, and loads what an earlier run wrote.
     *
     * The store needs a `Context` and this class does not have one, so it arrives on the
     * first call that does — which is already off the main thread, because that call is
     * a probe or a sweep.
     */
    @Synchronized
    fun attach(store: AudioVerdictStore) {
        if (this.store != null) return
        this.store = store
        store.load().forEach { (key, verdict) -> entries[key] = verdict }
    }

    /** Whether verdicts already survive a restart, so no store has to be built. */
    @Synchronized
    fun isPersistent(): Boolean = store != null

    /** What was decided about [key], or `null` when this file has never been looked at. */
    @Synchronized
    fun get(key: String): AudioVerdict? = entries[key]

    /** Remembers [verdict] for [key], in memory only; [save] is what reaches the disk. */
    @Synchronized
    fun put(key: String, verdict: AudioVerdict) {
        if (entries.put(key, verdict) == verdict) return
        dirty = true
    }

    /**
     * Writes the whole map, once.
     *
     * A sweep decides many files in a row, and writing after each would rewrite the same
     * growing string a hundred times for one screen visit. The caller persists when the
     * visit ends, which is also what keeps a cancelled sweep's work on disk.
     */
    @Synchronized
    fun save() {
        val store = store ?: return
        if (!dirty) return
        store.save(entries.toMap())
        dirty = false
    }

    @Synchronized
    fun snapshot(): Map<String, AudioVerdict> = entries.toMap()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        entries.clear()
        dirty = false
    }

    companion object {
        /**
         * Big enough for a library's worth of badges, small enough that the map and the
         * string it is written as stay trivial next to a few thumbnails.
         */
        const val DEFAULT_CAPACITY = 512

        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * The real store: one `SharedPreferences` string in its own file, next to the cache's.
 *
 * Kept apart from `PrefsAudioCodecStore` because the two have different lifetimes: the
 * codec file is a bounded snapshot of eight entries that changes on every rescue, while
 * this one is the library's memory and changes only when a file is decided for the first
 * time.
 */
class PrefsAudioVerdictStore(context: Context) : AudioVerdictStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): Map<String, AudioVerdict> =
        AudioVerdictFormat.decode(prefs.getString(KEY_VERDICTS, null))

    override fun save(verdicts: Map<String, AudioVerdict>) {
        prefs.edit()
            .putString(KEY_VERDICTS, AudioVerdictFormat.encode(verdicts))
            .apply()
    }

    private companion object {
        const val FILE_NAME = "audio_verdicts"
        const val KEY_VERDICTS = "verdicts"
    }
}

/**
 * The process-wide verdicts, attached to disk on first use.
 *
 * Everything that asks about a file — the library drawing a badge, the sweep deciding a
 * new one — comes through here, so the answer is the same one everywhere and a file is
 * decided once rather than per screen.
 */
object AudioVerdicts {

    private val index = AudioVerdictIndex()

    /** What was decided about [key], or `null` when the file has never been looked at. */
    fun of(context: Context, key: String?): AudioVerdict? {
        keepResults(context)
        return key?.let { identity -> index.get(identity) }
    }

    /** Records a decision in memory; [persist] is what makes it survive the process. */
    fun remember(context: Context, key: String, verdict: AudioVerdict) {
        keepResults(context)
        index.put(key, verdict)
    }

    /** Writes what this visit learned. Called once, after a sweep ends or is cancelled. */
    fun persist() = index.save()

    private fun keepResults(context: Context) {
        if (index.isPersistent()) return
        index.attach(PrefsAudioVerdictStore(context))
    }

    /** Drops what earlier visits decided; tests start from a known empty index. */
    internal fun forgetRemembered() = index.clear()
}
