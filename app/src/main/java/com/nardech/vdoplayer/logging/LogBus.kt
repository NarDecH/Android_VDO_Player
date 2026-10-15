package com.nardech.vdoplayer.logging

import android.content.Context
import android.util.Log as AndroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central log bus: an in-memory ring buffer plus a fan-out to logcat and the
 * on-disk writer.
 *
 * The UI never observes the buffer directly. It observes [version], a cheap
 * counter, and calls [snapshot] only when it changed. That keeps a video player
 * from re-allocating a 2000 element list on every frame-adjacent log line.
 */
object LogBus {

    private const val MAX_ENTRIES = 2_000
    private val TAG_PREFIX = "VDO"

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Everything at or above this level also lands in the log file. */
    @Volatile
    var fileLevel: LogLevel = LogLevel.DEBUG

    @Volatile
    private var writer: LogFileWriter? = null

    fun install(context: Context) {
        if (writer != null) return
        writer = LogFileWriter(context.applicationContext).also { it.start() }
        i(LogTag.APP, "Logging subsystem ready (file level: ${fileLevel.label})")
    }

    fun v(tag: LogTag, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: LogTag, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: LogTag, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: LogTag, message: String, error: Throwable? = null) =
        log(LogLevel.WARN, tag, message, error)

    fun e(tag: LogTag, message: String, error: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, error)

    fun log(level: LogLevel, tag: LogTag, message: String, error: Throwable? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = error?.let { stackTraceOf(it) },
        )

        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) {
                buffer.removeFirst()
            }
        }
        _version.value = _version.value + 1

        AndroidLog.println(level.androidPriority(), "$TAG_PREFIX/${tag.name}", message + describe(error))

        if (level.priority >= fileLevel.priority) {
            writer?.enqueue(entry)
        }
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _version.value = _version.value + 1
        writer?.clearFiles()
        i(LogTag.APP, "Log buffer and files cleared by user")
    }

    /** Blocks until everything queued has hit the file, so exports are complete. */
    fun flushBlocking(timeoutMillis: Long = 2_000) {
        writer?.flushBlocking(timeoutMillis)
    }

    internal fun currentWriter(): LogFileWriter? = writer

    private fun stackTraceOf(error: Throwable): String =
        buildString {
            append(error.javaClass.name)
            if (!error.message.isNullOrBlank()) {
                append(": ")
                append(error.message)
            }
            error.stackTrace.take(40).forEach {
                append("\n    at ")
                append(it.toString())
            }
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 5) {
                append("\n  caused by ")
                append(cause.javaClass.name)
                if (!cause.message.isNullOrBlank()) {
                    append(": ")
                    append(cause.message)
                }
                cause.stackTrace.take(20).forEach {
                    append("\n    at ")
                    append(it.toString())
                }
                cause = cause.cause
                depth++
            }
        }

    private fun describe(error: Throwable?): String =
        if (error == null) "" else "\n${error.javaClass.simpleName}: ${error.message}"

    private fun LogLevel.androidPriority(): Int = when (this) {
        LogLevel.VERBOSE -> AndroidLog.VERBOSE
        LogLevel.DEBUG -> AndroidLog.DEBUG
        LogLevel.INFO -> AndroidLog.INFO
        LogLevel.WARN -> AndroidLog.WARN
        LogLevel.ERROR -> AndroidLog.ERROR
    }
}
