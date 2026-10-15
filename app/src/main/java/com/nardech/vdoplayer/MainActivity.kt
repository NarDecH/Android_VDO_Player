package com.nardech.vdoplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.ui.AppRoot
import com.nardech.vdoplayer.ui.theme.VdoPlayerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogBus.install(this)
        enableEdgeToEdge()

        val app = application as VdoPlayerApp
        val container = app.container
        val sharedMedia = extractSharedMedia(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        LogBus.i(
            LogTag.UI,
            "MainActivity created (restored=${savedInstanceState != null}, sharedMedia=$sharedMedia)",
        )

        setContent {
            val settings by container.settings.settings
                .collectAsStateWithLifecycle(initialValue = com.nardech.vdoplayer.data.AppSettings())

            VdoPlayerTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppRoot(
                    container = container,
                    settings = settings,
                    sharedMediaUri = sharedMedia,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LogBus.d(LogTag.UI, "MainActivity resumed")
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        LogBus.i(LogTag.UI, "PiP mode changed: inPip=$isInPictureInPictureMode")
    }

    /** Reads a video handed over by another app (share sheet or "open with"). */
    private fun extractSharedMedia(intent: Intent?): String? {
        if (intent == null) return null
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            LogBus.i(LogTag.UI, "Received external media: $uri (action=${intent.action})")
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        return uri?.toString()
    }
}
