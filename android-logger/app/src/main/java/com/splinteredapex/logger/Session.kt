package com.splinteredapex.logger

import android.content.Context
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/** One recording run: a folder of files. All rows carry t_ns = SystemClock.elapsedRealtimeNanos(). */
class Session(val dir: File) {

    val name: String get() = dir.name

    private val writers = mutableListOf<TextFile>()
    private val binaries = mutableListOf<BinaryFile>()

    fun text(fileName: String, header: String?, gzip: Boolean = false): TextFile {
        val f = TextFile(File(dir, fileName), gzip)
        if (header != null) f.line(header)
        synchronized(writers) { writers += f }
        return f
    }

    fun binary(fileName: String): BinaryFile {
        val f = BinaryFile(File(dir, fileName))
        synchronized(binaries) { binaries += f }
        return f
    }

    fun flushAll() {
        synchronized(writers) { writers.forEach { it.flush() } }
        synchronized(binaries) { binaries.forEach { it.flush() } }
    }

    fun closeAll() {
        synchronized(writers) { writers.forEach { it.close() }; writers.clear() }
        synchronized(binaries) { binaries.forEach { it.close() }; binaries.clear() }
    }

    class TextFile(file: File, gzip: Boolean) {
        private val w: BufferedWriter
        @Volatile private var closed = false

        init {
            val raw: OutputStream = FileOutputStream(file)
            val counted = CountingStream(raw)
            val s: OutputStream = if (gzip) GZIPOutputStream(counted, 1 shl 16) else counted
            w = BufferedWriter(OutputStreamWriter(s, Charsets.UTF_8), 1 shl 16)
        }

        @Synchronized fun line(s: String) {
            if (closed) return
            w.write(s); w.write("\n")
        }

        @Synchronized fun flush() { if (!closed) runCatching { w.flush() } }
        @Synchronized fun close() { if (!closed) { closed = true; runCatching { w.close() } } }
    }

    class BinaryFile(file: File) {
        private val o = BufferedOutputStream(CountingStream(FileOutputStream(file)), 1 shl 16)
        @Volatile private var closed = false
        @Synchronized fun write(b: ByteArray, off: Int, len: Int) { if (!closed) o.write(b, off, len) }
        @Synchronized fun flush() { if (!closed) runCatching { o.flush() } }
        @Synchronized fun close() { if (!closed) { closed = true; runCatching { o.close() } } }
    }

    /** Counts bytes that reach the disk so the screen can show the run size. */
    private class CountingStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) { out.write(b); Live.bytesWritten += 1 }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); Live.bytesWritten += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    companion object {
        fun root(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "runs").apply { mkdirs() }

        fun create(context: Context): Session {
            val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK)
            val dir = File(root(context), "run_" + fmt.format(Date()))
            dir.mkdirs()
            return Session(dir)
        }

        fun list(context: Context): List<File> =
            root(context).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

        fun isoUtc(ms: Long): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.UK)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(ms))
        }
    }
}
