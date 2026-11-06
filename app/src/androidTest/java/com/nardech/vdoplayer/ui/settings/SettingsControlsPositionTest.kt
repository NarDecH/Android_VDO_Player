package com.nardech.vdoplayer.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.data.ControlsEdge
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

        // The tap starts a DataStore write on the composition's own dispatcher, and the
        // condition handed to waitUntil runs on the main thread: reading the repository
        // from inside that condition blocks the coroutine that is doing the writing, so
        // the write never lands and the wait times out on a value that was never stored.
        // The stored flow is collected on a background thread instead, and the condition
        // only compares what that collector last saw.
        val seen = AtomicReference(ControlsEdge.BOTTOM)
        val collector = CoroutineScope(Dispatchers.IO).launch {
            container.settings.settings.collect { seen.set(it.controlsEdge) }
        }
        try {
            compose.onNodeWithText(left).performClick()
            compose.waitUntil(5_000) { seen.get() == ControlsEdge.LEFT }
        } finally {
            collector.cancel()
        }
    }
}
