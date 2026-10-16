package com.nardech.vdoplayer.transfer

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The sending half of a Wi-Fi transfer.
 *
 * Uses `HttpURLConnection` directly: one POST per file, the file body as the request
 * entity, named by the query string. Streaming both ways means a 4 GB video costs no
 * more memory than a 4 MB one.
 */
class TransferClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
) {

    /** Asks a receiver who it is; also serves as the reachability check for a typed address. */
    fun probe(baseUrl: String): Result<TransferDeviceInfo> = runCatching {
        val connection = open("$baseUrl${TransferProtocol.PATH_INFO}", "GET")
        try {
            val status = connection.responseCode
            val body = readBody(connection, status)
            if (status != HttpURLConnection.HTTP_OK) {
                error("HTTP $status ${body.take(120)}")
            }
            TransferDeviceInfo(
                name = TransferProtocol.jsonField(body, "name") ?: "?",
                app = TransferProtocol.jsonField(body, "app") ?: "?",
                version = TransferProtocol.jsonField(body, "version") ?: "?",
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams [sizeBytes] bytes from [source]. [onProgress] fires every
     * [TransferProtocol.PROGRESS_STEP_BYTES]; returning false from [shouldCancel]
     * aborts the upload and leaves no file behind on the receiver.
     */
    fun upload(
        baseUrl: String,
        code: String,
        fileName: String,
        sizeBytes: Long,
        source: InputStream,
        shouldCancel: () -> Boolean = { false },
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResult {
        val url = "$baseUrl${TransferProtocol.PATH_UPLOAD}" +
            "?${TransferProtocol.NAME_PARAM}=${TransferProtocol.urlEncode(fileName)}"
        val connection = open(url, "POST")
        var written = 0L
        var lastReport = 0L
        return try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty(TransferProtocol.CODE_HEADER, code)
            if (sizeBytes >= 0L) {
                connection.setFixedLengthStreamingMode(sizeBytes)
            } else {
                connection.setChunkedStreamingMode(DEFAULT_CHUNK)
            }

            var cancelled = false
            val output = connection.outputStream
            try {
                val buffer = ByteArray(DEFAULT_CHUNK)
                while (true) {
                    if (shouldCancel()) {
                        cancelled = true
                        break
                    }
                    val read = source.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - lastReport >= TransferProtocol.PROGRESS_STEP_BYTES || read < buffer.size) {
                        lastReport = written
                        onProgress(written, sizeBytes)
                    }
                }
                if (!cancelled) output.flush()
            } finally {
                // Closing a fixed-length body early throws "insufficient data written".
                // That is exactly what a cancelled upload looks like, so swallow it and
                // call [disconnect] below: the receiver then sees EOF, drops the partial
                // file and stays usable.
                if (cancelled) runCatching { output.close() } else output.close()
            }
            if (cancelled) return UploadResult(false, written, "Cancelled", null)

            val status = connection.responseCode
            val body = readBody(connection, status)
            when (status) {
                HttpURLConnection.HTTP_OK -> UploadResult(
                    ok = true,
                    bytes = TransferProtocol.jsonField(body, "bytes")?.toLongOrNull() ?: written,
                    message = null,
                    location = TransferProtocol.jsonField(body, "location"),
                )

                HttpURLConnection.HTTP_FORBIDDEN -> UploadResult(
                    false,
                    written,
                    "The receiving device rejected the code",
                    null,
                )

                else -> UploadResult(
                    false,
                    written,
                    "HTTP $status ${TransferProtocol.jsonField(body, "error") ?: body.take(120)}",
                    null,
                )
            }
        } catch (error: Exception) {
            UploadResult(false, written, error.message ?: error.javaClass.simpleName, null)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "${TransferProtocol.APP_NAME}/transfer")
            setRequestProperty("Accept", "application/json")
        }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
    }

    private companion object {
        const val DEFAULT_CHUNK = 64 * 1024
    }
}
