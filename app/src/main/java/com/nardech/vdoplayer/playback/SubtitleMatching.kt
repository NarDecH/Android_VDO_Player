package com.nardech.vdoplayer.playback

/**
 * Which subtitle file belongs to which video, decided from names alone.
 *
 * Kept free of Android types so the rules can be tested on the JVM: the MediaStore
 * part lives in [SubtitleResolver] and only supplies candidate file names. A
 * subtitle matches when it sits next to the video under the same base name —
 * `movie.mp4` → `movie.srt` — or with an optional language tag in between —
 * `movie.th.srt`, `movie.zh-Hans.srt`.
 */
object SubtitleMatching {

    /** Extensions Media3 can parse; anything else in the folder is ignored. */
    val EXTENSIONS = listOf("srt", "vtt", "ass", "ssa")

    /** Subtitle files carry a language prefix; the tag rules follow BCP-47 loosely. */
    private val LANGUAGE_TAG = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")

    fun isSubtitle(fileName: String): Boolean = extensionOf(fileName) != null

    fun extensionOf(fileName: String): String? =
        fileName.substringAfterLast('.', "").lowercase().takeIf { it in EXTENSIONS }

    /** `Movie.Thai.srt` → `movie.thai`; a name without an extension is returned as it is. */
    fun baseName(fileName: String): String = fileName.substringBeforeLast('.', fileName).lowercase()

    /** The name without its extension, for a track label: `Movie.Thai.srt` → `Movie.Thai`. */
    fun label(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    /**
     * Language tag a candidate carries for [videoDisplayName], or `null` when the
     * file is named exactly like the video (`movie.srt`) and therefore has no tag.
     */
    fun languageTag(videoDisplayName: String, candidate: String): String? {
        val videoBase = baseName(videoDisplayName)
        if (videoBase.isEmpty()) return null
        val candidateBase = baseName(candidate)
        val prefix = "$videoBase."
        if (!candidateBase.startsWith(prefix)) return null
        val tag = candidateBase.removePrefix(prefix).substringBefore('.')
        return tag.takeIf { isLanguageTag(it) }
    }

    /**
     * How well a candidate fits the video: `null` means it is not a subtitle of
     * this video at all, and a lower number is a better match.
     *
     * 0 — named exactly like the video; 1 — language tag matches the device
     * language; 2 — a subtitle of this video in another language.
     */
    fun score(videoDisplayName: String, candidate: String, preferredLanguage: String?): Int? {
        if (!isSubtitle(candidate)) return null
        val videoBase = baseName(videoDisplayName)
        if (videoBase.isEmpty()) return null
        val candidateBase = baseName(candidate)
        if (candidateBase == videoBase) return 0
        val prefix = "$videoBase."
        if (!candidateBase.startsWith(prefix)) return null
        val tag = candidateBase.removePrefix(prefix).substringBefore('.')
        if (!isLanguageTag(tag)) return null
        val language = preferredLanguage?.lowercase()?.takeIf { it.isNotBlank() } ?: return 2
        return when {
            tag == language -> 1
            tag.startsWith("$language-") || language.startsWith("$tag-") -> 1
            else -> 2
        }
    }

    /**
     * The candidates that belong to [videoDisplayName], best match first. Ties are
     * broken by file name so the order is stable, which keeps the track the user
     * sees the same across scans.
     */
    fun rank(
        videoDisplayName: String,
        candidates: List<String>,
        preferredLanguage: String?,
    ): List<String> = candidates
        .filter { score(videoDisplayName, it, preferredLanguage) != null }
        .sortedWith(
            compareBy(
                { score(videoDisplayName, it, preferredLanguage) },
                { baseName(it) },
                { it.lowercase() },
            ),
        )

    private fun isLanguageTag(tag: String): Boolean = LANGUAGE_TAG.matches(tag)
}
