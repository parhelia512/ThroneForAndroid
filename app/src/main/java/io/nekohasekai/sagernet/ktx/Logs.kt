package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.CoreLog
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // Levels follow the sing-box log.level mapping: 0=panic 1=warn 2=info 3=debug 4=trace.
    // Gated at the source; an unreadable DataStore (JVM tests) lets the line through.
    private fun enabled(required: Int): Boolean {
        return runCatching { DataStore.logLevel >= required }.getOrDefault(true)
    }

    // No app context in JVM unit tests: a failed write is ignored.
    private fun printLog(line: String) {
        runCatching { CoreLog.write(line) }
    }

    fun d(message: String) {
        if (!enabled(3)) return
        printLog("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        if (!enabled(3)) return
        printLog("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        if (!enabled(2)) return
        printLog("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        if (!enabled(2)) return
        printLog("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        if (!enabled(1)) return
        printLog("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        if (!enabled(1)) return
        printLog("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        if (!enabled(1)) return
        printLog("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        printLog("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        printLog("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        printLog("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}