package com.nardech.vdoplayer.transfer

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three things a hostile or careless sender could get wrong are the file name, the
 * code, and the address the user types. Each is pinned here.
 */
class TransferProtocolTest {

    @Test
    fun `codes are always four digits`() {
        val random = SecureRandom()
        repeat(1_000) {
            val code = TransferProtocol.newCode(random)
            assertEquals(4, code.length)
            assertTrue("'$code' is not all digits", code.all { it.isDigit() })
        }
    }

    @Test
    fun `file names keep their extension and lose any directory`() {
        assertEquals("clip.mp4", TransferProtocol.safeFileName("clip.mp4"))
        assertEquals("clip.mp4", TransferProtocol.safeFileName("  clip.mp4  "))
        assertEquals("clip.mp4", TransferProtocol.safeFileName("../../etc/clip.mp4"))
        assertEquals("clip.mp4", TransferProtocol.safeFileName("..\\..\\Windows\\clip.mp4"))
        assertEquals("clip.mp4", TransferProtocol.safeFileName("/sdcard/Movies/clip.mp4"))
    }

    @Test
    fun `file names keep Thai letters and drop control characters`() {
        // Thai vowel marks are combining characters: a filter that only allowed letters
        // would turn คลิป into คล_ป and the received video would be unrecognisable.
        assertEquals("คลิป วันเกิด.mp4", TransferProtocol.safeFileName("คลิป วันเกิด.mp4"))
        assertEquals("clip_.mp4", TransferProtocol.safeFileName("clip\u0000.mp4"))
        assertEquals("a_b.mp4", TransferProtocol.safeFileName("a|b.mp4"))
        assertEquals("a_b.mp4", TransferProtocol.safeFileName("a<>b.mp4"))
        assertEquals("a_b_.mp4", TransferProtocol.safeFileName("a<b>.mp4"))
        assertEquals("ค_ลิป.mp4", TransferProtocol.safeFileName("ค:ลิป.mp4"))
    }

    @Test
    fun `ordinary punctuation survives`() {
        assertEquals("song's clip, take 2.mp4", TransferProtocol.safeFileName("song's clip, take 2.mp4"))
        assertEquals("100% & more!.mp4", TransferProtocol.safeFileName("100% & more!.mp4"))
        assertEquals("report (final) [v2].pdf", TransferProtocol.safeFileName("report (final) [v2].pdf"))
    }

    @Test
    fun `file names fall back when nothing usable is left`() {
        assertEquals("received", TransferProtocol.safeFileName(null))
        assertEquals("received", TransferProtocol.safeFileName(""))
        assertEquals("received", TransferProtocol.safeFileName("   "))
        assertEquals("received", TransferProtocol.safeFileName("."))
        assertEquals("received", TransferProtocol.safeFileName(".."))
        assertEquals("received", TransferProtocol.safeFileName("/////"))
        assertEquals("movie.dat", TransferProtocol.safeFileName(null, fallback = "movie.dat"))
    }

    @Test
    fun `long file names are shortened but keep their extension`() {
        val video = TransferProtocol.safeFileName("a".repeat(400) + ".mp4")
        assertEquals(TransferProtocol.MAX_NAME_LENGTH, video.length)
        assertTrue("a truncated video must still look like a video: $video", video.endsWith(".mp4"))

        val extensionless = TransferProtocol.safeFileName("b".repeat(400))
        assertEquals(TransferProtocol.MAX_NAME_LENGTH, extensionless.length)
        assertEquals("b".repeat(120), extensionless)
    }

    @Test
    fun `duplicates get a numbered suffix before the extension`() {
        val taken = setOf("holiday.mp4", "holiday (2).mp4")
        assertEquals("holiday (3).mp4", TransferProtocol.deduplicate("holiday.mp4") { it in taken })
        assertEquals("clip.mp4", TransferProtocol.deduplicate("clip.mp4") { false })

        val withoutExtension = setOf("clip", "clip (2)")
        assertEquals("clip (3)", TransferProtocol.deduplicate("clip") { it in withoutExtension })

        val many = (1..10).map { if (it == 1) "a.mp4" else "a ($it).mp4" }.toSet()
        assertEquals("a (11).mp4", TransferProtocol.deduplicate("a.mp4") { it in many })
    }

    @Test
    fun `typed addresses become a base url`() {
        assertEquals("http://192.168.1.20:45871", TransferProtocol.baseUrlFrom("192.168.1.20"))
        assertEquals("http://192.168.1.20:45871", TransferProtocol.baseUrlFrom("  192.168.1.20  "))
        assertEquals("http://192.168.1.20:8080", TransferProtocol.baseUrlFrom("192.168.1.20:8080"))
        assertEquals("http://192.168.1.20:45871", TransferProtocol.baseUrlFrom("http://192.168.1.20"))
        assertEquals("http://192.168.1.20:45871", TransferProtocol.baseUrlFrom("http://192.168.1.20/"))
        assertEquals("http://192.168.1.20:9000", TransferProtocol.baseUrlFrom("http://192.168.1.20:9000/upload"))
        assertEquals("http://phone.local:45871", TransferProtocol.baseUrlFrom("phone.local"))
        assertEquals("http://192.168.1.20:1234", TransferProtocol.baseUrlFrom("https://192.168.1.20:1234"))
    }

    @Test
    fun `unusable addresses are rejected and bad ports fall back to the default`() {
        assertNull(TransferProtocol.baseUrlFrom(""))
        assertNull(TransferProtocol.baseUrlFrom("   "))
        assertNull(TransferProtocol.baseUrlFrom("http://"))
        assertNull(TransferProtocol.baseUrlFrom("/"))
        assertNull(TransferProtocol.baseUrlFrom("http://:45871"))
        assertEquals("http://192.168.1.20:45871", TransferProtocol.baseUrlFrom("192.168.1.20:99999"))
        assertEquals("http://192.168.1.20:45871", TransferProtocol.baseUrlFrom("192.168.1.20:0"))
        assertEquals("http://192.168.1.20:45871", TransferProtocol.baseUrlFrom("192.168.1.20:abc"))
        assertEquals("http://192.168.1.20:1234", TransferProtocol.baseUrlFrom("192.168.1.20:abc", defaultPort = 1234))
    }

    @Test
    fun `json survives quotes, slashes and thai text`() {
        val document = TransferProtocol.json(
            "name" to "คลิป \"วันเกิด\"\\C:.mp4",
            "bytes" to 1_234L,
            "ok" to true,
            "location" to null,
        )
        assertEquals("คลิป \"วันเกิด\"\\C:.mp4", TransferProtocol.jsonField(document, "name"))
        assertEquals("1234", TransferProtocol.jsonField(document, "bytes"))
        assertEquals("true", TransferProtocol.jsonField(document, "ok"))
        assertEquals("null", TransferProtocol.jsonField(document, "location"))
        assertNull(TransferProtocol.jsonField(document, "missing"))
    }

    @Test
    fun `url encoding round trips thai file names`() {
        val name = "คลิป ทดสอบ 2026.mp4"
        assertEquals(name, TransferProtocol.urlDecode(TransferProtocol.urlEncode(name)))
    }
}
