package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.nekohasekai.sagernet.aidl.ICoreService
import io.nekohasekai.sagernet.aidl.ICoreTestCallback
import io.nekohasekai.sagernet.bg.proto.CoreSpeedTestSession
import io.nekohasekai.sagernet.bg.proto.CoreUrlTest
import io.nekohasekai.sagernet.bg.proto.SpeedTestQueueRunner
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.throneproj.mobile.Mobile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

// Always-bindable :bg host for the probe boxes, so the main process never loads the core.
class CoreService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = object : ICoreService.Stub() {

        override fun urlTest(
            profileIds: LongArray,
            url: String,
            timeoutMs: Int,
            concurrency: Int,
            cb: ICoreTestCallback,
        ) {
            scope.launch {
                try {
                    CoreUrlTest(profileIds.toList(), url, timeoutMs, concurrency).run { id, latency, error ->
                        cb.onUrlTestResult(id, latency, error)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    val error = e.readableMessage
                    for (id in profileIds) runCatching { cb.onUrlTestResult(id, -1, error) }
                } finally {
                    runCatching { cb.onDone() }
                }
            }
        }

        override fun speedTest(
            profileId: Long,
            mode: String,
            timeoutMs: Int,
            simpleDownloadUrl: String,
            cb: ICoreTestCallback,
        ) {
            scope.launch {
                try {
                    val profile = SagerDatabase.proxyDao.getById(profileId)
                    if (profile == null) {
                        cb.onSpeedTestProgress(
                            SpeedTestSnapshot(
                                profileId = profileId,
                                profileName = "",
                                mode = mode,
                                stage = SpeedTestQueueRunner.STAGE_ERROR,
                                error = "profile $profileId not found",
                                done = true,
                            )
                        )
                        return@launch
                    }
                    try {
                        CoreSpeedTestSession(profile, mode, timeoutMs, simpleDownloadUrl).run {
                            cb.onSpeedTestProgress(it)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                        cb.onSpeedTestProgress(
                            SpeedTestSnapshot(
                                profileId = profileId,
                                profileName = profile.displayName(),
                                mode = mode,
                                stage = SpeedTestQueueRunner.STAGE_ERROR,
                                error = e.readableMessage,
                                done = true,
                            )
                        )
                    }
                } finally {
                    runCatching { cb.onDone() }
                }
            }
        }

        override fun stopTests() {
            Mobile.stopTests()
            scope.coroutineContext.cancelChildren()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
