package com.nardech.vdoplayer.playback

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nardech.vdoplayer.data.DecoderPreference
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playback on a real Android runtime, which is what the JVM tests next door cannot
 * reach: [PlayerFactory] builds the same player the app builds, the clip is decoded
 * by the platform decoder or by the bundled FFmpeg libraries, and the test insists on
 * frames arriving at a real [Surface] — a player that reports `READY` without ever
 * producing a picture would fail here.
 *
 * The clip is `app/src/androidTest/assets/smoke.mp4`: 2 seconds of 160x120 H.264
 * baseline, video only, ~25 KB, generated once with
 * `ffmpeg -f lavfi -i testsrc2=size=160x120:rate=10:duration=2 -c:v libx264
 *  -profile:v baseline -pix_fmt yuv420p -g 10 -movflags +faststart -an smoke.mp4`
 * and committed so that a runner needs no encoder.
 *
 * These run on an emulator in CI (`.github/workflows/ci.yml`), x86_64, so the FFmpeg
 * case also proves the four-ABI `.so` packaging actually loads on a device.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun platformDecodingRendersFrames() {
        withPlayingClip(DecoderPreference.PLATFORM_FIRST) { clip ->
            val frames = clip.awaitFrames(1)
            assertTrue("the platform decoder rendered no frames", frames >= 1)
            assertEquals(
                "the clip should be decoded as H.264",
                MimeTypes.VIDEO_H264,
                clip.videoSampleMimeType(),
            )
            assertNull(clip.playbackError())
        }
    }

    @Test
    fun bundledFfmpegDecodesTheClip() {
        withPlayingClip(DecoderPreference.FFMPEG_FIRST) { clip ->
            val frames = clip.awaitFrames(1)
            assertTrue("the bundled FFmpeg decoder rendered no frames", frames >= 1)
            assertEquals(
                MimeTypes.VIDEO_H264,
                clip.videoSampleMimeType(),
            )
            assertNull(
                "the FFmpeg renderers should decode this clip without an error",
                clip.playbackError(),
            )
        }
    }

    /**
     * The feature the 1.2.0 notes advertise: the stream panel switches decoding while
     * the video keeps playing. Frames have to keep arriving after each switch, in both
     * directions, or the switch really did stop the picture.
     */
    @Test
    fun switchingDecodersKeepsFramesFlowing() {
        withPlayingClip(DecoderPreference.PLATFORM_FIRST) { clip ->
            clip.awaitFrames(1)

            val beforeFfmpeg = clip.frameCount()
            assertNotNull(
                "NextLib refused the live switch to the FFmpeg decoder",
                clip.apply(DecoderPreference.FFMPEG_FIRST),
            )
            clip.awaitFrames(beforeFfmpeg + 1)

            val beforePlatform = clip.frameCount()
            assertNotNull(
                "NextLib refused the live switch back to the platform decoder",
                clip.apply(DecoderPreference.PLATFORM_ONLY),
            )
            clip.awaitFrames(beforePlatform + 1)

            assertNull(clip.playbackError())
        }
    }

    /**
     * Runs [block] with a clip already playing through [preference] and shuts the
     * player down afterwards. The ExoPlayer is built and touched on the main thread
     * only: the instrumentation thread has no Looper, and every Media3 call has to
     * happen on the thread the player was created on.
     */
    private fun withPlayingClip(
        preference: DecoderPreference,
        block: (PlayingClip) -> Unit,
    ) {
        val clip = PlayingClip(preference)
        try {
            clip.start()
            block(clip)
        } finally {
            clip.close()
        }
    }

    private inner class PlayingClip(private val preference: DecoderPreference) {

        private val frames = AtomicInteger(0)
        private val playbackError = AtomicReference<PlaybackException?>(null)
        private var engine: PlaybackEngine? = null
        private var surface: Surface? = null
        private var texture: SurfaceTexture? = null

        fun start() = onMain {
            val built = PlayerFactory.build(context, preference)
            engine = built

            // A SurfaceTexture is what a SurfaceView hands the player in the app, so
            // the frames counted here travelled the real output path.
            val surfaceTexture = SurfaceTexture(0).apply {
                setOnFrameAvailableListener { frames.incrementAndGet() }
            }
            texture = surfaceTexture
            val output = Surface(surfaceTexture)
            surface = output

            built.player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                }
            })
            built.player.setVideoSurface(output)
            // The clip is two seconds long; looping keeps frames coming while the
            // decoder is swapped underneath a running player.
            built.player.repeatMode = Player.REPEAT_MODE_ONE
            built.player.setMediaItem(MediaItem.fromUri(CLIP))
            built.player.prepare()
            built.player.play()
        }

        fun frameCount(): Int = frames.get()

        fun playbackError(): PlaybackException? = playbackError.get()

        fun videoSampleMimeType(): String? =
            onMain { engine?.player?.videoFormat?.sampleMimeType }

        fun apply(target: DecoderPreference): String? = onMain {
            engine?.applyDecoderPreference(target)
        }

        /** Waits for [atLeast] rendered frames, and fails with the player error if one shows up. */
        fun awaitFrames(atLeast: Int, seconds: Long = FRAME_TIMEOUT_SECONDS): Int {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                playbackError.get()?.let { error ->
                    throw AssertionError("the player failed before rendering a frame", error)
                }
                if (frames.get() >= atLeast) return frames.get()
                Thread.sleep(POLL_MILLIS)
            }
            return frames.get().also { rendered ->
                throw AssertionError(
                    "expected at least $atLeast rendered frame(s) within ${seconds}s, " +
                        "saw $rendered (playback error: ${playbackError.get()})",
                )
            }
        }

        fun close() = onMain {
            engine?.release()
            engine = null
            surface?.release()
            surface = null
            texture?.release()
            texture = null
        }

        private fun <T> onMain(block: () -> T): T {
            val result = AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(block()) }
            return result.get()
        }
    }

    private val context get() = instrumentation.targetContext

    private companion object {
        const val CLIP = "asset:///smoke.mp4"
        const val FRAME_TIMEOUT_SECONDS = 40L
        const val POLL_MILLIS = 50L
    }
}
