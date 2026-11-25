package com.nardech.vdoplayer.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.nardech.vdoplayer.testing.awaitStoredSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
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

        // The click hands the write to the composition's scope and returns, so the tap is
        // proof that the chip was hit and nothing more. Idling here runs whatever the tap
        // posted before the clock below starts: the emulator stalls frames under load — the
        // run that failed logged "Skipped 42 frames" a third of a second before the tap.
        compose.waitForIdle()

        // The composition's scope is driven from this thread, so the wait hands the thread
        // back to it between reads instead of parking it for the whole window:
        // `awaitStoredSettings` reads on Dispatchers.IO in short slices and lets the
        // composition run in between, and still returns null when nothing matched inside
        // the window — a write that really does not happen still fails the test. A
        // `compose.waitUntil` here would run its condition on the UI thread, and
        // `.github/scripts/check-instrumented-waits.sh` refuses it.
        val stored = compose.awaitStoredSettings(container.settings) {
            it.controlsEdge == ControlsEdge.LEFT
        }
        if (stored == null) {
            // What the store holds separates the two ways this fails: a value that is still
            // BOTTOM means the tap never reached the write, and a value of LEFT read now
            // means the write landed after the wait had already given up. One more turn for
            // the composition, so a write queued as the window closed counts as late rather
            // than as never having happened.
            compose.waitForIdle()
            val actual = runBlocking { container.settings.settings.first().controlsEdge }
            fail("tapping the chip did not store the edge: the store says $actual")
        }
    }
}
