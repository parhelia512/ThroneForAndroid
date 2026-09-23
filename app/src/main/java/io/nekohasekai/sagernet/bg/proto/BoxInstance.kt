package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.StartOptions

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: GeneratedConfig
    lateinit var core: CoreConfig
    lateinit var box: Instance

    val boxOrNull: Instance? get() = if (::box.isInitialized) box else null

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected open fun buildConfig() {
        // random_inbound_port: a fresh port per start, saved so the app's own requests reach it (mainwindow_setup.cpp:193-196).
        if (DataStore.randomInboundPort) DataStore.inboundSocksPort = mkPort()
        config = CoreConfigs.buildMain(profile)
    }

    protected open suspend fun loadConfig() {
        box = Mobile.newInstance(CoreRuntime.platform, core.toStartOptions())
    }

    open suspend fun init() {
        buildConfig()
        core = CoreConfig.from(config, listOf(CoreConfig.TAG_PROXY))
        loadConfig()
    }

    override fun launch() {
        try {
            box.start()
        } catch (error: Throwable) {
            Logs.w("box start failed for profile ${profile.id}: ${error.message}")
            throw error
        }
    }

    override fun close() {
        boxOrNull?.close()
    }

}

internal fun CoreConfig.toStartOptions(): StartOptions = StartOptions().apply {
    coreConfig = this@toStartOptions.coreConfig
    needXray = this@toStartOptions.needXray
    xrayConfig = this@toStartOptions.xrayConfig ?: ""
    xrayOutboundDNSStrategy = xrayDnsStrategy
    this@toStartOptions.xrayFullConfigs.forEach(::addXrayFullConfig)
}
