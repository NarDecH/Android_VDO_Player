package com.nardech.vdoplayer.playback

import android.content.Context

/**
 * Where probe results go when the process is not running.
 *
 * A remembered codec used to be useful only within one launch: the cache lived in the
 * heap, so the first play after every restart paid for a header read and started the
 * picture a moment before the sound. The answer for a file cannot change until its
 * bytes do, and its key already carries the size and the timestamp that change, so the
 * map is safe to keep on disk.
 *
 * Kept as an interface so the rules — bounded size, newest wins, malformed lines are
 * skipped — are tested on the JVM with a fake, while [PrefsAudioCodecStore] is the one
 * implementation that touches `SharedPreferences`.
 */
interface AudioCodecStore {

    fun load(): List<Pair<String, String>>

    fun save(entries: List<Pair<String, String>>)
}

/**
 * The remembered map as one line per entry, `key<TAB>mimes`.
 *
 * The value holds every audio track of the file, comma-separated: a file with a second
 * playable track is not silent, so one track's answer is not enough to keep.
 *
 * A key is a URI plus two numbers, and a URI's tab and newline are percent-encoded, so
 * neither field can contain the separators. Lines that do not split cleanly are dropped
 * rather than repaired: a key nobody can rebuild is a miss, and a miss only costs the
 * header read.
 *
 * A value without a slash in it is dropped too. That is not a MIME type: it is a codec
 * name written by the version that kept only names, and it cannot answer the question
 * the header is read for now — whether any decoder on this device can read the track —
 * so the file is read again and the entry replaced.
 */
internal object AudioCodecStoreFormat {

    private const val SEPARATOR = '\t'

    fun encode(entries: List<Pair<String, String>>): String =
        entries.joinToString("\n") { (key, codec) -> "$key$SEPARATOR$codec" }

    fun decode(raw: String?): List<Pair<String, String>> = raw
        ?.lineSequence()
        ?.mapNotNull { line ->
            val separator = line.indexOf(SEPARATOR)
            when {
                separator <= 0 || separator == line.lastIndex -> null
                else -> line.substring(0, separator) to line.substring(separator + 1)
            }?.takeIf { (_, value) -> value.contains('/') }
        }
        ?.toList()
        .orEmpty()
}

/**
 * The real store: one `SharedPreferences` string.
 *
 * `apply()` writes in the background, and the map is written only when a codec is
 * learned, so a play that hits the cache never touches the disk.
 */
class PrefsAudioCodecStore(context: Context) : AudioCodecStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): List<Pair<String, String>> =
        AudioCodecStoreFormat.decode(prefs.getString(KEY_RESULTS, null))

    override fun save(entries: List<Pair<String, String>>) {
        prefs.edit()
            .putString(KEY_RESULTS, AudioCodecStoreFormat.encode(entries))
            .apply()
    }

    private companion object {
        const val FILE_NAME = "audio_codecs"
        const val KEY_RESULTS = "probe_results"
    }
}
