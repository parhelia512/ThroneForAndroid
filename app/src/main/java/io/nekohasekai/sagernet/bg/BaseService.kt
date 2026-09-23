package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.urlTestCurrent
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val box = proxy?.boxOrNull ?: return@broadcastReceiver
                    if (SagerNet.power.isDeviceIdleMode) {
                        box.pause()
                    } else {
                        box.wake()
                        if (DataStore.wakeResetConnections) {
                            box.resetNetwork()
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    proxy?.boxOrNull?.resetNetwork()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            val proxy = data?.proxy?.takeIf { it.isInitialized() } ?: error("core not started")
            try {
                return runBlocking {
                    urlTestCurrent(
                        proxy.box, proxy.core, DataStore.testUrl, DataStore.urlTestTimeoutMs
                    )
                }
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        /**
         * Instance.updateRuleSets (core/internal/rulesets UpdateAll): every remote rule-set of the running instance,
         * at most 5 at a time within 60 s. Arrives on a binder thread, so blocking that long is fine.
         */
        override fun updateRuleSets(): String = try {
            val box = data?.takeIf { it.state.connected }?.proxy?.boxOrNull
            if (box == null) {
                ruleSetUpdateJson(0, "not running")
            } else {
                val update = box.updateRuleSets()
                ruleSetUpdateJson(update.updated, update.error ?: "")
            }
        } catch (e: Throwable) {
            ruleSetUpdateJson(0, e.readableMessage)
        }

        private fun ruleSetUpdateJson(updated: Int, error: String): String =
            jsonObjectOf("updated" to updated, "error" to error).toCompact()

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        fun onProxySelected(ent: ProxyEntity) {
            data.proxy?.boxOrNull?.resetNetwork()
            runOnDefaultDispatcher {
                data.proxy?.apply {
                    looper?.selectMain(ent.id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                data.binder.broadcast { it.cbSelectorUpdate(ent.id) }
            }
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun killProcesses(): Throwable? {
            val proxy = data.proxy
            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            var cleanupError: Throwable? = null
            fun recordCleanupFailure(stage: String, error: Throwable) {
                if (cleanupError == null) {
                    cleanupError = error
                } else if (cleanupError !== error) {
                    cleanupError?.addSuppressed(error)
                }
                Logs.w(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=$stage failed " +
                        "type=${error.javaClass.name} message=${error.message}"
                )
            }
            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill begin"
            )
            try {
                proxy?.close()
                Logs.i(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=proxy-close success"
                )
            } catch (error: Throwable) {
                recordCleanupFailure("proxy-close", error)
            }

            try {
                wakeLock?.release()
            } catch (error: Throwable) {
                recordCleanupFailure("wake-lock-release", error)
            } finally {
                wakeLock = null
            }

            try {
                DefaultNetworkListener.stop(this)
            } catch (error: Throwable) {
                recordCleanupFailure("network-listener-stop", error)
            }

            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill done " +
                    "hasCleanupError=${cleanupError != null}"
            )
            return cleanupError
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null

            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxy = data.proxy
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            val caller = Thread.currentThread().stackTrace.firstOrNull { frame ->
                frame.className != Thread::class.java.name && frame.methodName != "stopRunner"
            }?.let { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
                ?: "unknown"
            Logs.i(
                "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId restart=$restart " +
                    "state=${data.state} profileId=${proxy?.profile?.id ?: -1L} " +
                    "hasMessage=${msg != null} caller=$caller"
            )
            if (data.state == State.Stopping) {
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=ignored-already-stopping"
                )
                return
            }
            this as Service

            data.changeState(State.Stopping)
            val originalMessage = msg

            runOnMainDispatcher {
                var cleanupError: Throwable? = null
                fun recordCleanupFailure(stage: String, error: Throwable) {
                    if (cleanupError == null) {
                        cleanupError = error
                    } else if (cleanupError !== error) {
                        cleanupError?.addSuppressed(error)
                    }
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=$stage failed type=${error.javaClass.name} " +
                            "message=${error.message}"
                    )
                }

                try {
                    data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                } catch (error: Throwable) {
                    recordCleanupFailure("connecting-job-cancel", error)
                } finally {
                    data.connectingJob = null
                }

                try {
                    data.notification?.destroy()
                } catch (error: Throwable) {
                    recordCleanupFailure("notification-destroy", error)
                } finally {
                    data.notification = null
                }

                try {
                    killProcesses()?.let { recordCleanupFailure("process-cleanup", it) }
                } catch (error: Throwable) {
                    recordCleanupFailure("process-cleanup-boundary", error)
                }

                try {
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                    }
                } catch (error: Throwable) {
                    recordCleanupFailure("receiver-unregister", error)
                } finally {
                    data.closeReceiverRegistered = false
                    data.proxy = null
                }

                cleanupError?.let { error ->
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=cleanup failed type=${error.javaClass.name} " +
                            "message=${error.message} suppressed=${error.suppressed.size} " +
                            "originalMessagePreserved=${originalMessage != null}"
                    )
                }

                try {
                    data.changeState(State.Stopped, originalMessage)
                } catch (error: Throwable) {
                    recordCleanupFailure("state-stopped", error)
                }
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=stopped restart=$restart hasCleanupError=${cleanupError != null}"
                )

                try {
                    // stop the service if nothing has bound to it
                    if (restart) startRunner() else {
                        stopSelf()
                    }
                } catch (error: Throwable) {
                    recordCleanupFailure("service-finish", error)
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            // Tracks the underlying network for VpnService.setUnderlyingNetworks only; the
            // "reset on network change" switch is applied by NativeInterface's monitor callback.
            DefaultNetworkListener.start(this) { network ->
                if (network == null) return@start
                SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        Logs.d("Network changed: $oldName -> ${link.interfaceName}")
                        upstreamInterfaceName = link.interfaceName
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.rememberEnable
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            runOnMainDispatcher {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    startProcesses()
                    data.changeState(State.Connected)

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (exc: Throwable) {
                    // gomobile surfaces Go errors as go.Universe$proxyerror: message only, no stack worth logging
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }

}
