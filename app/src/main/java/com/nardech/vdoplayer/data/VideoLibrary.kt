package com.nardech.vdoplayer.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.nardech.vdoplayer.logging.LogBus
import com.nardech.vdoplayer.logging.LogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Reads the device video library from MediaStore.
 *
 * The column set is chosen per API level on purpose: `relative_path` only exists
 * from Android 10, and querying an unknown column on older releases fails the
 * whole query instead of returning null.
 */
class VideoLibrary(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    fun observe(): Flow<List<VideoItem>> = callbackFlow<Unit> {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                LogBus.d(LogTag.MEDIA, "MediaStore changed, rescanning library")
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .conflate()
        .map { load() }
        .flowOn(Dispatchers.IO)

    suspend fun load(): List<VideoItem> = withContext(Dispatchers.IO) { query() }

    private fun query(): List<VideoItem> {
        val started = System.currentTimeMillis()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.MIME_TYPE)
            add("bucket_display_name")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("relative_path")
            }
        }.toTypedArray()

        val items = mutableListOf<VideoItem>()
        runCatching {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val bucketColumn = cursor.getColumnIndex("bucket_display_name")
                val relativeColumn = cursor.getColumnIndex("relative_path")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    items += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = cursor.getString(nameColumn) ?: "video-$id",
                        durationMs = cursor.getLong(durationColumn),
                        sizeBytes = cursor.getLong(sizeColumn),
                        dateAddedSeconds = cursor.getLong(dateColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        mimeType = cursor.getString(mimeColumn),
                        folder = bucketColumn.takeIf { it >= 0 }
                            ?.let { cursor.getString(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?: "Videos",
                        relativePath = relativeColumn.takeIf { it >= 0 }
                            ?.let { cursor.getString(it) },
                    )
                }
            }
        }.onFailure {
            LogBus.e(LogTag.MEDIA, "MediaStore query failed", it)
        }

        LogBus.i(
            LogTag.MEDIA,
            "Library scan found ${items.size} videos in ${System.currentTimeMillis() - started} ms",
        )
        return items
    }

}
