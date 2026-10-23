package com.nardech.vdoplayer.ui.library

/**
 * How the library's folder grouping is built, kept out of the composable so the order it
 * promises can be tested without a device.
 *
 * Generic in the item on purpose: the rules are about folders and order, and a test can
 * pin them with plain strings instead of building `VideoItem`s, which need an Android
 * `Uri` just to exist.
 */
object LibrarySections {

    /**
     * The library split into folder sections, or `emptyList` when grouping is off — the
     * caller draws one flat list in that case.
     *
     * Folders are ordered by name rather than by the file order underneath them, so a
     * section does not move around while the library refreshes. The items inside keep the
     * order they arrived in, which is the sort the viewer chose: grouping decides where a
     * file is shown, not how files compare.
     */
    fun <T> of(
        items: List<T>,
        grouped: Boolean,
        folderOf: (T) -> String,
    ): List<Pair<String, List<T>>> = if (!grouped) {
        emptyList()
    } else {
        items.groupBy(folderOf).toSortedMap().toList()
    }

    /**
     * What next/previous walks: the flat list when nothing is grouped, otherwise the
     * sections in drawn order, so a grouped library plays folder by folder instead of
     * jumping between sections in the order the files happened to sort.
     */
    fun <T> playOrder(items: List<T>, sections: List<Pair<String, List<T>>>): List<T> =
        if (sections.isEmpty()) items else sections.flatMap { (_, section) -> section }
}
