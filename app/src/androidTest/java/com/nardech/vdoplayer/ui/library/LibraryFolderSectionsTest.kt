package com.nardech.vdoplayer.ui.library

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.VideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grouped library is the one part of the folder view that a JVM test cannot judge:
 * [LibrarySections] proves the order of the sections, but only a device shows that the
 * heading is really drawn, spans the row above its files, carries that folder's count,
 * and works as the folder's filter when tapped.
 *
 * The screen itself needs a media permission, MediaStore and the settings store before it
 * draws anything, so the test drives [LibraryGrid] directly and rebuilds the sections the
 * same way the screen does.
 */
@RunWith(AndroidJUnit4::class)
class LibraryFolderSectionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val camera = listOf(
        video(id = 1, name = "camera-one", folder = "Camera"),
        video(id = 2, name = "camera-two", folder = "Camera"),
    )
    private val download = video(id = 3, name = "download-one", folder = "Download")
    private val all = camera + download

    @Test
    fun everyFolderGetsAHeadingWithItsNameAndItsCount() {
        compose.setContent { Grouped(all) }

        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText(count(2)).assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText(count(1)).assertIsDisplayed()

        // The files sit under their own heading rather than in one mixed list.
        compose.onNodeWithText("camera-one").assertIsDisplayed()
        compose.onNodeWithText("download-one").assertIsDisplayed()
    }

    @Test
    fun aHeadingIsThatFoldersFilter() {
        compose.setContent { Grouped(all) }

        compose.onNodeWithText("Download").performClick()

        compose.onNodeWithText("download-one").assertIsDisplayed()
        compose.onNodeWithText("camera-one").assertDoesNotExist()
        compose.onNodeWithText("camera-two").assertDoesNotExist()

        // The same heading toggles the filter off again.
        compose.onNodeWithText("Download").performClick()

        compose.onNodeWithText("camera-one").assertIsDisplayed()
        compose.onNodeWithText("download-one").assertIsDisplayed()
    }

    @Test
    fun aHeadingPlaysItsWholeFolderFromTheFirstFile() {
        var played: List<VideoItem> = emptyList()
        compose.setContent { Grouped(all, onPlaySection = { played = it }) }

        compose.onNodeWithContentDescription(playLabel("Camera")).performClick()

        // The heading starts the folder it names, in the order drawn. The whole library
        // would have carried on into Download once these two ended.
        assertEquals(camera.map { it.id }, played.map { it.id })
        // Playing is not filtering: the rest of the library is still on screen.
        compose.onNodeWithText("download-one").assertIsDisplayed()
    }

    @Test
    fun aHeadingSpansTheWholeGridInGridMode() {
        compose.setContent { Grouped(all, gridMode = true, gridColumns = 2) }

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText("Camera").getUnclippedBoundsInRoot()
        val rootWidth = root.right - root.left
        val headingWidth = heading.right - heading.left

        // Two columns share the row, so a heading that only took one of them would be
        // about half as wide as the grid; a spanning one is nearly all of it.
        assertTrue(
            "the heading is $headingWidth wide in a $rootWidth grid",
            headingWidth > rootWidth * 0.8f,
        )
    }

    @Test
    fun aHeadingStaysAtTheTopWhileItsFilesScroll() {
        val cameraFiles = (1L..30L).map { n -> video(id = n, name = "camera-$n", folder = "Camera") }
        compose.setContent { Grouped(cameraFiles, gridMode = false) }

        // Far enough into the section that no screen could still be showing its start.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("camera-25"))

        // The first files have left the screen, so the heading is the only thing left
        // naming the folder — and it has to be on screen for that to mean anything.
        compose.onNodeWithText("camera-1").assertDoesNotExist()
        val heading = compose.onNodeWithText("Camera").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "the heading sits at $heading, the library at $root",
            heading.top < root.top + 160.dp,
        )
    }

    @Test
    fun theNextFolderPushesThePinnedHeadingAway() {
        val camera = (1L..30L).map { n -> video(id = n, name = "camera-$n", folder = "Camera") }
        val download = (1L..30L).map { n -> video(id = 100L + n, name = "download-$n", folder = "Download") }
        compose.setContent { Grouped(camera + download, gridMode = false) }

        // Far enough into the second folder that its own heading has to be the pinned one:
        // reaching only its first file would leave the heading wherever the scroll stopped.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("download-25"))

        compose.onNodeWithText("camera-1").assertDoesNotExist()
        compose.onNodeWithText("download-1").assertDoesNotExist()

        // With the second section reached, its own heading is the one pinned on top.
        val heading = compose.onNodeWithText("Download").assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "Download's heading sits at $heading, the library at $root",
            heading.top < root.top + 160.dp,
        )
    }

    @Test
    fun ungroupedDrawsTheFlatListWithoutHeadings() {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                LibraryGrid(
                    items = all,
                    sections = emptyList(),
                    gridMode = false,
                    gridColumns = 1,
                    knownSilent = emptySet(),
                    onPlay = {},
                    onLongClick = {},
                    onFolderPick = {},
                    onPlaySection = {},
                )
            }
        }

        all.forEach { item -> compose.onNodeWithText(item.title).assertIsDisplayed() }
        compose.onNodeWithText(count(2)).assertDoesNotExist()
        compose.onNodeWithText(count(1)).assertDoesNotExist()
    }

    /** The screen's own state: a folder filter and the sections it leaves behind. */
    @Composable
    private fun Grouped(
        items: List<VideoItem>,
        gridMode: Boolean = false,
        gridColumns: Int = 1,
        onPlaySection: (List<VideoItem>) -> Unit = {},
    ) {
        var folderFilter by remember { mutableStateOf<String?>(null) }
        val visible = items.filter { item -> folderFilter == null || item.folder == folderFilter }
        val sections = LibrarySections.of(visible, grouped = true) { item -> item.folder }
        Box(modifier = Modifier.fillMaxSize()) {
            LibraryGrid(
                items = visible,
                sections = sections,
                gridMode = gridMode,
                gridColumns = gridColumns,
                knownSilent = emptySet(),
                onPlay = {},
                onLongClick = {},
                onFolderPick = { folder ->
                    folderFilter = if (folderFilter == folder) null else folder
                },
                onPlaySection = onPlaySection,
            )
        }
    }

    /**
     * Read from the app's own resources rather than spelled out, so the assertions hold on
     * a device whose language is not English.
     */
    private fun count(files: Int): String {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        return resources.getQuantityString(R.plurals.library_folder_count, files, files)
    }

    /**
     * The play button's own label. The folder names here are real folder names, not the
     * external-file key, so the resource can be read without the composable form.
     */
    private fun playLabel(folder: String): String {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        return resources.getString(R.string.library_play_folder, folder)
    }

    private fun video(id: Long, name: String, folder: String): VideoItem = VideoItem(
        id = id,
        uri = Uri.parse("content://media/external/video/media/$id"),
        displayName = "$name.mp4",
        durationMs = 60_000L,
        sizeBytes = 1024L * 1024L,
        dateAddedSeconds = 1_700_000_000L,
        width = 1280,
        height = 720,
        mimeType = "video/mp4",
        folder = folder,
        relativePath = "$folder/$name.mp4",
    )
}
