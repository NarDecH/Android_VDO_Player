package com.nardech.vdoplayer.playback

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The [Surface] the player renders into during the smoke test, and the frame counter.
 *
 * A bare `SurfaceTexture` can only ever count one frame. Nothing consumes its
 * buffers, so its queue fills after the first frame and the decoder blocks forever
 * waiting for a free slot — the player then looks stopped no matter how healthy the
 * decoder is, which is exactly what the first CI run of this test reported
 * ("expected at least 2 rendered frame(s), saw 1"). The two tests that only needed
 * the first frame passed, and the switch test timed out on the second one.
 *
 * A frame only frees its buffer when someone calls `updateTexImage()`, and that call
 * needs a GL context, so this is a miniature version of what a `TextureView` does
 * internally: an EGL context on its own thread, an external OES texture, the
 * `SurfaceTexture` attached to it, and a listener that consumes every frame that
 * arrives. The counter then rises for as long as the player keeps producing frames,
 * which is the thing the test is actually about.
 */
internal class CountingVideoSurface : AutoCloseable {

    private val frames = AtomicInteger(0)
    private val thread = HandlerThread("counting-video-surface").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var config: EGLConfig? = null
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture: SurfaceTexture? = null

    /** What the player draws into; a real consumer is parked on the other side. */
    lateinit var surface: Surface
        private set

    init {
        val failure = runOnGlThread { create() }
        failure?.let { throw AssertionError("could not build a counting video surface", it) }
    }

    fun frameCount(): Int = frames.get()

    override fun close() {
        runOnGlThread { dispose() }
        thread.quitSafely()
        thread.join(TimeUnit.SECONDS.toMillis(5))
    }

    private fun create() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        // A pbuffer is only here so the context has somewhere to be current; nothing
        // is ever drawn into it. The frames arrive through the SurfaceTexture.
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val configAttributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(
            EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0) &&
                configCount[0] > 0,
        ) { "no EGL config with a pbuffer surface" }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        check(textures[0] != 0) { "glGenTextures failed" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])

        val surfaceTexture = SurfaceTexture(textures[0])
        texture = surfaceTexture
        surfaceTexture.setOnFrameAvailableListener({ consumeFrame() }, handler)
        surface = Surface(surfaceTexture)
    }

    /**
     * Called on the GL thread for every frame the player queues. Consuming the frame
     * is what releases the buffer for the next one, so the decoder is never left
     * waiting for the queue to drain.
     */
    private fun consumeFrame() {
        val surfaceTexture = texture ?: return
        try {
            surfaceTexture.updateTexImage()
        } catch (expected: IllegalStateException) {
            // A callback that outlived close(): there is nothing to consume anymore.
        } finally {
            frames.incrementAndGet()
        }
    }

    private fun dispose() {
        val surfaceTexture = texture
        if (::surface.isInitialized) surface.release()
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        texture = null

        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        pbuffer = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    /** Runs [block] on the GL thread and rethrows whatever it threw. */
    private fun runOnGlThread(block: () -> Unit): Throwable? {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                block()
            } catch (thrown: Throwable) {
                failure = thrown
            } finally {
                done.countDown()
            }
        }
        check(done.await(10, TimeUnit.SECONDS)) { "the GL thread did not answer" }
        return failure
    }
}
