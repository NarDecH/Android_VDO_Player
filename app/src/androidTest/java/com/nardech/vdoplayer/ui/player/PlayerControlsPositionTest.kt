package com.nardech.vdoplayer.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.playback.PlayerUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's control bar can be moved to the top edge, for a hand or a stand that
 * covers the bottom of the screen. That is a placement only a laid-out screen can
 * prove: the composable picks an alignment, and the buttons either land above the
 * middle of the picture or they do not. The title bar is asserted too, because it is
 * the half that has to take the edge the bar left — if both halves moved, they would
 * overlap and the close button would sit behind the seek bar.
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlsPositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theControlBarStartsAlongTheBottom() {
        compose.setContent { PlayerOverlay(controlsOnTop = false) }

        val middle = middleOfScreen()
        val play = boundsOf(R.string.player_play)
        val close = boundsOf(R.string.player_close)

        compose.onNodeWithContentDescription(label(R.string.player_play)).assertIsDisplayed()

        assertTrue(
            "the buttons are at $play, above the middle of the screen ($middle)",
            play.top > middle,
        )
        assertTrue(
            "the title bar is at $close, below the middle of the screen ($middle)",
            close.bottom < middle,
        )
    }

    @Test
    fun movingTheBarToTheTopCarriesTheTitleBarToTheBottom() {
        compose.setContent { PlayerOverlay(controlsOnTop = true) }

        val middle = middleOfScreen()
        val play = boundsOf(R.string.player_play)
        val close = boundsOf(R.string.player_close)

        assertTrue(
            "the buttons are at $play, below the middle of the screen ($middle)",
            play.bottom < middle,
        )
        assertTrue(
            "the title bar is at $close, above the middle of the screen ($middle)",
            close.top > middle,
        )
    }

    /** Half way down the screen, which is the edge both halves are compared with. */
    private fun middleOfScreen() = compose.onRoot().getBoundsInRoot().let { root ->
        root.top + (root.bottom - root.top) / 2f
    }

    private fun boundsOf(stringRes: Int) =
        compose.onNodeWithContentDescription(label(stringRes)).getUnclippedBoundsInRoot()

    /**
     * Read from the running activity, not a hard-coded word: the app ships a Thai
     * translation, and a device set to Thai describes these buttons in Thai.
     */
    private fun label(stringRes: Int): String = compose.activity.getString(stringRes)

    @Composable
    private fun PlayerOverlay(controlsOnTop: Boolean) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerControls(
                state = PlayerUiState(
                    durationMs = 90_000L,
                    positionMs = 30_000L,
                    bufferedMs = 45_000L,
                ),
                title = "Test clip",
                folder = "",
                controlsOnTop = controlsOnTop,
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
