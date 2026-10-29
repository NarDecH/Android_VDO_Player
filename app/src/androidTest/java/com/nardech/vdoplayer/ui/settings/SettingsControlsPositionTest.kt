package com.nardech.vdoplayer.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The control bar position is the one setting with two switches — the player's title
 * bar and Settings → Playback — and both write the same stored preference. The DataStore
 * mapping is what makes that true, so it is checked on a device: a value written through
 * the repository has to read back, and the row in Settings has to write that value when
 * it is tapped rather than only changing a local copy.
 *
 * The stored value is put back afterwards: these tests run against the app's real
 * settings file, and a left-behind "top" would change how the other player tests lay
 * themselves out.
 */
@RunWith(AndroidJUnit4::class)
class SettingsControlsPositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private var original = false

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        original = runBlocking { container.settings.settings.first().controlsOnTop }
        runBlocking { container.settings.setControlsOnTop(false) }
    }

    @After
    fun restoreTheViewersChoice() {
        runBlocking { container.settings.setControlsOnTop(original) }
    }

    @Test
    fun whatIsStoredReadsBack() = runBlocking {
        container.settings.setControlsOnTop(true)
        assertTrue(
            "the stored control-bar position did not come back from DataStore",
            container.settings.settings.first().controlsOnTop,
        )
    }

    @Test
    fun theRowInSettingsWritesTheStoredChoice() {
        compose.setContent {
            val stored by container.settings.settings.collectAsState(initial = AppSettings())
            SettingsScreen(
                container = container,
                settings = stored,
                contentPadding = PaddingValues(0.dp),
            )
        }

        val label = compose.activity.getString(R.string.settings_controls_on_top)
        compose.onNodeWithText(label).performScrollTo()

        // The switch sits beside the label's column rather than inside it, so the row is
        // found from the switch's side: a toggle whose sibling carries the title.
        val row = compose.onNode(
            isToggleable() and hasAnySibling(hasAnyDescendant(hasText(label))),
        )
        row.assertIsOff()
        row.performClick()

        // The tap starts a DataStore write and the switch follows the stored flow, so the
        // assertion waits for that round trip instead of assuming it finished with the click.
        compose.waitUntil(5_000) {
            row.fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        }
        row.assertIsOn()

        assertTrue(
            "the switch moved but the stored preference did not",
            runBlocking { container.settings.settings.first() }.controlsOnTop,
        )
    }
}
