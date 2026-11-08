package com.nardech.vdoplayer.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.ControlsEdge
import com.nardech.vdoplayer.testing.awaitStored
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The control bar position is set in two places — the player's title bar and Settings →
 * Playback — and both write the same stored preference. The DataStore mapping is what
 * makes that true, so it is checked on a device: every edge has to read back from the
 * repository, and the chips in Settings have to write that value when one is tapped
 * rather than only changing a local copy.
 *
 * The stored value is put back afterwards: these tests run against the app's real
 * settings file, and a left-behind "left" would change how the other player tests lay
 * themselves out.
 */
@RunWith(AndroidJUnit4::class)
class SettingsControlsPositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private var original = ControlsEdge.BOTTOM

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        original = runBlocking { container.settings.settings.first().controlsEdge }
        runBlocking { container.settings.setControlsEdge(ControlsEdge.BOTTOM) }
    }

    @After
    fun restoreTheViewersChoice() {
        runBlocking { container.settings.setControlsEdge(original) }
    }

    @Test
    fun everyEdgeReadsBack() = runBlocking {
        ControlsEdge.entries.forEach { edge ->
            container.settings.setControlsEdge(edge)
            assertEquals(
                "the stored control-bar edge did not come back from DataStore",
                edge,
                container.settings.settings.first().controlsEdge,
            )
        }
    }

    @Test
    fun thePickerInSettingsWritesTheStoredChoice() {
        compose.setContent {
            val stored by container.settings.settings.collectAsState(initial = AppSettings())
            SettingsScreen(
                container = container,
                settings = stored,
                contentPadding = PaddingValues(0.dp),
            )
        }

        val title = compose.activity.getString(R.string.settings_controls_edge)
        // The Playback card is the second in the list, so its rows are not composed until
        // the list scrolls: the scroll happens on the list, to the node, rather than on a
        // node that does not exist yet.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title))

        val left = compose.activity.getString(R.string.controls_edge_left)
        // The click action is taken from the node rather than a touch at its centre: this
        // row is the last one the scroll brought into view, and a tap that lands on the
        // edge of the window can be swallowed before the chip sees it.
        compose.onNodeWithText(left).performSemanticsAction(SemanticsActions.OnClick)

        // The chip launches the write on the composition's own scope, and the v2 rule
        // dispatches that back to its StandardTestDispatcher: a queued task runs only when
        // the test lets it, so `waitForIdle` is what starts the write at all. Without it the
        // test waits on a coroutine that has not been dispatched yet.
        compose.waitForIdle()

        // The wait then happens one thread away from the UI: `awaitStored` suspends on
        // Dispatchers.IO and returns as soon as the stored flow reports the new edge, or null
        // if nothing was written. A `compose.waitUntil` would poll the UI thread, which is
        // the thread the write resumes on — `.github/scripts/check-instrumented-waits.sh`
        // refuses it.
        val stored = container.settings.awaitStored { it.controlsEdge == ControlsEdge.LEFT }
        assertNotNull("tapping the chip did not store the edge", stored)
    }
}
