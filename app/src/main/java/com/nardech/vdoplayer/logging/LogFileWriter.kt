package com.nardech.vdoplayer.logging

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Appends log lines to rotating files inside
 * `getExternalFilesDir(null)/logs`, off the main thread.
 *
 * Rotation keeps at most [MAX_FILES] files of [MAX_FILE_BYTES] each, so the log
 * directory can never grow without bound on a phone with little free space.
 */
internal class LogFileWriter(private val context: Context) {

    private sealed interface Job {
        data class Line(val entry: LogEntry) : Job
        data class Flush(val done: CompletableDeferred<Unit>) : Job
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val lock = Any()

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var currentFile: File? = null

    private val logDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }

    fun start() {
        scope.launch {
            for (job in queue) {
                when (job) {
                    is Job.Line -> runCatching { writeLine(job.entry) }
                    is Job.Flush -> {
                        runCatching { writer?.flush() }
                        job.done.complete(Unit)
                    }
                }
            }
        }
        runCatching {
            logDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
                LogBus.d(LogTag.STORAGE, "Existing log file: ${file.name} (${file.length()} bytes)")
            }
        }
    }

    fun enqueue(entry: LogEntry) {
        queue.trySend(Job.Line(entry))
    }

    /** Writes everything queued so far and waits for it, used right before exporting. */
    fun flushBlocking(timeoutMillis: Long = 2_000) {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Job.Flush(done)).isFailure) return
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { done.await() }
            }
        }
    }

    fun logFiles(): List<File> =
        logDir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun currentFileOrNull(): File? = currentFile?.takeIf { it.exists() } ?: logFiles().firstOrNull()

    fun clearFiles() {
        scope.launch {
            closeCurrent()
            runCatching { logDir.listFiles()?.forEach { it.delete() } }
        }
    }

    private fun writeLine(entry: LogEntry) {
        synchronized(lock) {
            if (writer == null) openNew()
            val target = writer ?: return
            target.write(entry.toLine())
            target.newLine()
            target.flush()
            if ((currentFile?.length() ?: 0L) > MAX_FILE_BYTES) {
                closeCurrent()
                prune()
            }
        }
    }

    private fun openNew() {
        val stamp = FILE_STAMP.format(LocalDateTime.now())
        val file = File(logDir, "vdo-$stamp.log")
        writer = BufferedWriter(FileWriter(file, true))
        currentFile = file
    }

    private fun closeCurrent() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }

    private fun prune() {
        val files = logFiles()
        if (files.size <= MAX_FILES) return
        files.drop(MAX_FILES).forEach { stale ->
            runCatching { stale.delete() }
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val MAX_FILES = 10
        val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    }
}
