package com.nardech.vdoplayer.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When a frame is worth decoding, and which cache slot it belongs to.
 *
 * Decoding a frame costs a full seek in the container plus a decode, so the rules are
 * separated from the retriever and unit tested: only scrub positions in a new bucket
 * are decoded, and an offline retriever is never asked for anything.
 */
object SeekPreviewPolicy {

    /** Frame positions are rounded to this grid, so a slow drag reuses frames. */
    const val BUCKET_MS = 2_000L

    fun isEnabled(setting: Boolean): Boolean = setting

    fun bucket(positionMs: Long, bucketMs: Long = BUCKET_MS): Long {
        if (bucketMs <= 0L) return positionMs
        return Math.floorDiv(positionMs.coerceAtLeast(0L), bucketMs) * bucketMs
    }

    /** True when [targetMs] is far enough from the frame we already have. */
    fun needsNewFrame(currentPositionMs: Long?, targetMs: Long): Boolean {
        if (currentPositionMs == null) return true
        return bucket(currentPositionMs) != bucket(targetMs)
    }

    /** A preview that describes the same bucket as [targetMs] still applies. */
    fun stillApplies(previewPositionMs: Long?, targetMs: Long): Boolean {
        if (previewPositionMs == null) return false
        return bucket(previewPositionMs) == bucket(targetMs)
    }
}

/** A frame grabbed for the seek bar, with the position it was taken at. */
data class SeekPreview(val positionMs: Long, val bitmap: Bitmap)

/**
 * Grabs single frames while the user scrubs.
 *
 * One [MediaMetadataRetriever] is reused per media item (opening one costs a container
 * parse), frames are scaled down before they reach Compose, and an LRU cache means a
 * drag back and forth does not decode the same second twice. Every call is serialised:
 * the hardware decoder does not like being asked for frames from several threads at
 * once, and the UI only ever wants the newest one anyway.
 */
class SeekPreviewSource(private val cacheEntries: Int = 24) {

    private val lock = Mutex()
    private var retrieverUri: Uri? = null
    private var retriever: MediaMetadataRetriever? = null
    private val cache = LruCache<Long, Bitmap>(cacheEntries)

    /**
     * Returns a frame at [positionMs], or null when the file cannot be read (a
     * provider that went away, an unsupported container, no frame at that time).
     */
    suspend fun frame(
        context: Context,
        uri: Uri,
        positionMs: Long,
        maxWidthPx: Int,
    ): Bitmap? = lock.withLock {
        val key = SeekPreviewPolicy.bucket(positionMs)
        cache.get(key)?.let { return it }

        val source = retriever(context, uri) ?: return null
        val raw = runCatching {
            source.getFrameAtTime(
                positionMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            )
        }.getOrNull() ?: return null

        val scaled = scale(raw, maxWidthPx)
        if (scaled !== raw) raw.recycle()
        cache.put(key, scaled)
        return scaled
    }

    /** Drops the retriever and every cached frame; used when playback moves on. */
    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        retrieverUri = null
        cache.evictAll()
    }

    private fun retriever(context: Context, uri: Uri): MediaMetadataRetriever? {
        val existing = retriever
        if (existing != null && retrieverUri == uri) return existing
        runCatching { existing?.release() }
        retriever = null
        retrieverUri = null
        cache.evictAll()

        val created = MediaMetadataRetriever()
        return runCatching {
            created.setDataSource(context, uri)
        }.map {
            retriever = created
            retrieverUri = uri
            created
        }.getOrElse {
            runCatching { created.release() }
            null
        }
    }

    private fun scale(bitmap: Bitmap, maxWidthPx: Int): Bitmap {
        if (maxWidthPx <= 0 || bitmap.width <= maxWidthPx) return bitmap
        val height = (bitmap.height.toLong() * maxWidthPx / bitmap.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, maxWidthPx, height, true)
    }
}
