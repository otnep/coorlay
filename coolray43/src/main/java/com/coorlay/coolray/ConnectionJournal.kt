package com.coorlay.coolray

import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The connection journal: the newest lines in memory for the journal screen, and an append-only
 * file in [directory] that is rotated to [PREVIOUS_JOURNAL] past [maxFileBytes].
 *
 * Every file operation runs on one worker. Jobs that must not race with rotation (clearing the
 * previous journal, diagnostics that log a lot) are queued on it through [execute].
 */
internal class ConnectionJournal(
    private val directory: File,
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val onWriteError: (Throwable) -> Unit = {},
) {
    // A ThreadPoolExecutor so the writer can see whether more lines are queued before flushing.
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
    private val lines = ArrayDeque<String>()
    /** Guarded by [lines]: SimpleDateFormat is not thread-safe and lines arrive from many threads. */
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    /** Open journal file; touched only on [worker]. */
    private var writer: Writer? = null

    val file: File get() = File(directory, JOURNAL)
    val previousFile: File get() = File(directory, PREVIOUS_JOURNAL)

    /**
     * Shows the end of earlier runs' journal (up to 8 MB on disk: only its tail is read, off the UI
     * thread). Call before the first [add]: queued first, the file then still holds only earlier
     * runs, and their lines go before this run's.
     */
    fun loadHistory() = execute {
        val history = try {
            file.takeIf { it.exists() }
                ?.let { DiagnosticLogExporter.journalTail(it, HISTORY_BYTES) { _, tail -> tail.toList().takeLast(HISTORY_LINES) } }
                .orEmpty()
        } catch (_: Exception) { emptyList() }
        synchronized(lines) { history.asReversed().forEach { if (lines.size < MAX_LINES) lines.addFirst(it) } }
    }

    /** Stamps [line] with the time, keeps it for the journal screen and appends it to the file. */
    fun add(line: String): String {
        val entry = synchronized(lines) {
            val stamped = time.format(Date()) + " " + line
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(stamped)
            stamped
        }
        execute { append(entry) }
        return entry
    }

    /** The journal lines kept in memory, oldest first. */
    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    /** Runs [task] on the journal worker, after every line added so far; ignored once closed. */
    fun execute(task: () -> Unit) {
        if (worker.isShutdown) return
        try { worker.execute(task) } catch (_: RejectedExecutionException) { }
    }

    /** Writes buffered lines to the file, e.g. before the file is exported; waits up to [timeoutMillis]. */
    fun flush(timeoutMillis: Long) {
        try { worker.submit { flushFile() }.get(timeoutMillis, TimeUnit.MILLISECONDS) } catch (_: Exception) { }
    }

    /** Closes the file after the queued lines; later lines stay in memory only. */
    fun close() {
        execute { closeFile() }
        worker.shutdown()
    }

    /**
     * Worker only. The file stays open; a burst of lines is flushed once the queue drains,
     * instead of opening and closing the file for every line on the head unit's flash.
     */
    private fun append(entry: String) {
        try {
            val output = writer ?: FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).also { writer = it }
            output.write(entry); output.write("\n")
            if (worker.queue.isEmpty()) flushFile()
        } catch (error: Exception) {
            onWriteError(error)
            closeFile()
        }
    }

    /** Worker only: writes out buffered lines and rotates the file past [maxFileBytes]. */
    private fun flushFile() {
        val output = writer ?: return
        try {
            output.flush()
            if (file.length() > maxFileBytes) {
                closeFile()
                previousFile.delete()
                file.renameTo(previousFile)
            }
        } catch (error: Exception) {
            onWriteError(error)
            closeFile()
        }
    }

    private fun closeFile() {
        val output = writer ?: return
        writer = null
        try { output.close() } catch (_: Exception) { }
    }

    companion object {
        const val JOURNAL = "connection-journal.txt"
        const val PREVIOUS_JOURNAL = "connection-journal.previous.txt"
        /** Lines kept in memory for the journal screen. */
        const val MAX_LINES = 1200
        private const val HISTORY_LINES = 400
        private const val HISTORY_BYTES = 256L * 1024
    }
}
