package com.nardech.vdoplayer.transfer

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Drives the sending half against the real receiving half, so both ends of the wire
 * contract are tested together: the same path a phone-to-phone transfer takes.
 */
class TransferClientTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val events = CopyOnWriteArrayList<TransferServer.Event>()
    private val client = TransferClient(connectTimeoutMs = 3_000, readTimeoutMs = 15_000)

    @Test
    fun `probe reports the receiving device`() {
        val sink = TempDirSink(temporary.root)
        val server = serverWith(sink, events, deviceName = "NarDecH Phone", appVersion = "1.1.0")
            .also { it.start() }
        try {
            val info = client.probe("http://127.0.0.1:${server.port}").getOrThrow()

            assertEquals("NarDecH Phone", info.name)
            assertEquals(TransferProtocol.APP_NAME, info.app)
            assertEquals("1.1.0", info.version)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `probe fails when nothing answers`() {
        val port = ServerSocket(0).use { it.localPort }

        val result = client.probe("http://127.0.0.1:$port")

        assertTrue("connecting to a closed port must fail", result.isFailure)
    }

    @Test
    fun `upload streams the file and reports progress`() {
        val sink = TempDirSink(temporary.root)
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(700 * 1024)
            val progress = mutableListOf<Pair<Long, Long>>()
            val result = client.upload(
                baseUrl = "http://127.0.0.1:${server.port}",
                code = server.code,
                fileName = "holiday คลิป.mp4",
                sizeBytes = payload.size.toLong(),
                source = ByteArrayInputStream(payload),
                onProgress = { bytes, total -> progress.add(bytes to total) },
            )

            assertTrue("upload failed: ${result.message}", result.ok)
            assertEquals(payload.size.toLong(), result.bytes)
            assertNotNull(result.location)
            assertArrayEquals(payload, sink.file("holiday คลิป.mp4").readBytes())

            assertTrue(progress.isNotEmpty())
            assertEquals(payload.size.toLong(), progress.last().first)
            assertTrue(progress.all { it.second == payload.size.toLong() })
            assertTrue(progress.map { it.first }.zipWithNext().all { (a, b) -> a < b })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `upload streams a file whose size is unknown`() {
        val sink = TempDirSink(temporary.root)
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(600 * 1024)
            val result = client.upload(
                baseUrl = "http://127.0.0.1:${server.port}",
                code = server.code,
                fileName = "unknown-size.mp4",
                sizeBytes = -1L,
                source = ByteArrayInputStream(payload),
            )

            assertTrue("upload failed: ${result.message}", result.ok)
            assertEquals(payload.size.toLong(), result.bytes)
            assertArrayEquals(payload, sink.file("unknown-size.mp4").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `upload with a wrong code leaves nothing behind`() {
        val sink = TempDirSink(temporary.root)
        val server = serverWith(sink, events).also { it.start() }
        try {
            val wrong = if (server.code == "0000") "1111" else "0000"
            val result = client.upload(
                baseUrl = "http://127.0.0.1:${server.port}",
                code = wrong,
                fileName = "clip.mp4",
                sizeBytes = 1_024L,
                source = ByteArrayInputStream(testBytes(1_024)),
            )

            assertFalse(result.ok)
            assertTrue("unhelpful message: ${result.message}", result.message!!.contains("code"))
            assertTrue(sink.created.isEmpty())
            assertTrue(events.any { it is TransferServer.Event.UploadRejected })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a cancelled upload is reported as cancelled and leaves no partial file`() {
        val sink = TempDirSink(temporary.root)
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(2 * 1024 * 1024)
            var sentSoFar = 0L
            val result = client.upload(
                baseUrl = "http://127.0.0.1:${server.port}",
                code = server.code,
                fileName = "big-movie.mp4",
                sizeBytes = payload.size.toLong(),
                source = ByteArrayInputStream(payload),
                shouldCancel = { sentSoFar >= 300 * 1024 },
                onProgress = { bytes, _ -> sentSoFar = bytes },
            )

            assertEquals("Cancelled", result.message)
            assertFalse(result.ok)
            assertTrue("some bytes should have gone out first", result.bytes > 0)

            waitFor { sink.discarded.isNotEmpty() }
            assertTrue(sink.committed.isEmpty())
            assertTrue("a cancelled transfer must not leave a file", sink.namesOnDisk().isEmpty())
            assertTrue(events.any { it is TransferServer.Event.UploadRejected })
            assertTrue(
                "cancelling is not an error: ${events.filterIsInstance<TransferServer.Event.Failed>()}",
                events.none { it is TransferServer.Event.Failed },
            )

            // The receiver is still usable afterwards.
            val next = testBytes(4_096)
            val followUp = client.upload(
                baseUrl = "http://127.0.0.1:${server.port}",
                code = server.code,
                fileName = "after-cancel.mp4",
                sizeBytes = next.size.toLong(),
                source = ByteArrayInputStream(next),
            )
            assertTrue("receiver broke: ${followUp.message}", followUp.ok)
            assertArrayEquals(next, sink.file("after-cancel.mp4").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an upload that cannot reach anything fails with a readable message`() {
        val port = ServerSocket(0).use { it.localPort }

        val result = client.upload(
            baseUrl = "http://127.0.0.1:$port",
            code = "1234",
            fileName = "clip.mp4",
            sizeBytes = 8L,
            source = ByteArrayInputStream(testBytes(8)),
        )

        assertFalse(result.ok)
        assertTrue("message was '${result.message}'", !result.message.isNullOrBlank())
    }
}
