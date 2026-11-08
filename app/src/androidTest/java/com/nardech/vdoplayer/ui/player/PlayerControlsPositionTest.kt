package com.nardech.vdoplayer.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nardech.vdoplayer.R
import com.nardech.vdoplayer.data.ControlsEdge
import com.nardech.vdoplayer.playback.PlayerUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player's control bar can be moved to any of the four edges, for a hand or a stand
 * that covers part of the screen. That is a placement only a laid-out screen can prove:
 * the composable picks an alignment, and the buttons either land on that edge or they do
 * not. The title bar is asserted too, because it is the half that has to take a different
 * edge — if both halves moved together, they would overlap and the close button would sit
 * behind the seek bar.
 *
 * Both axes are compared with the middle of the screen rather than with a hard-coded
 * coordinate, so the test holds on any size of device.
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlsPositionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theControlBarStartsAlongTheBottom() {
        compose.setContent { PlayerOverlay(ControlsEdge.BOTTOM) }

        val middle = middleY()
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
        compose.setContent { PlayerOverlay(ControlsEdge.TOP) }

        val middle = middleY()
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

    @Test
    fun theBarStandsAlongTheLeftEdge() {
        compose.setContent { PlayerOverlay(ControlsEdge.LEFT) }

        val middleX = middleX()
        val middleY = middleY()
        val play = boundsOf(R.string.player_play)
        val close = boundsOf(R.string.player_close)

        assertTrue(
            "the buttons are at $play, not in the left half of the screen ($middleX)",
            centreX(play) < middleX,
        )
        assertTrue(
            "the title bar is at $close, not above the panel ($middleY)",
            close.bottom < middleY,
        )
    }

    @Test
    fun theBarStandsAlongTheRightEdge() {
        compose.setContent { PlayerOverlay(ControlsEdge.RIGHT) }

        val middleX = middleX()
        val middleY = middleY()
        val play = boundsOf(R.string.player_play)
        val close = boundsOf(R.string.player_close)

        assertTrue(
            "the buttons are at $play, not in the right half of the screen ($middleX)",
            centreX(play) > middleX,
        )
        assertTrue(
            "the title bar is at $close, not above the panel ($middleY)",
            close.bottom < middleY,
        )
    }

    /** Half way across the screen, which is what a side edge is compared with. */
    private fun middleX(): Dp = compose.onRoot().getBoundsInRoot().let { root ->
        root.left + (root.right - root.left) / 2f
    }

    /** Half way down the screen, which is what a top or bottom edge is compared with. */
    private fun middleY(): Dp = compose.onRoot().getBoundsInRoot().let { root ->
        root.top + (root.bottom - root.top) / 2f
    }

    private fun centreX(bounds: DpRect): Dp =
        bounds.left + (bounds.right - bounds.left) / 2f

    private fun boundsOf(stringRes: Int) =
        compose.onNodeWithContentDescription(label(stringRes)).getUnclippedBoundsInRoot()

    /**
     * Read from the running activity, not a hard-coded word: the app ships a Thai
     * translation, and a device set to Thai describes these buttons in Thai.
     */
    private fun label(stringRes: Int): String = compose.activity.getString(stringRes)

    @Composable
    private fun PlayerOverlay(edge: ControlsEdge) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerControls(
                state = PlayerUiState(
                    durationMs = 90_000L,
                    positionMs = 30_000L,
                    bufferedMs = 45_000L,
                ),
                title = "Test clip",
                folder = "",
                edge = edge,
                locked = false,
                scrubProgress = -1f,
                onScrub = {},
                onScrubFinished = {},
                onToggleLock = {},
                onChangeControlsEdge = {},
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
