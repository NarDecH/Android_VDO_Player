package com.nardech.vdoplayer.transfer

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the receiving half over real loopback sockets, which is exactly what a phone
 * on the Wi-Fi does. No Android APIs are involved: the file sink is a temp directory.
 */
class TransferServerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val events = CopyOnWriteArrayList<TransferServer.Event>()

    private fun sink() = TempDirSink(temporary.root)

    @Test
    fun `receives a file and reports progress`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(300 * 1024)
            val response = rawRequest(
                server.port,
                uploadHead(server.code, "clip.mp4", payload.size),
                payload,
            )

            assertEquals(200, statusOf(response))
            assertTrue(response.contains("\"name\":\"clip.mp4\""))
            assertArrayEquals(payload, sink.file("clip.mp4").readBytes())
            assertEquals(listOf("clip.mp4"), sink.committed)
            assertEquals(listOf("clip.mp4" to payload.size.toLong()), sink.created)
            assertEquals(emptyList<String>(), sink.discarded)

            val progress = events.filterIsInstance<TransferServer.Event.UploadProgress>()
            assertTrue("progress must be reported", progress.isNotEmpty())
            assertEquals(payload.size.toLong(), progress.last().bytes)
            assertTrue(
                "progress must only move forward",
                progress.map { it.bytes }.zipWithNext().all { (a, b) -> a < b },
            )

            val started = events.filterIsInstance<TransferServer.Event.UploadStarted>().single()
            assertEquals("clip.mp4", started.fileName)
            assertEquals(payload.size.toLong(), started.sizeBytes)
            assertEquals("127.0.0.1", started.from)

            val finished = events.filterIsInstance<TransferServer.Event.UploadFinished>().single()
            assertEquals("clip.mp4", finished.fileName)
            assertNotNull(finished.location)
            assertTrue(events.none { it is TransferServer.Event.Failed })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `receives a file whose name is Thai`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(1_024)
            val name = "คลิป วันเกิด (2026).mp4"
            val response = rawRequest(server.port, uploadHead(server.code, name, payload.size), payload)

            assertEquals(200, statusOf(response))
            assertArrayEquals(payload, sink.file(name).readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `accepts a chunked body, which is how a sender streams a file with no known size`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(200 * 1024)
            val head = buildString {
                append("POST ${TransferProtocol.PATH_UPLOAD}?name=chunked.mp4 HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                append("${TransferProtocol.CODE_HEADER}: ${server.code}\r\n")
                append("Transfer-Encoding: chunked\r\n")
                append("\r\n")
            }
            val response = rawRequest(server.port, head, chunkedBody(payload))

            assertEquals(200, statusOf(response))
            assertTrue(response.contains("\"bytes\":${payload.size}"))
            assertArrayEquals(payload, sink.file("chunked.mp4").readBytes())

            val started = events.filterIsInstance<TransferServer.Event.UploadStarted>().single()
            assertEquals(-1L, started.sizeBytes)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `rejects a wrong code and creates nothing`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(2_048)
            val response = rawRequest(
                server.port,
                uploadHead(wrongCodeFor(server.code), "clip.mp4", payload.size),
                payload,
            )

            assertEquals(403, statusOf(response))
            assertTrue(sink.created.isEmpty())
            assertTrue(sink.namesOnDisk().isEmpty())
            assertTrue(events.any { it is TransferServer.Event.UploadRejected })
            assertTrue(events.none { it is TransferServer.Event.UploadFinished })
        } finally {
            server.stop()
        }
    }

    @Test
    fun `rotates the code after too many wrong attempts`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val wrong = wrongCodeFor(server.code)
            repeat(TransferProtocol.MAX_CODE_ATTEMPTS) {
                // No body on purpose. The server decides on the code header and closes
                // without reading the body, and a body still unread in the socket makes
                // Windows answer the close with a reset: the client then throws
                // SocketException instead of reading the 403, which is what used to fail
                // this test on the Windows runner roughly one run in a few dozen. The
                // rejection path never looks at the body, so nothing is left untested.
                rawRequest(server.port, uploadHead(wrong, "clip.mp4", 4))
            }

            assertTrue(
                "the app must notice an address being guessed at",
                events.any { it is TransferServer.Event.Failed },
            )
            assertTrue("the code must be rotated", events.any { it is TransferServer.Event.CodeRotated })
            assertTrue(sink.created.isEmpty())

            // The advertised code is the working one again, and the counter was reset.
            val payload = testBytes(8)
            assertEquals(
                200,
                statusOf(
                    rawRequest(server.port, uploadHead(server.code, "after.mp4", payload.size), payload),
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `rejects a body with no known length`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val response = rawRequest(server.port, uploadHead(server.code, "clip.mp4", length = null))

            assertEquals(411, statusOf(response))
            assertTrue(sink.created.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `answers unknown paths with 404`() {
        val server = serverWith(sink(), events).also { it.start() }
        try {
            assertEquals(404, statusOf(rawRequest(server.port, uploadHead(server.code, null, null, path = "/nope", method = "GET"))))
            assertEquals(404, statusOf(rawRequest(server.port, uploadHead(server.code, "a.mp4", 1, path = "/upload/extra"))))
            assertEquals(404, statusOf(rawRequest(server.port, uploadHead(server.code, "a.mp4", 1, method = "DELETE"))))
            assertEquals(
                404,
                statusOf(rawRequest(server.port, "GET /info/../upload?name=a.mp4 HTTP/1.1\r\n\r\n")),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `tells the sender who is receiving`() {
        val server = serverWith(sink(), events, deviceName = "NarDecH Phone", appVersion = "1.1.0")
            .also { it.start() }
        try {
            val response = rawRequest(
                server.port,
                "GET ${TransferProtocol.PATH_INFO} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
            )

            assertEquals(200, statusOf(response))
            assertTrue(response.contains("\"name\":\"NarDecH Phone\""))
            assertTrue(response.contains("\"app\":\"${TransferProtocol.APP_NAME}\""))
            assertTrue(response.contains("\"version\":\"1.1.0\""))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `drops a half sent file and keeps the receiver usable`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val head = uploadHead(server.code, "half.mp4", 4_096)
            val response = rawRequest(server.port, head, testBytes(64), halfClose = true)

            assertEquals(400, statusOf(response))
            assertEquals(listOf("half.mp4"), sink.discarded)
            assertTrue(sink.committed.isEmpty())
            assertTrue("a partial file must not stay on disk", sink.namesOnDisk().isEmpty())

            val rejection = events.filterIsInstance<TransferServer.Event.UploadRejected>().single()
            assertTrue(rejection.reason.contains("64 of 4096"))
            assertTrue(
                "a sender walking away is not an app error",
                events.none { it is TransferServer.Event.Failed },
            )

            val payload = testBytes(1_024)
            assertEquals(
                200,
                statusOf(
                    rawRequest(server.port, uploadHead(server.code, "next.mp4", payload.size), payload),
                ),
            )
            assertArrayEquals(payload, sink.file("next.mp4").readBytes())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `keeps a hostile file name inside the receive folder`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(32)
            val response = rawRequest(
                server.port,
                uploadHead(server.code, "../../../evil.mp4", payload.size),
                payload,
            )

            assertEquals(200, statusOf(response))
            assertEquals(listOf("evil.mp4"), sink.namesOnDisk())
            assertFalse(java.io.File(temporary.root.parentFile, "evil.mp4").exists())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `invents a name when the sender does not propose one`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payload = testBytes(16)
            val response = rawRequest(server.port, uploadHead(server.code, null, payload.size), payload)

            assertEquals(200, statusOf(response))
            val name = sink.created.single().first
            assertTrue("unexpected name '$name'", name.startsWith("received-"))
            assertTrue(name.endsWith(".dat"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `handles several uploads at once`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        try {
            val payloads = (1..TransferProtocol.MAX_CONCURRENT_UPLOADS).map { index ->
                "clip-$index.mp4" to testBytes(200 * 1024, seed = index.toLong())
            }
            val ready = CountDownLatch(payloads.size)
            val threads = payloads.map { (name, payload) ->
                Thread {
                    ready.countDown()
                    rawRequest(server.port, uploadHead(server.code, name, payload.size), payload)
                }.apply { isDaemon = true }
            }
            threads.forEach { it.start() }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            threads.forEach { it.join(20_000) }
            threads.forEach { assertFalse("a transfer thread is stuck", it.isAlive) }

            assertEquals(payloads.map { it.first }.sorted(), sink.namesOnDisk())
            payloads.forEach { (name, payload) ->
                assertArrayEquals(payload, sink.file(name).readBytes())
            }
            assertEquals(payloads.size, events.filterIsInstance<TransferServer.Event.UploadFinished>().size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stopping closes the port and rotating invalidates the old code`() {
        val sink = sink()
        val server = serverWith(sink, events).also { it.start() }
        val port = server.port
        assertTrue(server.isRunning)
        assertTrue(port > 0)

        val old = server.code
        var fresh = server.rotateCode()
        while (fresh == old) fresh = server.rotateCode()
        assertEquals(403, statusOf(rawRequest(port, uploadHead(old, "a.mp4", 4), testBytes(4))))
        assertEquals(200, statusOf(rawRequest(port, uploadHead(fresh, "b.mp4", 4), testBytes(4))))
        assertTrue(events.any { it is TransferServer.Event.CodeRotated })

        server.stop("test finished")
        assertFalse(server.isRunning)
        assertEquals(0, server.port)
        assertTrue(events.any { it is TransferServer.Event.Stopped })
        assertTrue(
            "the port must stop answering",
            runCatching { rawRequest(port, "GET /info HTTP/1.1\r\n\r\n") }.isFailure,
        )
        // Stopping twice is allowed and does nothing.
        server.stop()
    }

    @Test
    fun `stop waits for the accept loop instead of leaving the socket open`() {
        val server = serverWith(sink(), events).also { it.start() }

        server.stop("test finished")

        // A thread parked in accept() keeps the listening socket alive on Linux, so the
        // kernel goes on handing a closed port connections for as long as the call is
        // pending. Waiting for the loop to leave is what makes the port stop answering
        // as soon as stop() returns.
        val lingering = Thread.getAllStackTraces().keys
            .filter { thread -> thread.isAlive && thread.name == "vdo-transfer-accept" }
        assertEquals("the accept loop outlived stop()", emptyList<Thread>(), lingering)
    }

    @Test
    fun `cannot start twice and can be restarted after a stop`() {
        val sink = sink()
        val server = serverWith(sink, events)
        server.start()
        assertThrows(IllegalStateException::class.java) { server.start() }
        server.stop()

        server.start(preferredPort = 0)
        try {
            val payload = testBytes(8)
            assertEquals(
                200,
                statusOf(
                    rawRequest(server.port, uploadHead(server.code, "again.mp4", payload.size), payload),
                ),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `ignores a malformed request`() {
        val server = serverWith(sink(), events).also { it.start() }
        try {
            assertEquals(400, statusOf(rawRequest(server.port, "GARBAGE\r\n\r\n")))
            assertEquals(400, statusOf(rawRequest(server.port, "POST\r\n\r\n")))
            assertTrue(server.isRunning)
        } finally {
            server.stop()
        }
    }

    /** A four digit code that is guaranteed not to be the one the receiver is showing. */
    private fun wrongCodeFor(code: String): String = if (code == "0000") "1111" else "0000"

    private fun chunkedBody(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        // Two chunks of unequal length plus the terminating zero chunk.
        while (offset < payload.size) {
            val size = minOf(70_000, payload.size - offset)
            out.write("${size.toString(16)}\r\n".toByteArray())
            out.write(payload, offset, size)
            out.write("\r\n".toByteArray())
            offset += size
        }
        out.write("0\r\n\r\n".toByteArray())
        return out.toByteArray()
    }
}
