package com.nardech.vdoplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** A video the user actually watched, with the position playback should resume at. */
data class HistoryEntry(
    val uri: String,
    val title: String,
    val folder: String,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAt: Long,
) {
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

private val Context.historyDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "vdo_history")

/**
 * Remembers where the user stopped. Stored as one JSON blob inside DataStore so a
 * few hundred entries stay a single cheap write instead of hundreds of keys.
 */
class PlayHistory(private val context: Context) {

    private val key = stringPreferencesKey("entries")

    val recent: Flow<List<HistoryEntry>> = context.historyDataStore.data
        .map { prefs -> decode(prefs[key]) }

    suspend fun record(entry: HistoryEntry) {
        context.historyDataStore.edit { prefs ->
            val current = decode(prefs[key]).filterNot { it.uri == entry.uri }.toMutableList()
            current.add(0, entry)
            prefs[key] = encode(current.take(MAX_ENTRIES))
        }
    }

    suspend fun positionFor(uri: String): Long =
        recentNow().firstOrNull { it.uri == uri }?.positionMs ?: 0L
    suspend fun remove(uri: String) {
        context.historyDataStore.edit { prefs ->
            prefs[key] = encode(decode(prefs[key]).filterNot { it.uri == uri })
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { it.remove(key) }
        LogBus.i(LogTag.STORAGE, "Playback history cleared")
    }

    private suspend fun recentNow(): List<HistoryEntry> = recent.first()

    private fun decode(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    add(
                        HistoryEntry(
                            uri = json.optString("uri"),
                            title = json.optString("title"),
                            folder = json.optString("folder"),
                            durationMs = json.optLong("duration"),
                            positionMs = json.optLong("position"),
                            updatedAt = json.optLong("updatedAt"),
                        ),
                    )
                }
            }
        }.onFailure {
            LogBus.w(LogTag.STORAGE, "History blob was unreadable, resetting it", it)
        }.getOrDefault(emptyList())
    }

    private fun encode(entries: List<HistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("uri", entry.uri)
                    put("title", entry.title)
                    put("folder", entry.folder)
                    put("duration", entry.durationMs)
                    put("position", entry.positionMs)
                    put("updatedAt", entry.updatedAt)
                },
            )
        }
        return array.toString()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
