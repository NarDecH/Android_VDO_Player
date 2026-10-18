package com.nardech.vdoplayer.playback

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nardech.vdoplayer.data.DecoderPreference
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playback on a real Android runtime, which is what the JVM tests next door cannot
 * reach: [PlayerFactory] builds the same player the app builds, the clip is decoded
 * by the platform decoder or by the bundled FFmpeg libraries, and the test insists on
 * frames arriving at a real [android.view.Surface] — a player that reports `READY`
 * without ever producing a picture would fail here. [CountingVideoSurface] is the
 * consumer on the other side of that surface; it is what makes "frames keep arriving"
 * an observable fact rather than a single first frame.
 *
 * Two clips are packaged in `app/src/androidTest/assets` and committed, so a runner
 * needs no encoder of its own:
 *
 * * `smoke.mp4` — 2 seconds of 160x120 H.264 baseline, video only, ~25 KB:
 *   `ffmpeg -f lavfi -i testsrc2=size=160x120:rate=10:duration=2 -c:v libx264
 *   -profile:v baseline -pix_fmt yuv420p -g 10 -movflags +faststart -an smoke.mp4`
 * * `ac3.mkv` — the same picture with an AC-3 stereo track, ~50 KB, and the only
 *   clip here whose *audio* the platform cannot decode:
 *   `ffmpeg -f lavfi -i testsrc2=size=160x120:rate=10:duration=2 -f lavfi
 *   -i sine=frequency=440:sample_rate=48000:duration=2 -c:v libx264 -profile:v
 *   baseline -pix_fmt yuv420p -g 10 -c:a ac3 -b:a 96k -ac 2 -shortest
 *   -f matroska ac3.mkv`
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
            clip.awaitFrames(STREAM_FRAMES)
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
            clip.awaitFrames(STREAM_FRAMES)
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
     * TEMPORARY PROBE - not for merging. Makes the emulator job fail on purpose so the
     * failure path (diagnostics files, report upload, summary annotation) can be seen
     * working once. Delete this test and the branch it lives on.
     */
    @Test
    fun probeTheFailurePath() {
        fail("temporary probe: the emulator job was supposed to fail here")
    }

    /**
     * The case the whole FFmpeg extension exists for, and the one the JVM tests can
     * only describe: `docs/RESEARCH.md` opens with it — an MKV whose video is fine but
     * whose audio is AC-3 plays as *silent* video through the platform renderers.
     *
     * The picture in this clip is H.264, which the platform decodes without help, so
     * the audio track is what separates the two decoder sets: a track is only treated
     * as playing when a renderer reports it supports the format, and on an image with
     * no AC-3 decoder the only renderer that can say so is NextLib's FFmpeg one.
     *
     * The premise is checked instead of assumed. If an image ever ships a platform
     * AC-3 decoder there is no gap left to demonstrate on it, and the test says so by
     * skipping rather than claiming a proof it did not make.
     */
    @Test
    fun bundledFfmpegPlaysAc3AudioThePlatformCannot() {
        assumeTrue(
            "this image decodes AC-3 itself, so it cannot show what FFmpeg adds",
            !platformCanDecode(MimeTypes.AUDIO_AC3),
        )

        withPlayingClip(DecoderPreference.FFMPEG_FIRST, AC3_CLIP_NAME) { clip ->
            clip.awaitFrames(STREAM_FRAMES)

            assertEquals(
                "the AC-3 track should be the one being decoded",
                MimeTypes.AUDIO_AC3,
                clip.selectedAudioMimeType(),
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
            clip.awaitFrames(STREAM_FRAMES)

            val beforeFfmpeg = clip.frameCount()
            assertNotNull(
                "NextLib refused the live switch to the FFmpeg decoder",
                clip.apply(DecoderPreference.FFMPEG_FIRST),
            )
            // A switch that produces one frame and stalls would freeze the picture just
            // as badly as one that produces none, so the stream has to come back.
            clip.awaitFrames(beforeFfmpeg + STREAM_FRAMES)

            val beforePlatform = clip.frameCount()
            assertNotNull(
                "NextLib refused the live switch back to the platform decoder",
                clip.apply(DecoderPreference.PLATFORM_ONLY),
            )
            clip.awaitFrames(beforePlatform + STREAM_FRAMES)

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
        clipName: String = DEFAULT_CLIP_NAME,
        block: (PlayingClip) -> Unit,
    ) {
        val clip = PlayingClip(preference, clipName)
        try {
            clip.start()
            block(clip)
        } finally {
            clip.close()
        }
    }

    private inner class PlayingClip(
        private val preference: DecoderPreference,
        private val clipName: String,
    ) {

        private val playbackError = AtomicReference<PlaybackException?>(null)
        private var engine: PlaybackEngine? = null
        private var output: CountingVideoSurface? = null

        fun start() = onMain {
            val built = PlayerFactory.build(context, preference)
            engine = built

            // A counting surface is what a SurfaceView is to the app: the frames have
            // to be decoded, rendered into a real output and consumed before they are
            // counted, so a player that never draws anything cannot pass this.
            val videoOutput = CountingVideoSurface()
            output = videoOutput

            built.player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackError.set(error)
                }
            })
            built.player.setVideoSurface(videoOutput.surface)
            // The clip is two seconds long; looping keeps frames coming while the
            // decoder is swapped underneath a running player.
            built.player.repeatMode = Player.REPEAT_MODE_ONE
            built.player.setMediaItem(MediaItem.fromUri(localClip(context, clipName)))
            built.player.prepare()
            built.player.play()
        }

        fun frameCount(): Int = output?.frameCount() ?: 0

        fun playbackError(): PlaybackException? = playbackError.get()

        fun videoSampleMimeType(): String? =
            onMain { engine?.player?.videoFormat?.sampleMimeType }

        /**
         * The mime type of the audio track that is actually playing, or `null` when no
         * renderer could take it — which is what a platform-only build answers for
         * AC-3, and why this is the assertion the FFmpeg case turns on.
         */
        fun selectedAudioMimeType(): String? = onMain {
            engine?.player?.currentTracks?.groups
                ?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                ?.let { group ->
                    (0 until group.length)
                        .firstOrNull { group.isTrackSelected(it) }
                        ?.let { index -> group.getTrackFormat(index).sampleMimeType }
                }
        }

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
                if (frameCount() >= atLeast) return frameCount()
                Thread.sleep(POLL_MILLIS)
            }
            return frameCount().also { rendered ->
                throw AssertionError(
                    "expected at least $atLeast rendered frame(s) within ${seconds}s, " +
                        "saw $rendered (playback error: ${playbackError.get()})",
                )
            }
        }

        fun close() = onMain {
            engine?.release()
            engine = null
            output?.close()
            output = null
        }

        private fun <T> onMain(block: () -> T): T {
            val result = AtomicReference<T>()
            instrumentation.runOnMainSync { result.set(block()) }
            return result.get()
        }
    }

    private val context get() = instrumentation.targetContext

    /**
     * The clip is packaged in the *test* APK's assets, and the player is built with the
     * app's context, which cannot see them — `asset:///smoke.mp4` resolves inside the
     * app under test and would fail with "Unable to open content". Copying the file into
     * the app's cache first keeps the player reading an ordinary local file, the same
     * path a video already on the phone takes.
     */
    private fun localClip(context: Context, clipName: String): Uri {
        val target = File(context.cacheDir, clipName)
        instrumentation.context.assets.open(clipName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(target)
    }

    /**
     * Whether anything in the platform — vendor codecs included — decodes [mimeType].
     * This is the question the FFmpeg cases answer with "no", and it is asked of the
     * device rather than hardcoded, because the answer is a property of the image.
     */
    private fun platformCanDecode(mimeType: String): Boolean {
        val format = if (mimeType.startsWith("audio/")) {
            MediaFormat.createAudioFormat(mimeType, /* sampleRate = */ 48_000, /* channelCount = */ 2)
        } else {
            MediaFormat.createVideoFormat(mimeType, /* width = */ 160, /* height = */ 120)
        }
        return MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format) != null
    }

    private companion object {
        const val DEFAULT_CLIP_NAME = "smoke.mp4"

        /** The clip whose AC-3 audio only FFmpeg can decode (see the class comment). */
        const val AC3_CLIP_NAME = "ac3.mkv"

        /**
         * How many frames count as "the picture is running". The clip plays at 10
         * frames per second, so three frames are a third of a second of video: enough
         * to prove a stream without turning a slow emulator into a flaky failure.
         */
        const val STREAM_FRAMES = 3
        const val FRAME_TIMEOUT_SECONDS = 40L
        const val POLL_MILLIS = 50L
    }
}
