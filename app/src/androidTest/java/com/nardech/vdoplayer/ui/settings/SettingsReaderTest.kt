package com.nardech.vdoplayer.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nardech.vdoplayer.AppContainer
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.AppSettings
import com.nardech.vdoplayer.util.Formatters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings is a list of rows that say everything with text, which is why it looked fine to
 * a screen reader and was left out of the player's audit. Three things still did not work
 * there: the one slider was announced as a percentage with no name, a chip read as
 * "2, selected" with nothing to say what it was choosing between, and the card titles were
 * plain text, so jumping heading by heading found nothing at all. None of that shows in a
 * screenshot, so it is asserted through the semantics tree.
 */
@RunWith(AndroidJUnit4::class)
class SettingsReaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private lateinit var stored: AppSettings

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        stored = runBlocking { container.settings.settings.first() }
    }

    @Test
    fun theSpeedSliderIsNamedAndSpeaksTheSpeed() {
        showSettings()

        val title = string(R.string.settings_default_speed)
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(title))

        val slider = compose.onNode(hasContentDescription(title)).fetchSemanticsNode()
        assertEquals(
            "the slider should speak the speed, not a percentage of it",
            Formatters.speed(stored.defaultSpeed),
            slider.config.getOrNull(SemanticsProperties.StateDescription),
        )
        // Naming the slider must not cost it its role or its adjustability: taking over the
        // semantics of a control is only worth it if it still works as the control it is.
        assertNotNull(
            "the slider lost its progress range when the label was added",
            slider.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo),
        )
        assertTrue(
            "the slider can no longer be adjusted by a screen reader",
            slider.config.contains(SemanticsActions.SetProgress),
        )
    }

    @Test
    fun theCardTitlesAreHeadings() {
        showSettings()

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        val appearance = string(R.string.settings_appearance)
        compose.onNode(hasText(appearance) and heading).assertExists()
    }

    @Test
    fun aChipSaysWhichRowItAnswers() {
        showSettings()

        val decoder = string(R.string.settings_decoder)
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(decoder, substring = true))

        val chips = compose.onAllNodes(hasContentDescription(decoder, substring = true))
            .fetchSemanticsNodes()
        assertTrue("the decoder chips carry no row name at all", chips.isNotEmpty())

        // Each chip is announced as its row and its own label, and pressing it is the only
        // thing the node does: a chip that also reports a text field or a toggle would be
        // read twice.
        val described = chips.mapNotNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        }
        assertTrue(
            "a chip is named $described, which does not start with \"$decoder\"",
            described.all { it.startsWith("$decoder,") },
        )
    }

    private fun string(res: Int) = compose.activity.getString(res)

    private fun showSettings() {
        compose.setContent {
            val live by container.settings.settings.collectAsState(initial = AppSettings())
            SettingsScreen(
                container = container,
                settings = live,
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}
