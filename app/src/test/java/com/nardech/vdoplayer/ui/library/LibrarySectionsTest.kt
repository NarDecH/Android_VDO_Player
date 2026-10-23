package com.nardech.vdoplayer.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grouping changes where a file is drawn and what next/previous follows, so both are
 * pinned here with plain strings — the composable only has to trust this list.
 */
class LibrarySectionsTest {

    private val sorted = listOf("Camera/a", "Camera/b", "Download/c", "Movies/d")

    private fun folderOf(path: String): String = path.substringBefore('/')

    @Test
    fun `grouping off keeps the flat list`() {
        assertEquals(
            "an ungrouped library must draw exactly what the sort produced",
            emptyList<Pair<String, List<String>>>(),
            LibrarySections.of(sorted, grouped = false, folderOf = ::folderOf),
        )
        assertEquals(sorted, LibrarySections.playOrder(sorted, emptyList()))
    }

    @Test
    fun `sections are ordered by folder, files keep their own sort`() {
        // The sort put these in date order, so the folders are interleaved: grouping has
        // to gather them without re-sorting what is inside them.
        val byDate = listOf("Movies/d", "Camera/a", "Download/c", "Camera/b")

        val sections = LibrarySections.of(byDate, grouped = true, folderOf = ::folderOf)

        assertEquals(
            listOf("Camera", "Download", "Movies"),
            sections.map { (folder, _) -> folder },
        )
        assertEquals(
            "the sort inside a folder is the one the viewer chose, not an alphabet of files",
            listOf("Camera/a", "Camera/b"),
            sections.first().second,
        )
    }

    @Test
    fun `playback follows the drawn order when grouped`() {
        val sections = LibrarySections.of(sorted, grouped = true, folderOf = ::folderOf)

        assertEquals(
            "next/previous must walk the sections as they are shown",
            listOf("Camera/a", "Camera/b", "Download/c", "Movies/d"),
            LibrarySections.playOrder(sorted, sections),
        )
    }

    @Test
    fun `grouping a filtered list only grows the sections that survived`() {
        val filtered = listOf("Camera/b", "Movies/d")

        val sections = LibrarySections.of(filtered, grouped = true, folderOf = ::folderOf)

        assertEquals(
            listOf("Camera" to listOf("Camera/b"), "Movies" to listOf("Movies/d")),
            sections,
        )
    }
}
