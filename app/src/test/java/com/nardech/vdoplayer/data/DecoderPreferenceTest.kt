package com.nardech.vdoplayer.data

import androidx.media3.exoplayer.DefaultRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One table decides what each decoder setting means, and both the player and the
 * live switch read it. A silent change here would change what people's videos do,
 * so every entry is spelled out.
 */
class DecoderPreferenceTest {

    @Test
    fun `hardware first keeps FFmpeg as the fallback`() {
        val preference = DecoderPreference.PLATFORM_FIRST

        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            preference.extensionMode,
        )
        assertEquals(DecoderMode.AUTO, preference.videoMode)
        assertEquals(DecoderMode.AUTO, preference.audioMode)
    }

    @Test
    fun `prefer FFmpeg decodes with FFmpeg`() {
        val preference = DecoderPreference.FFMPEG_FIRST

        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            preference.extensionMode,
        )
        assertEquals(DecoderMode.FFMPEG, preference.videoMode)
        assertEquals(DecoderMode.FFMPEG, preference.audioMode)
    }

    @Test
    fun `hardware only restricts the selection to hardware decoders`() {
        val preference = DecoderPreference.PLATFORM_ONLY

        assertEquals(DecoderMode.HARDWARE, preference.videoMode)
        assertEquals(DecoderMode.HARDWARE, preference.audioMode)
        // The FFmpeg renderers stay built even here: they are fixed at player build
        // time, and without them the live switch could never reach FFmpeg.
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            preference.extensionMode,
        )
    }

    @Test
    fun `names survive a round trip through the settings store`() {
        DecoderPreference.entries.forEach { preference ->
            assertEquals(preference, DecoderPreference.fromName(preference.name))
        }
        assertNull("an empty store means the default", DecoderPreference.fromName(null))
        assertNull("a value from a future version is ignored", DecoderPreference.fromName("QUANTUM"))
    }
}
