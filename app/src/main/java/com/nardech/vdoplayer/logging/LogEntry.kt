package com.nardech.vdoplayer.logging

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Severity of a single log line. Ordered from chatty to fatal. */
enum class LogLevel(val label: String, val priority: Int) {
    VERBOSE("VERBOSE", 2),
    DEBUG("DEBUG", 3),
    INFO("INFO", 4),
    WARN("WARN", 5),
    ERROR("ERROR", 6),
    ;

    companion object {
        fun fromName(name: String): LogLevel =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: INFO

        /** Levels at or above [level] are written to disk. */
        fun atLeast(level: LogLevel): List<LogLevel> = entries.filter { it.priority >= level.priority }
    }
}

/** Log sub systems, so the log viewer can filter by area. */
enum class LogTag {
    APP,
    UI,
    PLAYER,
    MEDIA,
    DECODER,
    STORAGE,
    EXPORT,
    TRANSFER,
    CRASH,
}

/**
 * One immutable log record. [throwable] is the already formatted stack trace, so
 * the record can be serialised long after the exception was caught.
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: LogTag,
    val message: String,
    val throwable: String? = null,
) {
    fun timeText(): String = TIME_FORMATTER.format(at())
    fun dateTimeText(): String = DATE_TIME_FORMATTER.format(at())

    private fun at(): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())

    /** Single line, as written into the log file. */
    fun toLine(): String = buildString {
        append(dateTimeText())
        append(' ')
        append(level.label.padEnd(7))
        append(' ')
        append('[')
        append(tag.name)
        append("] ")
        append(message)
        if (!throwable.isNullOrBlank()) {
            append('\n')
            append(throwable.trimEnd())
        }
    }

    companion object {
        private val TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.US)
        private val DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
