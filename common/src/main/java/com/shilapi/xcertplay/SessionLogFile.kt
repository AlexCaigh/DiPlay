package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File

/** Bounded, private diagnostics. Each write is redacted before touching storage. */
internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private var closed = false
    private var size = UNKNOWN_SIZE
    // Raw lines queue here; the writer thread redacts, rotates and writes them in order.
    private val writer = BoundedDiagnosticWriter<String>(MAX_PENDING, ::appendNow)

    fun reset(header: String) = synchronized(lock) {
        if (!closed) {
            file.parentFile?.mkdirs()
            rotate()
            file.writeText("")
            size = 0
            appendNow(header)
        }
    }

    /** Never waits for disk. Under overload the oldest queued line is discarded. */
    fun append(line: String) {
        if (!closed) writer.enqueue(line)
    }

    /** Synchronous write for callers that are already off the hot path. */
    fun appendNow(line: String) = synchronized(lock) {
        if (closed) return@synchronized
        val safe = DiagnosticRedactor.redact(line) ?: return@synchronized
        runCatching {
            val bytes = (safe + "\n").toByteArray()
            if (size == UNKNOWN_SIZE) size = file.length()
            if (size > MAX_BYTES) {
                rotate()
                file.writeText("")
                size = 0
            }
            file.appendBytes(bytes)
            size += bytes.size
        }.onFailure { size = UNKNOWN_SIZE }
        Unit
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)

    private fun rotate() {
        if (!file.exists() || file.length() == 0L) return
        for (index in ARCHIVE_NAMES.lastIndex downTo 1) {
            val source = File(file.parentFile, ARCHIVE_NAMES[index - 1])
            val destination = File(file.parentFile, ARCHIVE_NAMES[index])
            if (source.exists()) source.copyTo(destination, overwrite = true)
        }
        file.copyTo(File(file.parentFile, ARCHIVE_NAMES.first()), overwrite = true)
    }
    /** Flushes queued lines for a bounded time, then refuses new ones. */
    override fun close() {
        writer.close()
        writer.awaitIdle(CLOSE_FLUSH_MILLIS)
        synchronized(lock) { closed = true }
    }
    companion object {
        const val MAX_BYTES = 512 * 1024L
        private const val MAX_PENDING = 4096
        private const val CLOSE_FLUSH_MILLIS = 1_000L
        private const val UNKNOWN_SIZE = -1L
        private val ARCHIVE_NAMES = listOf("previous.log") + (2..7).map { "previous-$it.log" }
        val REPORT_NAMES = ARCHIVE_NAMES.reversed() + "diplay.log"
    }
}
