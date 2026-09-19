package io.nekohasekai.sagernet.bg.proto

import android.os.Parcelable
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.parcelize.Parcelize
import kotlin.coroutines.coroutineContext

@Parcelize
data class SpeedTestSnapshot(
    val profileId: Long,
    val profileName: String,
    val mode: String,
    val stage: String,
    val downloadBitsPerSecond: Long = 0,
    val uploadBitsPerSecond: Long = 0,
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val latencyMs: Long = 0,
    val serverName: String = "",
    val serverCountry: String = "",
    val error: String = "",
    val cancelled: Boolean = false,
    val done: Boolean = false,
) : Parcelable

internal fun completedSpeedTestCount(index: Int, total: Int, done: Boolean): Int {
    if (total <= 0) return 0
    return (index + if (done) 1 else 0).coerceIn(0, total)
}

interface SpeedTestNodeSession : AutoCloseable {
    suspend fun run(onSample: (SpeedTestSnapshot) -> Unit): SpeedTestSnapshot
    fun cancel()
}

class SpeedTestQueueRunner<T>(
    private val sessionFactory: (T) -> SpeedTestNodeSession,
    private val failureSnapshot: (T, Exception) -> SpeedTestSnapshot,
) {
    @Volatile
    private var currentSession: SpeedTestNodeSession? = null

    suspend fun run(
        profiles: List<T>,
        onSample: (index: Int, total: Int, sample: SpeedTestSnapshot) -> Unit,
    ): List<SpeedTestSnapshot> {
        val results = ArrayList<SpeedTestSnapshot>(profiles.size)
        profiles.forEachIndexed { index, profile ->
            coroutineContext.ensureActive()
            val session = sessionFactory(profile)
            currentSession = session
            try {
                results += session.run { onSample(index, profiles.size, it) }
            } catch (e: CancellationException) {
                session.cancel()
                throw e
            } catch (e: Exception) {
                val failed = failureSnapshot(profile, e)
                onSample(index, profiles.size, failed)
                results += failed
            } finally {
                runCatching { session.close() }.onFailure { Logs.w(it) }
                currentSession = null
            }
        }
        return results
    }

    fun cancel() {
        currentSession?.cancel()
    }

    companion object {
        const val STAGE_PENDING = "pending"
        const val STAGE_DISCOVERY = "discovery"
        const val STAGE_LATENCY = "latency"
        const val STAGE_DOWNLOAD = "download"
        const val STAGE_UPLOAD = "upload"
        const val STAGE_COMPLETE = "complete"
        const val STAGE_CANCELLED = "cancelled"
        const val STAGE_ERROR = "error"
    }
}

// Main-process session: the measurement itself runs in the :bg CoreService.
class RemoteSpeedTestSession(private val profile: ProxyEntity) : SpeedTestNodeSession {

    override suspend fun run(onSample: (SpeedTestSnapshot) -> Unit): SpeedTestSnapshot {
        return CoreServiceClient.speedTest(
            profile.id,
            DataStore.speedTestMode,
            DataStore.speedTestTimeoutMs,
            DataStore.simpleDownloadURL,
            onSample,
        )
    }

    override fun cancel() {
        runOnDefaultDispatcher {
            runCatching { CoreServiceClient.stopTests() }.onFailure { Logs.w(it) }
        }
    }

    override fun close() = Unit
}
