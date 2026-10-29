package com.nardech.vdoplayer.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.playback.PlayerUiState
import com.nardech.vdoplayer.util.Formatters
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every control on the player is an icon, so a screen reader has nothing but the content
 * description to go on: without one, a button is announced as "Button" and the viewer has
 * to guess. That is invisible in a screenshot and in a click test, which is why it is
 * asserted here through the semantics tree.
 *
 * The other three cases are the labels that used to be wrong rather than missing: a seek
 * bar that reported a percentage instead of a time, a repeat button whose label was a
 * state with no name, and a queue counter drawn as "1/3", which is read out as "one slash
 * three".
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlLabelsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyButtonOnThePlayerSaysWhatItDoes() {
        compose.setContent { Overlay() }

        val buttons = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("no buttons were laid out at all", buttons.isNotEmpty())

        val silent = buttons.filter { node ->
            val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
            descriptions.all { it.isBlank() } && texts.all { it.isBlank() }
        }
        assertTrue(
            "${silent.size} of ${buttons.size} controls would be announced as a bare button",
            silent.isEmpty(),
        )
    }

    @Test
    fun theSeekBarReportsTheTimeItWouldLandOn() {
        compose.setContent { Overlay(positionMs = 30_000L, durationMs = 90_000L) }

        val expected = compose.activity.getString(
            R.string.player_seek_state,
            Formatters.duration(30_000L),
            Formatters.duration(90_000L),
        )
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected))
            .assertExists()
    }

    @Test
    fun theRepeatButtonNamesTheModeItIsIn() {
        compose.setContent { Overlay(repeatMode = Player.REPEAT_MODE_ALL) }

        val expected = compose.activity.getString(
            R.string.player_repeat_announce,
            compose.activity.getString(R.string.player_repeat_all),
        )
        compose.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun theQueueCounterIsSpokenAsAPosition() {
        compose.setContent { Overlay(queueSize = 3) }

        val expected = compose.activity.getString(R.string.player_queued_position, 1, 3)
        compose.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Composable
    private fun Overlay(
        positionMs: Long = 30_000L,
        durationMs: Long = 90_000L,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        queueSize: Int = 1,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerControls(
                state = PlayerUiState(
                    durationMs = durationMs,
                    positionMs = positionMs,
                    bufferedMs = positionMs + 15_000L,
                    repeatMode = repeatMode,
                    queueIndex = 0,
                    queueSize = queueSize,
                ),
                title = "Test clip",
                folder = "",
                controlsOnTop = false,
                locked = false,
                scrubProgress = -1f,
                onScrub = {},
                onScrubFinished = {},
                onToggleLock = {},
                onToggleControlsPosition = {},
                onClose = {},
                onPlayPause = {},
                onPrevious = {},
                onNext = {},
                onSpeed = {},
                onRepeat = {},
                onAspect = {},
                onInfo = {},
                onAudio = {},
                onSubtitles = {},
                onRotate = {},
                onPip = {},
            )
        }
    }
}
