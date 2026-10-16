package com.nardech.vdoplayer.transfer

import java.io.OutputStream
import java.security.SecureRandom
import java.util.Locale

/**
 * The wire contract for phone-to-phone transfers.
 *
 * Both ends are this app, so the protocol is deliberately the smallest thing that
 * works: plain HTTP/1.1 with the file body as the request entity, and a four digit
 * code in a header. That keeps the app dependency free (no HTTP server library, no
 * Nearby/Play Services) and makes the receiving end testable from a terminal with
 * `curl -H 'X-VDO-Code: 1234' --data-binary @clip.mp4 'http://IP:PORT/upload?name=clip.mp4'`.
 *
 * Sockets need the INTERNET permission; nothing here ever leaves the local network.
 */
object TransferProtocol {

    /** Port the receiver tries first; it falls back to an ephemeral port when busy. */
    const val DEFAULT_PORT = 45871

    /** mDNS service type used to find receivers on the same Wi-Fi network. */
    const val SERVICE_TYPE = "_vdoplayer._tcp."

    const val PATH_INFO = "/info"
    const val PATH_UPLOAD = "/upload"

    /** Header carrying the one-time code the sender must repeat back. */
    const val CODE_HEADER = "X-VDO-Code"

    /** Query parameter holding the file name the sender proposes. */
    const val NAME_PARAM = "name"

    /** Refuse absurd header blocks instead of reading forever. */
    const val MAX_HEADER_BYTES = 16 * 1024

    /** Parallel uploads accepted at once. */
    const val MAX_CONCURRENT_UPLOADS = 3

    /** Wrong codes from one device before the code is rotated. */
    const val MAX_CODE_ATTEMPTS = 25

    /** How often upload progress is reported, in bytes. */
    const val PROGRESS_STEP_BYTES = 256L * 1024L

    /** Sub-folder created under Movies for received files. */
    const val RECEIVE_FOLDER = "VDO Player"

    const val APP_NAME = "VDO Player"

    /**
     * Characters that must never reach the file system: directory separators, the set
     * Windows reserves, and control characters. Everything else is kept — including Thai
     * vowel marks and accents, which a `\w` style filter would quietly destroy — because
     * the name is the user's own file name and it should arrive unchanged.
     */
    private val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]+")

    /** File names longer than this are shortened, keeping the extension. */
    const val MAX_NAME_LENGTH = 120

    /** Four digits, regenerated every time receiving starts and on demand. */
    fun newCode(random: SecureRandom = SecureRandom()): String =
        String.format(Locale.US, "%04d", random.nextInt(10_000))

    /**
     * Strips directory separators and control characters from a name proposed by a
     * remote device, so a hostile sender cannot escape the receive folder.
     */
    fun safeFileName(raw: String?, fallback: String = "received"): String {
        val trimmed = raw
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(UNSAFE_NAME, "_")
            ?.trim()
            .orEmpty()
        val name = if (trimmed.isBlank() || trimmed == "." || trimmed == "..") {
            fallback
        } else {
            trimmed
        }
        return shorten(name)
    }

    /**
     * Trims a name down to [MAX_NAME_LENGTH] without losing the extension: the extension
     * decides whether a received video lands in Movies or in Downloads, so a very long
     * file name must not turn a video into a generic download.
     */
    private fun shorten(name: String): String {
        if (name.length <= MAX_NAME_LENGTH) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 12) name.substring(dot) else ""
        val stem = if (extension.isEmpty()) name else name.substring(0, dot)
        return stem.take(MAX_NAME_LENGTH - extension.length).trimEnd() + extension
    }

    /** Adds ` (2)`, ` (3)`… before the extension when the name is already taken. */
    fun deduplicate(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (index < 1_000) {
            val candidate = "$stem ($index)$extension"
            if (!taken(candidate)) return candidate
            index++
        }
        return name
    }

    fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    fun urlDecode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")

    /**
     * Turns what a user typed into a base URL. Accepts `192.168.0.7`, `192.168.0.7:45871`
     * and full `http://host:port` forms; returns null when there is nothing usable.
     */
    fun baseUrlFrom(input: String, defaultPort: Int = DEFAULT_PORT): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        // Trailing slashes are dropped here rather than before the scheme check, so that
        // "http://" stays recognisable as an address with nothing in it.
        val withoutScheme = withScheme.substringAfter("://").trimEnd('/')
        if (withoutScheme.isBlank()) return null
        val host = withoutScheme.substringBefore(':').substringBefore('/')
        if (host.isBlank()) return null
        val port = withoutScheme.substringAfter(':', "").substringBefore('/')
        val resolvedPort = port.toIntOrNull()?.takeIf { it in 1..65_535 } ?: defaultPort
        return "http://$host:$resolvedPort"
    }

    fun json(vararg pairs: Pair<String, Any?>): String = buildString {
        append('{')
        pairs.forEachIndexed { index, pair ->
            if (index > 0) append(',')
            append('"').append(escapeJson(pair.first)).append("\":")
            val value = pair.second
            when (value) {
                null -> append("null")
                is Number, is Boolean -> append(value.toString())
                else -> append('"').append(escapeJson(value.toString())).append('"')
            }
        }
        append('}')
    }

    /** Reads one string/number field out of a flat JSON object produced by [json]. */
    fun jsonField(document: String, key: String): String? {
        val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|[^,}\\s]+)")
        val match = pattern.find(document) ?: return null
        val literal = match.groupValues[1]
        return if (literal.startsWith("\"")) unescapeJson(match.groupValues[2]) else literal
    }

    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }

    private fun unescapeJson(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index == value.lastIndex) {
                out.append(character)
                index++
                continue
            }
            when (val next = value[index + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = value.substring(index + 2, (index + 6).coerceAtMost(value.length))
                    out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    index += 4
                }
                else -> out.append(next)
            }
            index += 2
        }
        return out.toString()
    }
}

/** Where a received file is written. Implemented for MediaStore and, in tests, for a temp dir. */
interface ReceivedFileSink {

    fun create(fileName: String, sizeBytes: Long): Destination

    interface Destination {
        val stream: OutputStream

        /** Closes the stream and publishes the file; returns a human readable location. */
        fun commit(): String?

        /** Closes and removes a partial file. */
        fun discard()
    }
}

data class TransferDeviceInfo(
    val name: String,
    val app: String,
    val version: String,
)

data class UploadResult(
    val ok: Boolean,
    val bytes: Long,
    val message: String?,
    val location: String?,
)
