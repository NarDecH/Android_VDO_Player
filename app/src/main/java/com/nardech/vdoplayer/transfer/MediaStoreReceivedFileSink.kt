package com.nardech.vdoplayer.transfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Publishes received files where the phone's gallery and this app can both see them.
 *
 * Android 10 and newer go through MediaStore with `IS_PENDING`, so a half-finished
 * transfer is never visible; older releases write the file directly (the app declares
 * `WRITE_EXTERNAL_STORAGE` up to API 28 for its log exports already) and ask the media
 * scanner to index it.
 */
class MediaStoreReceivedFileSink(private val context: Context) : ReceivedFileSink {

    override fun create(fileName: String, sizeBytes: Long): ReceivedFileSink.Destination {
        val name = TransferProtocol.deduplicate(fileName) { candidate -> exists(candidate) }
        val mime = MimeTypes.of(name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createViaMediaStore(name, mime)
        } else {
            createInPublicFolder(name)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createViaMediaStore(name: String, mime: String): ReceivedFileSink.Destination {
        val resolver = context.contentResolver
        val video = mime.startsWith("video/")
        val collection = if (video) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val relativePath = if (video) {
            "${Environment.DIRECTORY_MOVIES}/${TransferProtocol.RECEIVE_FOLDER}"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/${TransferProtocol.RECEIVE_FOLDER}"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore refused to create an entry for '$name'")
        val stream = resolver.openOutputStream(uri)
            ?: error("MediaStore did not open '$name' for writing")
        return MediaStoreDestination(resolver, uri, stream, "$relativePath/$name")
    }

    private fun createInPublicFolder(name: String): ReceivedFileSink.Destination {
        val parent = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            TransferProtocol.RECEIVE_FOLDER,
        )
        if (!parent.exists() && !parent.mkdirs()) {
            error("Could not create ${parent.absolutePath}")
        }
        val file = File(parent, name)
        return LegacyDestination(context, file, FileOutputStream(file))
    }

    private fun exists(name: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return existsInMediaStore(name)
        }
        val parent = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            TransferProtocol.RECEIVE_FOLDER,
        )
        return File(parent, name).exists()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun existsInMediaStore(name: String): Boolean {
        val collection = if (MimeTypes.isVideo(name)) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        context.contentResolver.query(collection, projection, selection, arrayOf(name), null)
            ?.use { cursor -> return cursor.moveToFirst() }
        return false
    }

    private class MediaStoreDestination(
        private val resolver: android.content.ContentResolver,
        private val uri: Uri,
        override val stream: OutputStream,
        private val location: String,
    ) : ReceivedFileSink.Destination {

        override fun commit(): String? {
            runCatching { stream.close() }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            runCatching { resolver.update(uri, values, null, null) }
            return location
        }

        override fun discard() {
            runCatching { stream.close() }
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private class LegacyDestination(
        private val context: Context,
        private val file: File,
        override val stream: OutputStream,
    ) : ReceivedFileSink.Destination {

        override fun commit(): String? {
            runCatching { stream.close() }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(MimeTypes.of(file.name)),
                null,
            )
            return file.absolutePath
        }

        override fun discard() {
            runCatching { stream.close() }
            runCatching { file.delete() }
        }
    }
}
