package com.nardech.vdoplayer.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Back inside the player used to finish the Activity: nothing in the overlay handled the
 * press, so it fell through to `MainActivity` and leaving a video also closed the app.
 * These tests press the button the system presses — the Activity's dispatcher, which is
 * what `BackHandler` registers with — and watch what a host activity does with it.
 *
 * The host here is two Texts, not `PlayerScreen`: the screen needs a connected playback
 * service and a media file, and neither is what this failure was about. The handler and
 * the order it closes things in is.
 */
@RunWith(AndroidJUnit4::class)
class PlayerBackHandlerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backWithNothingOpenLeavesTheOverlayWithoutFinishingTheActivity() {
        compose.setContent {
            var playing by remember { mutableStateOf(true) }
            if (playing) {
                PlayerBackHandler(onDismiss = { false }, onClose = { playing = false })
                Text(OVERLAY)
            } else {
                Text(LIBRARY)
            }
        }

        compose.onNodeWithText(OVERLAY).assertIsDisplayed()
        pressBack()

        compose.onNodeWithText(LIBRARY).assertIsDisplayed()
        assertFalse("the Activity finished under the player", compose.activity.isFinishing)
    }

    @Test
    fun backClosesOneThingPerPressBeforeLeavingTheOverlay() {
        compose.setContent {
            var playing by remember { mutableStateOf(true) }
            var panelOpen by remember { mutableStateOf(true) }
            if (playing) {
                PlayerBackHandler(
                    onDismiss = {
                        if (panelOpen) {
                            panelOpen = false
                            true
                        } else {
                            false
                        }
                    },
                    onClose = { playing = false },
                )
                Text(if (panelOpen) PANEL else OVERLAY)
            } else {
                Text(LIBRARY)
            }
        }

        compose.onNodeWithText(PANEL).assertIsDisplayed()

        // The panel takes the first press: the overlay stays, so the video keeps playing.
        pressBack()
        compose.onNodeWithText(OVERLAY).assertIsDisplayed()

        // With nothing left to close, the same press leaves for the list.
        pressBack()
        compose.onNodeWithText(LIBRARY).assertIsDisplayed()
        assertFalse("the Activity finished instead of leaving the overlay", compose.activity.isFinishing)
    }

    /**
     * Exactly the call the system makes for a back press. `Espresso.pressBack()` would
     * only add a dependency around this same dispatcher call.
     */
    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private companion object {
        const val OVERLAY = "overlay"
        const val PANEL = "panel"
        const val LIBRARY = "library"
    }
}
