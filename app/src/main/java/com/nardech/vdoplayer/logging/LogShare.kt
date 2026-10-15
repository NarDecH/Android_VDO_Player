package com.nardech.vdoplayer.logging

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LogExportFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    ZIP("zip", "application/zip"),
}

data class LogExportResult(val file: File, val summary: String)

/**
 * Turns the in-memory buffer and the rotated files on disk into something the
 * user can send to a developer: a single readable text report, or a zip with the
 * raw files plus diagnostics.
 */
object LogShare {

    suspend fun export(
        context: Context,
        format: LogExportFormat,
        includeDiagnostics: Boolean,
        player: Player? = null,
        entries: List<LogEntry>,
    ): LogExportResult = withContext(Dispatchers.IO) {
        LogBus.flushBlocking()

        val stamp = FILE_STAMP.format(LocalDateTime.now())
        val outDir = File(context.externalCacheDir ?: context.cacheDir, "exports").apply { mkdirs() }
        val baseName = "vdo-player-logs-$stamp"

        when (format) {
            LogExportFormat.TXT -> {
                val file = File(outDir, "$baseName.txt")
                file.bufferedWriter().use { out ->
                    out.appendLine("VDO Player log export")
                    out.appendLine("Generated: ${LocalDateTime.now()}")
                    out.appendLine("Records in buffer: ${entries.size}")
                    out.appendLine()
                    if (includeDiagnostics) {
                        out.appendLine(Diagnostics.summary(context, player))
                        out.appendLine(Diagnostics.codecInventory())
                        out.appendLine()
                    }
                    out.appendLine("=== Session log ===")
                    entries.forEach { out.appendLine(it.toLine()) }
                }
                LogExportResult(file, "Text report (${entries.size} lines)")
            }

            LogExportFormat.ZIP -> {
                val file = File(outDir, "$baseName.zip")
                ZipOutputStream(file.outputStream().buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("session.log"))
                    entries.forEach { zip.write((it.toLine() + "\n").toByteArray()) }
                    zip.closeEntry()

                    if (includeDiagnostics) {
                        zip.putNextEntry(ZipEntry("diagnostics.txt"))
                        zip.write(Diagnostics.summary(context, player).toByteArray())
                        zip.write("\n".toByteArray())
                        zip.write(Diagnostics.codecInventory().toByteArray())
                        zip.closeEntry()
                    }

                    LogBus.currentWriter()?.logFiles()?.forEach { disk ->
                        runCatching {
                            zip.putNextEntry(ZipEntry("files/${disk.name}"))
                            disk.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                LogExportResult(file, "Zip bundle")
            }
        }
    }

    fun shareIntent(context: Context, result: LogExportResult, format: LogExportFormat): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            result.file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VDO Player diagnostics")
            putExtra(
                Intent.EXTRA_TEXT,
                "VDO Player log export — ${LocalDateTime.now()}",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Copies the export into the shared Downloads collection. On Android 10+
     * this goes through MediaStore (no storage permission needed); older
     * releases fall back to the app specific external directory.
     */
    suspend fun saveToDownloads(
        context: Context,
        result: LogExportResult,
        format: LogExportFormat,
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, result.file.name)
                put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values)
            if (uri == null) {
                LogBus.w(LogTag.EXPORT, "MediaStore refused to create a Downloads entry")
                return@withContext null
            }
            runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    result.file.inputStream().use { it.copyTo(out) }
                }
            }.onFailure {
                LogBus.e(LogTag.EXPORT, "Copying the export into Downloads failed", it)
                resolver.delete(uri, null, null)
                return@withContext null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            LogBus.i(LogTag.EXPORT, "Saved export to Downloads: ${result.file.name}")
            uri
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val target = File(downloads, result.file.name)
            runCatching {
                target.parentFile?.mkdirs()
                result.file.copyTo(target, overwrite = true)
            }.onFailure {
                LogBus.e(LogTag.EXPORT, "Legacy Downloads write failed, keeping app copy", it)
                return@withContext null
            }
            val uri = Uri.fromFile(target)
            LogBus.i(LogTag.EXPORT, "Saved export to $target")
            uri
        }
    }

    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
}
