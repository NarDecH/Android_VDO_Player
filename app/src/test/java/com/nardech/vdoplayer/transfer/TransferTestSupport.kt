package com.nardech.vdoplayer.transfer

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives files into a plain directory, so the whole protocol runs on the JVM without
 * MediaStore. It mirrors what [MediaStoreReceivedFileSink] promises: nothing is kept
 * unless [ReceivedFileSink.Destination.commit] is called.
 */
class TempDirSink(private val directory: File) : ReceivedFileSink {

    val created = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    val committed = Collections.synchronizedList(mutableListOf<String>())
    val discarded = Collections.synchronizedList(mutableListOf<String>())

    override fun create(fileName: String, sizeBytes: Long): ReceivedFileSink.Destination {
        created.add(fileName to sizeBytes)
        val target = File(directory, fileName)
        val out = BufferedOutputStream(FileOutputStream(target))
        return object : ReceivedFileSink.Destination {
            override val stream: OutputStream = out

            override fun commit(): String? {
                runCatching { out.close() }
                committed.add(target.name)
                return target.absolutePath
            }

            override fun discard() {
                runCatching { out.close() }
                runCatching { target.delete() }
                discarded.add(target.name)
            }
        }
    }

    fun file(name: String): File = File(directory, name)

    fun exists(name: String): Boolean = file(name).exists()

    /** Names currently on disk, so a leak outside the directory is easy to spot. */
    fun namesOnDisk(): List<String> = directory.list()?.sorted().orEmpty()
}

/** Bytes that differ throughout, so a mixing-up or truncation shows up as a mismatch. */
fun testBytes(size: Int, seed: Long = 42L): ByteArray = ByteArray(size).also { bytes ->
    Random(seed).nextBytes(bytes)
}

fun serverWith(
    sink: ReceivedFileSink,
    events: MutableList<TransferServer.Event> = CopyOnWriteArrayList(),
    deviceName: String = "Test Phone",
    appVersion: String = "1.1.0",
): TransferServer = TransferServer(
    deviceName = deviceName,
    appVersion = appVersion,
    sink = sink,
    onEvent = { events.add(it) },
)

/**
 * Sends a hand written HTTP request and returns the raw response. Going under
 * [TransferClient] is what lets the tests pin the exact wire contract (`curl` sees the
 * same thing from a phone on the network).
 */
fun rawRequest(
    port: Int,
    head: String,
    body: ByteArray? = null,
    halfClose: Boolean = false,
): String {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 15_000
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            body?.let { write(it) }
            flush()
        }
        // Half closing is how a cancelled or crashed sender looks to the receiver.
        if (halfClose) runCatching { socket.shutdownOutput() }
        return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}

/** Polls until the receiver, which answers on its own thread, has caught up. */
fun waitFor(timeoutMs: Long = 10_000, what: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(20)
    }
    throw AssertionError("Timed out after ${timeoutMs}ms waiting for $what")
}

fun statusOf(response: String): Int =
    Regex("^HTTP/1\\.1 (\\d{3})").find(response)?.groupValues?.get(1)?.toInt()
        ?: throw AssertionError("No status line in '${response.take(200)}'")

fun uploadHead(
    code: String,
    name: String?,
    length: Int?,
    path: String = TransferProtocol.PATH_UPLOAD,
    method: String = "POST",
): String = buildString {
    val query = name
        ?.let { "?${TransferProtocol.NAME_PARAM}=${TransferProtocol.urlEncode(it)}" }
        .orEmpty()
    append("$method $path$query HTTP/1.1\r\n")
    append("Host: 127.0.0.1\r\n")
    append("${TransferProtocol.CODE_HEADER}: $code\r\n")
    append("Content-Type: application/octet-stream\r\n")
    if (length != null) append("Content-Length: $length\r\n")
    append("Connection: close\r\n")
    append("\r\n")
}
