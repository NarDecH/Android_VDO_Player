package com.nardech.vdoplayer.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player is a full-screen overlay outside the app's `Scaffold`, so the snackbar
 * host there is not composed while a video is open. Until now the player had none of
 * its own, and every announcement — repeat mode, decoder switch — queued forever
 * without a single pixel changing. That is a failure only a device can show, hence
 * this test: a message handed to [PlayerSnackbarHost] has to reach the screen, above
 * the control bar, and above the stream panel when that is open.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSnackbarHostTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun announcementReachesTheScreenAboveTheControlBar() {
        val hostState = SnackbarHostState()
        compose.setContent {
            PlayerOverlay(hostState, showInfo = false, message = ANNOUNCEMENT)
        }

        compose.onNodeWithText(ANNOUNCEMENT).assertIsDisplayed()

        val screen = compose.onRoot().getBoundsInRoot()
        val snackbar = compose.onNodeWithText(ANNOUNCEMENT).getUnclippedBoundsInRoot()
        assertTrue(
            "the snackbar sits at $snackbar, on top of the control bar in $screen",
            snackbar.bottom < screen.bottom - 100.dp,
        )
    }

    @Test
    fun announcementMovesUpWhileTheStreamPanelIsOpen() {
        val hostState = SnackbarHostState()
        compose.setContent {
            PlayerOverlay(hostState, showInfo = true, message = ANNOUNCEMENT)
        }

        compose.onNodeWithText(ANNOUNCEMENT).assertIsDisplayed()

        val screen = compose.onRoot().getBoundsInRoot()
        val snackbar = compose.onNodeWithText(ANNOUNCEMENT).getUnclippedBoundsInRoot()
        assertTrue(
            "the snackbar sits at $snackbar, inside the stream panel in $screen",
            snackbar.bottom < screen.bottom - 250.dp,
        )
    }

    /** The bottom half of the player: the host plus something to announce. */
    @Composable
    private fun PlayerOverlay(
        hostState: SnackbarHostState,
        showInfo: Boolean,
        message: String,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerSnackbarHost(
                hostState = hostState,
                showInfo = showInfo,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            LaunchedEffect(Unit) { hostState.showSnackbar(message) }
        }
    }

    private companion object {
        const val ANNOUNCEMENT = "Decoder: FFmpeg"
    }
}
