package moe.matsuri.nb4a.utils

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Shared by both processes: every line is an O_APPEND write on one inode and the size cap
// truncates that inode in place, so a process that opened the file earlier keeps writing to it.
object CoreLog {

    private const val FILE_NAME = "core.log"
    private const val MIN_SIZE_KB = 50

    private val lock = Any()
    private var stream: FileOutputStream? = null
    private val timestamp = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val file: File get() = File(app.filesDir, FILE_NAME)

    private fun maxSizeBytes(): Long =
        runCatching { DataStore.logBufSize }.getOrDefault(0).coerceAtLeast(MIN_SIZE_KB) * 1024L

    // "panic" keeps the file empty, the way the old "none" level did.
    private fun enabled(): Boolean = runCatching { DataStore.logLevel != "panic" }.getOrDefault(true)

    private fun stream(): FileOutputStream {
        stream?.let { return it }
        return FileOutputStream(file, true).also { stream = it }
    }

    fun write(line: String) {
        if (!enabled()) return
        val stamp = synchronized(timestamp) { timestamp.format(Date()) }
        val bytes = "$stamp $line\n".toByteArray()
        synchronized(lock) {
            runCatching {
                val out = stream()
                if (out.channel.size() + bytes.size > maxSizeBytes()) out.channel.truncate(0)
                out.write(bytes)
            }.onFailure { stream = null }
        }
    }

    fun clear() {
        synchronized(lock) {
            runCatching { stream().channel.truncate(0) }.onFailure { stream = null }
        }
    }

    fun read(maxBytes: Long): ByteArray {
        return try {
            val target = file
            val length = target.length()
            FileInputStream(target).use { input ->
                if (maxBytes in 1 until length) input.skip(length - maxBytes)
                input.readBytes()
            }
        } catch (e: Exception) {
            e.stackTraceToString().toByteArray()
        }
    }
}
