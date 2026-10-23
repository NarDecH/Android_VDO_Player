package com.nardech.vdoplayer.playback

import kotlinx.coroutines.yield

/**
 * Decides the library's audio badges for the files nobody has played yet.
 *
 * A badge could only ever appear for a file whose header had already been read, which
 * meant the files a viewer had played — and the badge exists to warn about a file *before*
 * it is played. The work that answers the question is the same one playback does, so the
 * library does it too: one header read per file, once, and the decision kept in
 * [AudioVerdicts] where every later visit finds it without opening anything.
 *
 * The walk is deliberately gentle, because it runs in the background of a list the viewer
 * is looking at:
 *
 * * **Known files cost nothing.** A key with a verdict is skipped before anything is read,
 *   so the second visit to a settled library opens no file at all.
 * * **A file the probe already read is not read again.** [remembered] is the probe cache,
 *   and a hit becomes a verdict straight away.
 * * **A visit is bounded.** At most [DEFAULT_BUDGET] undecided files are looked at, so a
 *   library of thousands cannot turn into a long silent read of the whole disk — the
 *   badges fill in over a few visits instead. Cancelling the caller's coroutine (leaving
 *   the screen, a new library emission) stops it between two files.
 * * **Nothing uncertain is written.** A read that failed, a header that is not a real
 *   audio track, or a decoder list that could not be asked produce no verdict, so those
 *   files are tried again instead of being marked forever.
 */
internal object AudioVerdictSweep {

    /**
     * How many undecided files one visit looks at.
     *
     * A header read is cheap, but it is still a file opened on a screen that is only being
     * looked at, and on a device with cloud media in the library it can be a download.
     * Capped here, the first visit to a large library decides the first hundred-odd files
     * and the next visit continues where it stopped.
     */
    const val DEFAULT_BUDGET = 120

    /**
     * Walks [keys] in order and reports what each still-undecided file answered.
     *
     * Returns how many files this pass decided. Everything is a parameter so the walk can
     * be tested without a device: [remembered] and [readMimes] are the probe, [outlook] is
     * [AudioPreflight]'s rule, and [onVerdict] is where a decision goes.
     */
    suspend fun sweep(
        keys: List<String>,
        budget: Int = DEFAULT_BUDGET,
        known: (String) -> AudioVerdict?,
        remembered: (String) -> List<String>?,
        readMimes: (String) -> List<String>?,
        outlook: (List<String>) -> AudioOutlook,
        onVerdict: suspend (String, AudioVerdict) -> Unit,
    ): Int {
        var examined = 0
        var decided = 0
        for (key in keys) {
            if (known(key) != null) continue
            if (examined >= budget) break
            examined++
            // A hit in the probe cache is the same answer without the read.
            val mimes = remembered(key) ?: readMimes(key) ?: continue
            val verdict = decide(mimes, outlook) ?: continue
            onVerdict(key, verdict)
            decided++
            // Between two files, so a scroll or a screen change is not stuck behind the
            // whole walk.
            yield()
        }
        return decided
    }

    /**
     * The verdict for [mimes], or `null` when this visit learned nothing worth keeping.
     *
     * A file with no audio track is decided — [AudioVerdict.NO_AUDIO] — because otherwise
     * every visit would open it again to reach the same conclusion. Everything else that
     * comes back [AudioOutlook.UNKNOWN] is a question that could not be asked, not a
     * finding, so it stays undecided.
     */
    fun decide(
        mimes: List<String>,
        outlook: (List<String>) -> AudioOutlook,
    ): AudioVerdict? = when (outlook(mimes)) {
        AudioOutlook.PLAYABLE -> AudioVerdict.PLAYABLE
        AudioOutlook.UNDECODABLE -> AudioVerdict.UNDECODABLE
        AudioOutlook.UNKNOWN -> if (mimes.isEmpty()) AudioVerdict.NO_AUDIO else null
    }
}
