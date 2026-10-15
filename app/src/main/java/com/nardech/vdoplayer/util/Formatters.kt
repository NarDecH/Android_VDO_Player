package com.nardech.vdoplayer.util

import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {

    /** `1:04:07` for long videos, `4:07` otherwise. */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "--:--"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun size(bytes: Long): String {
        if (bytes <= 0L) return "-"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) {
            "${bytes} ${units[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }

    fun speed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}x"
        } else {
            String.format(Locale.US, "%.2fx", speed)
        }

    fun relativeTime(epochSeconds: Long): String {
        val now = System.currentTimeMillis() / 1000
        val diff = now - epochSeconds
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86_400 -> "${diff / 3600} h ago"
            diff < 604_800 -> "${diff / 86_400} d ago"
            else -> java.time.Instant.ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }
    }
}
