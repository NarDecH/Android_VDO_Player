package com.nardech.vdoplayer.util

import android.content.Context
import com.nardech.vdoplayer.R
import java.time.Instant
import java.time.ZoneId
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

    /**
     * Human readable age of a MediaStore timestamp. Takes a [Context] because the
     * wording ("5 min ago") is translated through string resources.
     */
    fun relativeTime(context: Context, epochSeconds: Long): String {
        if (epochSeconds <= 0L) return context.getString(R.string.time_just_now)
        val diff = (System.currentTimeMillis() / 1000) - epochSeconds
        return when {
            diff < 60 -> context.getString(R.string.time_just_now)
            diff < 3_600 -> context.getString(R.string.time_minutes_ago, diff / 60)
            diff < 86_400 -> context.getString(R.string.time_hours_ago, diff / 3_600)
            diff < 604_800 -> context.getString(R.string.time_days_ago, diff / 86_400)
            else -> Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
        }
    }
}
