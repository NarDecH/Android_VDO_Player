package com.nardech.vdoplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favouritesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "vdo_favourites")

/**
 * The videos marked as favourites, kept by URI.
 *
 * A favourite is a pointer at a file, not a copy of it: the library still comes from
 * MediaStore, so a file the viewer deletes simply stops appearing and a renamed file
 * keeps its favourite because the URI does not change. A set is enough because the
 * library keeps its own order — the favourites list is the library filtered, not a
 * second list with an order of its own to maintain.
 */
class Favourites(private val context: Context) {

    private val key = stringSetPreferencesKey("uris")

    val favourites: Flow<Set<String>> = context.favouritesDataStore.data
        .map { prefs -> prefs[key].orEmpty() }

    /** Adds [uri] when it is not a favourite yet, and removes it when it is. */
    suspend fun toggle(uri: String): Boolean {
        var nowFavourite = false
        context.favouritesDataStore.edit { prefs ->
            val current = prefs[key].orEmpty()
            val updated = if (uri in current) current - uri else current + uri
            nowFavourite = uri in updated
            prefs[key] = updated
        }
        LogBus.i(
            LogTag.STORAGE,
            if (nowFavourite) "Added favourite: $uri" else "Removed favourite: $uri",
        )
        return nowFavourite
    }
}
