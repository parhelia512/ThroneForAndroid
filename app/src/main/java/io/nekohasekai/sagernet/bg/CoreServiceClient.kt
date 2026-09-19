package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.nekohasekai.sagernet.aidl.ICoreService
import io.nekohasekai.sagernet.aidl.ICoreTestCallback
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.completeWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference

// Main-process client of the :bg CoreService; each call binds for its own duration.
object CoreServiceClient {

    private class Connection : ServiceConnection, IBinder.DeathRecipient {
        val service = CompletableDeferred<ICoreService>()

        @Volatile
        var onDied: (() -> Unit)? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            runCatching { binder.linkToDeath(this, 0) }
            service.complete(ICoreService.Stub.asInterface(binder))
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()

        override fun binderDied() = died()

        private fun died() {
            service.completeExceptionally(IllegalStateException("core service died"))
            onDied?.invoke()
        }
    }

    private suspend fun <T> withService(block: suspend (ICoreService, Connection) -> T): T {
        val connection = Connection()
        val bound = app.bindService(
            Intent(app, CoreService::class.java), connection, Context.BIND_AUTO_CREATE
        )
        if (!bound) {
            runCatching { app.unbindService(connection) }
            error("cannot bind core service")
        }
        try {
            return block(connection.service.await(), connection)
        } finally {
            runCatching { app.unbindService(connection) }
        }
    }

    suspend fun urlTest(
        profileIds: LongArray,
        url: String,
        timeoutMs: Int,
        concurrency: Int,
        onResult: (profileId: Long, latencyMs: Int, error: String) -> Unit,
    ) = withService { service, connection ->
        suspendCancellableCoroutine { continuation ->
            connection.onDied = {
                continuation.completeWith(Result.failure(IllegalStateException("core service died")))
            }
            continuation.invokeOnCancellation { runCatching { service.stopTests() } }
            service.urlTest(profileIds, url, timeoutMs, concurrency, object : ICoreTestCallback.Stub() {
                override fun onUrlTestResult(profileId: Long, latencyMs: Int, error: String?) {
                    onResult(profileId, latencyMs, error.orEmpty())
                }

                override fun onSpeedTestProgress(snapshot: SpeedTestSnapshot?) = Unit

                override fun onDone() {
                    continuation.completeWith(Result.success(Unit))
                }
            })
        }
    }

    suspend fun speedTest(
        profileId: Long,
        mode: String,
        timeoutMs: Int,
        simpleDownloadUrl: String,
        onProgress: (SpeedTestSnapshot) -> Unit,
    ): SpeedTestSnapshot = withService { service, connection ->
        suspendCancellableCoroutine { continuation ->
            val last = AtomicReference<SpeedTestSnapshot?>(null)
            connection.onDied = {
                continuation.completeWith(Result.failure(IllegalStateException("core service died")))
            }
            continuation.invokeOnCancellation { runCatching { service.stopTests() } }
            service.speedTest(profileId, mode, timeoutMs, simpleDownloadUrl, object : ICoreTestCallback.Stub() {
                override fun onUrlTestResult(profileId: Long, latencyMs: Int, error: String?) = Unit

                override fun onSpeedTestProgress(snapshot: SpeedTestSnapshot?) {
                    if (snapshot == null) return
                    last.set(snapshot)
                    onProgress(snapshot)
                }

                override fun onDone() {
                    val result = last.get()
                    if (result == null) {
                        continuation.completeWith(Result.failure(IllegalStateException("speed test produced no result")))
                    } else {
                        continuation.completeWith(Result.success(result))
                    }
                }
            })
        }
    }

    suspend fun stopTests() = withService { service, _ -> service.stopTests() }

}
