package com.nardech.vdoplayer.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The receiving half of a Wi-Fi transfer: a tiny HTTP/1.1 server that streams one
 * file per request into a [ReceivedFileSink].
 *
 * Everything Android-specific lives behind the sink and the event callback, so the
 * whole protocol (ports, code check, name sanitising, progress, cancellation) is
 * exercised by JVM unit tests and by `curl` against a running phone.
 */
class TransferServer(
    private val deviceName: String,
    private val appVersion: String,
    private val sink: ReceivedFileSink,
    private val onEvent: (Event) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {

    sealed interface Event {
        data class Started(val port: Int, val code: String) : Event
        data class Stopped(val reason: String) : Event
        data class UploadStarted(val fileName: String, val sizeBytes: Long, val from: String) : Event
        data class UploadProgress(val fileName: String, val bytes: Long, val total: Long) : Event
        data class UploadFinished(val fileName: String, val bytes: Long, val location: String?) : Event
        data class UploadRejected(val fileName: String?, val reason: String, val from: String) : Event
        data class CodeRotated(val code: String) : Event
        data class Failed(val message: String, val error: Throwable? = null) : Event
    }

    private val running = AtomicBoolean(false)
    private val failedAttempts = AtomicInteger(0)
    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null

    @Volatile
    private var currentCode: String = TransferProtocol.newCode(random)

    /** Port the socket is bound to, or 0 when stopped. */
    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    val code: String get() = currentCode

    /**
     * Binds the socket and starts accepting. Returns the bound port; the caller shows
     * it to the sender. Falls back to an ephemeral port when [preferredPort] is taken.
     */
    @Synchronized
    fun start(preferredPort: Int = TransferProtocol.DEFAULT_PORT): Int {
        check(!running.get()) { "TransferServer is already running" }
        currentCode = TransferProtocol.newCode(random)
        failedAttempts.set(0)

        val bound = bind(preferredPort)
        serverSocket = bound
        port = bound.localPort
        running.set(true)

        pool = Executors.newFixedThreadPool(TransferProtocol.MAX_CONCURRENT_UPLOADS) { runnable ->
            Thread(runnable, "vdo-transfer-worker").apply { isDaemon = true }
        }
        acceptThread = Thread({ acceptLoop(bound) }, "vdo-transfer-accept").apply {
            isDaemon = true
            start()
        }
        onEvent(Event.Started(port, currentCode))
        return port
    }

    private fun bind(preferredPort: Int): ServerSocket {
        val preferred = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        }
        return preferred.getOrElse { error ->
            onEvent(
                Event.Failed(
                    "Port $preferredPort is unavailable, using a random port instead",
                    error,
                ),
            )
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
    }

    @Synchronized
    fun stop(reason: String = "stopped") {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(activeSockets) {
            activeSockets.forEach { socket -> runCatching { socket.close() } }
            activeSockets.clear()
        }
        pool?.shutdownNow()
        pool = null
        acceptThread = null
        port = 0
        onEvent(Event.Stopped(reason))
    }

    /** Generates a new code, invalidating senders that still hold the old one. */
    @Synchronized
    fun rotateCode(): String {
        currentCode = TransferProtocol.newCode(random)
        failedAttempts.set(0)
        onEvent(Event.CodeRotated(currentCode))
        return currentCode
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (closed: SocketException) {
                if (running.get()) onEvent(Event.Failed("Accept failed", closed))
                return
            } catch (error: IOException) {
                if (running.get()) onEvent(Event.Failed("Accept failed", error))
                continue
            }
            activeSockets.add(client)
            val executor = pool
            if (executor == null || executor.isShutdown) {
                runCatching { client.close() }
                activeSockets.remove(client)
                continue
            }
            runCatching { executor.execute { handle(client) } }.onFailure {
                runCatching { client.close() }
                activeSockets.remove(client)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = readRequest(input)
            if (request == null) {
                respond(output, 400, TransferProtocol.json("error" to "Malformed request"))
                return
            }
            when {
                request.method == "GET" && request.path == TransferProtocol.PATH_INFO -> {
                    respondQuietly(output) {
                        respond(
                            output,
                            200,
                            TransferProtocol.json(
                                "name" to deviceName,
                                "app" to TransferProtocol.APP_NAME,
                                "version" to appVersion,
                                "port" to port,
                            ),
                        )
                    }
                }

                request.method == "POST" && request.path == TransferProtocol.PATH_UPLOAD -> {
                    handleUpload(request, input, output, client.inetAddress?.hostAddress ?: "?")
                }

                else -> respondQuietly(output) {
                    respond(output, 404, TransferProtocol.json("error" to "Unknown path"))
                }
            }
            // No flush here: [respond] already flushes, and a second one would throw for a
            // sender that hung up on a cancelled upload — an error with nothing behind it.
        } catch (error: IOException) {
            // Reaching here means the socket broke while parsing the request, which is
            // the sender's problem to see. Failures while *answering* an understood
            // request are dropped instead: [respondQuietly] already covers them, and a
            // sender that cancels or walks out of Wi-Fi range is not an app error.
            onEvent(Event.Failed("Connection failed", error))
        } finally {
            runCatching { client.close() }
            activeSockets.remove(client)
        }
    }

    private fun handleUpload(
        request: Request,
        input: InputStream,
        output: BufferedOutputStream,
        from: String,
    ) {
        val offered = request.query[TransferProtocol.NAME_PARAM]
        if (!request.headers[TransferProtocol.CODE_HEADER.lowercase()].equals(currentCode)) {
            val attempts = failedAttempts.incrementAndGet()
            onEvent(Event.UploadRejected(offered, "Wrong or missing code", from))
            if (attempts >= TransferProtocol.MAX_CODE_ATTEMPTS) {
                onEvent(Event.Failed("Too many wrong codes from $from", null))
                rotateCode()
            }
            respondQuietly(output) {
                respond(output, 403, TransferProtocol.json("error" to "Wrong or missing code"))
            }
            return
        }
        failedAttempts.set(0)

        val chunked = request.isChunked
        val length = request.headers["content-length"]?.toLongOrNull() ?: -1L
        if (length < 0L && !chunked) {
            onEvent(Event.UploadRejected(offered, "Length is required", from))
            respondQuietly(output) {
                respond(
                    output,
                    411,
                    TransferProtocol.json("error" to "Content-Length or a chunked body is required"),
                )
            }
            return
        }

        val fileName = TransferProtocol.safeFileName(
            offered,
            fallback = "received-${System.currentTimeMillis() % 100_000}.dat",
        )
        onEvent(Event.UploadStarted(fileName, if (chunked) -1L else length, from))

        val destination = try {
            sink.create(fileName, length)
        } catch (error: Exception) {
            onEvent(Event.Failed("Could not create '$fileName'", error))
            respondQuietly(output) {
                respond(output, 500, TransferProtocol.json("error" to "Could not create the file"))
            }
            return
        }

        var written = 0L
        var rejection: String? = null
        try {
            destination.stream.use { target ->
                val report: (Long) -> Unit = { bytes ->
                    onEvent(
                        Event.UploadProgress(fileName, bytes, if (chunked) -1L else length),
                    )
                }
                if (chunked) {
                    val total = copyChunks(input, target, report)
                    if (total == null) {
                        rejection = "Sender stopped part way through the transfer"
                    } else {
                        written = total
                    }
                } else {
                    written = copyFixed(input, target, length, report)
                    if (written < length) {
                        rejection = "Connection ended after $written of $length bytes"
                    }
                }
            }
        } catch (disconnected: IOException) {
            // A sender that cancels, stalls or leaves Wi-Fi range looks like this. It is
            // a normal way for a transfer to end, so report a rejection rather than an
            // error the user cannot act on.
            rejection = "Sender disconnected after $written of $length bytes"
        } catch (error: Exception) {
            runCatching { destination.discard() }
            onEvent(Event.Failed("Writing '$fileName' failed", error))
            respondQuietly(output) {
                respond(output, 500, TransferProtocol.json("error" to "Write failed"))
            }
            return
        }

        if (rejection != null) {
            runCatching { destination.discard() }
            onEvent(Event.UploadRejected(fileName, rejection, from))
            respondQuietly(output) {
                respond(output, 400, TransferProtocol.json("error" to "Incomplete body"))
            }
            return
        }

        val location = destination.commit()
        onEvent(Event.UploadFinished(fileName, written, location))
        respondQuietly(output) {
            respond(
                output,
                200,
                TransferProtocol.json(
                    "name" to fileName,
                    "bytes" to written,
                    "location" to (location ?: ""),
                ),
            )
        }
    }

    /** Copies exactly [length] bytes, stopping early when the sender hangs up. */
    private fun copyFixed(
        input: InputStream,
        target: OutputStream,
        length: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        var written = 0L
        var lastReport = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 2)
        while (written < length) {
            val wanted = minOf(buffer.size.toLong(), length - written).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read <= 0) break
            target.write(buffer, 0, read)
            written += read
            if (written - lastReport >= TransferProtocol.PROGRESS_STEP_BYTES || written == length) {
                lastReport = written
                onProgress(written)
            }
        }
        return written
    }

    /**
     * Copies a `Transfer-Encoding: chunked` body, which is how a sender streams a file
     * whose size it cannot know up front. Returns the bytes written, or null when the
     * sender stopped early or the framing is broken.
     */
    private fun copyChunks(
        input: InputStream,
        target: OutputStream,
        onProgress: (Long) -> Unit,
    ): Long? {
        var total = 0L
        var lastReport = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 2)
        while (true) {
            val header = readLine(input) ?: return null
            val size = header.trim().substringBefore(';').trim().toLongOrNull(16) ?: return null
            if (size == 0L) {
                // Trailers, terminated by an empty line.
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                return total
            }
            var remaining = size
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) return null
                target.write(buffer, 0, read)
                remaining -= read
                total += read
                if (total - lastReport >= TransferProtocol.PROGRESS_STEP_BYTES) {
                    lastReport = total
                    onProgress(total)
                }
            }
            // The CRLF that closes a chunk.
            if (readLine(input) == null) return null
        }
    }

    /**
     * Answers a request that has already been understood. A sender may hang up while
     * we reply (it cancelled, or its Wi-Fi dropped); the upload events already say what
     * happened, so the socket error carries no news for the user.
     */
    private inline fun respondQuietly(
        output: BufferedOutputStream,
        block: (BufferedOutputStream) -> Unit,
    ) {
        try {
            block(output)
        } catch (ignored: IOException) {
            // Deliberately swallowed: see the comment above.
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
    ) {
        val isChunked: Boolean
            get() = headers["transfer-encoding"]
                ?.split(',')
                ?.any { it.trim().equals("chunked", ignoreCase = true) } == true
    }

    /** Reads the request line and headers; the body is left in [input] for the caller. */
    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            head.append(line).append('\n')
            if (head.length > TransferProtocol.MAX_HEADER_BYTES) return null
        }
        val lines = head.toString().split('\n').filter { it.isNotEmpty() }
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() && it.contains('=') }
            .associate { pair ->
                val key = TransferProtocol.urlDecode(pair.substringBefore('='))
                val value = TransferProtocol.urlDecode(pair.substringAfter('='))
                key.lowercase() to value
            }
        val headers = lines.drop(1)
            .filter { it.contains(':') }
            .associate { header ->
                header.substringBefore(':').trim().lowercase() to header.substringAfter(':').trim()
            }
        return Request(method, path, query, headers)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        var read = input.read()
        if (read == -1) return null
        while (read != -1 && read != '\n'.code) {
            if (read != '\r'.code) builder.append(read.toChar())
            read = input.read()
        }
        return builder.toString()
    }

    private fun respond(output: BufferedOutputStream, status: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray())
        output.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }
}
