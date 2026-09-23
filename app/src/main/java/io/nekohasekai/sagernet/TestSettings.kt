package io.nekohasekai.sagernet

import java.net.URI

/**
 * Speed-test values shared by the preferences, the speed-test path and the profile list. The setting
 * `speed_test_mode` is the desktop's int enum (Const.hpp:39-49); the mode names travel with the snapshots to the
 * :bg process and are stored on the profiles next to the measured rates.
 */
object SpeedTestSettings {

    const val FULL = 0
    const val DOWNLOAD_ONLY = 1
    const val UPLOAD_ONLY = 2
    const val SIMPLE_DOWNLOAD = 3
    const val COUNTRY = 4

    const val MODE_DOWNLOAD_UPLOAD = "download_upload"
    const val MODE_DOWNLOAD = "download"
    const val MODE_UPLOAD = "upload"
    const val MODE_SIMPLE_DOWNLOAD = "simple_download"
    const val MODE_COUNTRY = "country"

    val modes = setOf(
        MODE_DOWNLOAD_UPLOAD,
        MODE_DOWNLOAD,
        MODE_UPLOAD,
        MODE_SIMPLE_DOWNLOAD,
        MODE_COUNTRY,
    )

    fun isValidMode(value: String): Boolean = value in modes

    /** The name of a `speed_test_mode` value; an unknown value is the desktop default (download + upload). */
    fun modeName(mode: Int): String = when (mode) {
        DOWNLOAD_ONLY -> MODE_DOWNLOAD
        UPLOAD_ONLY -> MODE_UPLOAD
        SIMPLE_DOWNLOAD -> MODE_SIMPLE_DOWNLOAD
        COUNTRY -> MODE_COUNTRY
        else -> MODE_DOWNLOAD_UPLOAD
    }

    fun isValidTimeout(value: String): Boolean = value.toIntOrNull()?.let { it > 0 } == true

    fun isValidHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
