package io.nekohasekai.sagernet.bg

import android.app.Application
import go.Seq
import io.nekohasekai.sagernet.BuildConfig
import io.throneproj.mobile.LogSink
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.SetupOptions
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.utils.CoreLog
import java.io.File

// Loaded only in the :bg process: everything here pulls the ThroneCore AAR (libthrone.so) in.
object CoreRuntime {

    private const val LOG_QUEUE_LINES = 1024

    val platform: NativeInterface by lazy { NativeInterface() }

    fun setup(app: Application) {
        Seq.setContext(app)
        CoreLog.clear()
        Mobile.setup(SetupOptions().apply {
            basePath = app.filesDir.absolutePath
            workingPath = File(app.filesDir, "core").absolutePath
            tempPath = app.cacheDir.absolutePath
            logMaxLines = LOG_QUEUE_LINES
            debug = BuildConfig.DEBUG
        })
        Mobile.setLogSink(CoreLogSink)
    }

    private object CoreLogSink : LogSink {
        override fun write(level: Int, message: String) {
            CoreLog.write("[${levelName(level)}] $message")
        }

        private fun levelName(level: Int): String = when (level) {
            Mobile.LogLevelPanic -> "Panic"
            Mobile.LogLevelFatal -> "Fatal"
            Mobile.LogLevelError -> "Error"
            Mobile.LogLevelWarn -> "Warning"
            Mobile.LogLevelInfo -> "Info"
            Mobile.LogLevelDebug -> "Debug"
            Mobile.LogLevelTrace -> "Trace"
            else -> "Info"
        }
    }
}
