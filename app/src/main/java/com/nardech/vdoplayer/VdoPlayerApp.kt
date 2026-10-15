package com.nardech.vdoplayer

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.nardech.vdoplayer.data.PlayHistory
import com.nardech.vdoplayer.data.SettingsRepository
import com.nardech.vdoplayer.data.VideoLibrary
import com.nardech.vdoplayer.logging.CrashGuard
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import com.nardech.vdoplayer.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Manual dependency container: one place that knows how to build the singletons. */
class AppContainer(application: Application) {
    val settings = SettingsRepository(application)
    val library = VideoLibrary(application)
    val history = PlayHistory(application)
}

class VdoPlayerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        LogBus.install(this)
        CrashGuard.install(this)
        LogBus.i(
            LogTag.APP,
            "VDO Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) starting — " +
                "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}), ABI ${Build.SUPPORTED_ABIS.firstOrNull()}",
        )

        container = AppContainer(this)

        // Thumbnails for videos come from Coil's video frame decoder.
        imageLoader = ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()

        // The playback service is a separate component and cannot read DataStore
        // synchronously, so the decoder preference is mirrored into a plain
        // SharedPreferences file it can read in onCreate.
        applicationScope.launch {
            container.settings.settings.collectLatest { settings ->
                LogBus.fileLevel = settings.logLevel
                getSharedPreferences(PlaybackService.PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(PlaybackService.KEY_DECODER, settings.decoderPreference.name)
                    .apply()
            }
        }
    }

    companion object {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        lateinit var imageLoader: ImageLoader
            private set
    }
}
