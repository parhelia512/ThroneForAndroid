package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    /** Outbound tag -> the profiles whose traffic it carries: the `proxy` exit accounts for the started profile. */
    val trafficMap: Map<String, List<ProxyEntity>>
        get() = mapOf(CoreConfig.TAG_PROXY to listOf(profile))

    override fun buildConfig() {
        super.buildConfig()
        if (notTmp) {
            Logs.d(config.coreConfig)
            if (config.needXray) Logs.d(config.xrayConfig ?: "")
        }
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override fun launch() {
        super.launch() // start box
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        var closeError: Throwable? = null
        try {
            super.close()
        } catch (error: Throwable) {
            closeError = error
        }
        try {
            runBlocking {
                looper?.stop()
                looper = null
            }
        } catch (error: Throwable) {
            if (closeError == null) {
                closeError = error
            } else if (closeError !== error) {
                closeError?.addSuppressed(error)
            }
        }
        closeError?.let { throw it }
    }
}
