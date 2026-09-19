package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner.Companion.STAGE_CANCELLED
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner.Companion.STAGE_COMPLETE
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner.Companion.STAGE_DISCOVERY
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner.Companion.STAGE_DOWNLOAD
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner.Companion.STAGE_ERROR
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner.Companion.STAGE_LATENCY
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner.Companion.STAGE_UPLOAD
import io.nekohasekai.sagernet.database.ProxyEntity
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.SpeedTestHandler
import io.throneproj.mobile.SpeedTestResult
import io.throneproj.mobile.TestRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

// Speed test of one profile in its own probe box, sampled every 100 ms like the old session.
class CoreSpeedTestSession(
    private val profile: ProxyEntity,
    private val mode: String,
    private val timeoutMs: Int,
    private val simpleDownloadUrl: String,
) {

    suspend fun run(onSample: (SpeedTestSnapshot) -> Unit): SpeedTestSnapshot {
        val generated = CoreConfigs.buildTest(listOf(profile.id))
        if (!generated.ok) error(generated.error ?: "config generation failed")
        generated.skipped[profile.id]?.let { error(it) }
        // A custom full sing-box config is a box of its own, measured through its default outbound.
        val fullConfig = generated.fullConfigs[profile.id]
        val core = if (fullConfig != null) CoreConfig(coreConfig = fullConfig) else CoreConfig.from(generated)
        val request = TestRequest().apply {
            core.applyTo(this)
            useDefaultOutbound = fullConfig != null
            timeoutMs = this@CoreSpeedTestSession.timeoutMs
            when (mode) {
                SpeedTestSettings.MODE_DOWNLOAD -> testDownload = true
                SpeedTestSettings.MODE_UPLOAD -> testUpload = true
                SpeedTestSettings.MODE_SIMPLE_DOWNLOAD -> {
                    simpleDownload = true
                    simpleDownloadAddr = simpleDownloadUrl
                }

                else -> {
                    testDownload = true
                    testUpload = true
                }
            }
        }

        val finished = CompletableDeferred<SpeedTestResult?>()
        val last = AtomicReference<SpeedTestResult?>(null)
        Mobile.startSpeedTest(null, CoreRuntime.platform, request, object : SpeedTestHandler {
            override fun onResult(result: SpeedTestResult?) {
                last.set(result)
            }

            override fun onDone() {
                finished.complete(last.get())
            }
        })

        var lastSnapshot = snapshot(stage = STAGE_DISCOVERY)
        onSample(lastSnapshot)
        try {
            while (withTimeoutOrNull(SAMPLE_INTERVAL_MS) { finished.await() } == null && !finished.isCompleted) {
                // The querier keeps the previous test's numbers until a new one is running.
                val progress = Mobile.querySpeedTest()
                if (progress.running) {
                    lastSnapshot = progress.toSnapshot(done = false)
                    onSample(lastSnapshot)
                }
            }
        } catch (e: CancellationException) {
            Mobile.stopTests()
            throw e
        }
        val result = finished.await()
            ?: return lastSnapshot.copy(stage = STAGE_ERROR, error = "speed test produced no result", done = true)
        return result.toSnapshot(done = true).also(onSample)
    }

    private fun snapshot(stage: String) = SpeedTestSnapshot(
        profileId = profile.id,
        profileName = profile.displayName(),
        mode = mode,
        stage = stage,
    )

    private fun SpeedTestResult.toSnapshot(done: Boolean): SpeedTestSnapshot {
        val error = error.orEmpty()
        val stage = when {
            !done && dlBytes == 0L && ulBytes == 0L -> STAGE_LATENCY
            !done && ulBytes > 0L -> STAGE_UPLOAD
            !done -> STAGE_DOWNLOAD
            cancelled -> STAGE_CANCELLED
            error.isNotEmpty() -> STAGE_ERROR
            else -> STAGE_COMPLETE
        }
        return SpeedTestSnapshot(
            profileId = profile.id,
            profileName = profile.displayName(),
            mode = mode,
            stage = stage,
            downloadBitsPerSecond = parseBitrate(dlSpeed),
            uploadBitsPerSecond = parseBitrate(ulSpeed),
            downloadBytes = dlBytes,
            uploadBytes = ulBytes,
            latencyMs = latency.toLong(),
            serverName = serverName.orEmpty(),
            serverCountry = serverCountry.orEmpty(),
            error = error,
            cancelled = cancelled,
            done = done,
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 100L
        val BITRATE = Regex("""([0-9.]+)\s*([KMG]?)bps""", RegexOption.IGNORE_CASE)

        // The core formats rates as "%.2f" + Kbps/Mbps/Gbps (decimal units).
        fun parseBitrate(text: String?): Long {
            if (text.isNullOrBlank()) return 0
            val match = BITRATE.find(text) ?: return 0
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0
            val unit = when (match.groupValues[2].uppercase()) {
                "G" -> 1e9
                "M" -> 1e6
                "K" -> 1e3
                else -> 1.0
            }
            return (value * unit).toLong()
        }
    }
}
