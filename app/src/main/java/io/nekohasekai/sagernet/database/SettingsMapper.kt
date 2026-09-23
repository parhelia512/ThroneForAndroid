package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.config.GeneratorSettings
import io.nekohasekai.sagernet.outbound.config.RoutingInput

/**
 * DataStore -> the config generator's settings and build-time globals, and RouteManager -> its routing input. The
 * UI process (config export) and the :bg process (running instances, URL / speed tests) map them identically.
 * Every desktop key maps onto the field that names it; the Android-only inputs are the service mode, the per-app
 * lists and the HTTP proxy bypass list.
 */
object SettingsMapper {

    /** The route profile of current_route_id (the first profile when it is gone) and the rule-set list. */
    fun routingInput(): RoutingInput = RoutingInput(RouteManager.current(), RouteManager.catalog())

    fun generatorSettings(): GeneratorSettings {
        val mixedEnabled = !DataStore.mixedInboundDisabled
        val clashApi = DataStore.coreBoxClashApi
        return GeneratorSettings(
            logLevel = DataStore.logLevel,
            ntpEnabled = DataStore.enableNtp,
            ntpServer = DataStore.ntpServerAddress,
            ntpServerPort = DataStore.ntpServerPort,
            ntpInterval = DataStore.ntpInterval,
            ntpOutbound = DataStore.ntpOutbound,
            useMozillaCerts = DataStore.useMozillaCerts,
            dnsInPort = DataStore.coreDnsInPort,
            mixedInboundEnabled = mixedEnabled,
            inboundAddress = DataStore.inboundAddress.ifBlank { SettingsRegistry.LOOPBACK_ADDRESS },
            mixedPort = DataStore.inboundSocksPort,
            mixedAuth = DataStore.inboundAuth,
            mixedUsername = DataStore.inboundUser,
            mixedPassword = DataStore.inboundPass,
            customInboundJson = DataStore.customInbound,
            vpnMode = DataStore.serviceMode == Key.MODE_VPN,
            tunMtu = DataStore.vpnMtu,
            tunStack = DataStore.vpnImpl,
            // strict_route is fixed on Android (decision D7).
            tunStrictRoute = true,
            tunIPv4Cidr = DataStore.vpnTunIpv4Cidr,
            tunIPv6Cidr = DataStore.vpnTunIpv6Cidr,
            ipv6Enabled = DataStore.vpnIpv6,
            bypassLan = !DataStore.disablePrivateRangeBypass,
            privateRanges = DataStore.vpnPrivateRanges,
            perAppEnabled = DataStore.proxyApps,
            perAppBypass = DataStore.bypass,
            perAppPackages = GeneratorSettings.splitLines(DataStore.individual),
            httpProxyEnabled = mixedEnabled,
            httpProxyBypassDomains = GeneratorSettings.splitLines(DataStore.httpProxyBypass),
            remoteDns = DataStore.remoteDns,
            remoteDnsDisableIpv6 = DataStore.remoteDnsDisableIpv6,
            directDns = DataStore.directDns,
            underlyingDns = DataStore.coreBoxUnderlyingDns,
            dnsCacheCapacity = DataStore.dnsCacheCapacity,
            dnsDisableCache = DataStore.dnsDisableCache,
            dnsDisableExpire = DataStore.dnsDisableExpire,
            dnsPersistCache = DataStore.dnsPersistCache,
            dnsReverseMapping = DataStore.dnsReverseMapping,
            dnsObject = DataStore.dnsObject,
            dnsFinalOut = DataStore.dnsFinalOut,
            dnsOptimistic = DataStore.dnsOptimistic,
            dnsOptimisticTimeout = DataStore.dnsOptimisticTimeout,
            dnsQueryTimeout = DataStore.dnsQueryTimeout,
            dnsUseHosts = DataStore.dnsUseHosts,
            dnsPredefinedEnable = DataStore.dnsPredefinedEnable,
            dnsPredefinedRules = DataStore.dnsPredefinedRules,
            fakeDns = DataStore.fakedns,
            fakeIpDisableIpv6 = DataStore.fakeipDisableIpv6,
            resolveDomainStrategy = DataStore.domainStrategy,
            rulesetMirror = DataStore.rulesetMirror,
            adblockEnable = DataStore.adblockEnable,
            enableDnsRouting = DataStore.enableDnsRouting,
            enableTunRouting = DataStore.enableTunRouting,
            trafficStats = DataStore.enableStats,
            apiSecret = DataStore.coreBoxApiSecret,
            clashApiEnabled = clashApi > 0,
            clashApiListen = DataStore.coreBoxClashListenAddr,
            clashApiPort = if (clashApi > 0) clashApi else -clashApi,
            clashApiSecret = DataStore.coreBoxClashApiSecret,
            xrayLogLevel = DataStore.xrayLogLevel,
        )
    }

    /** DataStore -> BuildContext: the preset globals and the domain strategies. TLS spoof is never mapped (decision D8). */
    fun buildContext(): BuildContext = BuildContext(
        skipCert = DataStore.skipCert,
        utlsFingerprint = DataStore.utlsFingerprint,
        muxDefaultOn = DataStore.muxDefaultOn,
        muxProtocol = DataStore.muxProtocol,
        muxConcurrency = DataStore.muxConcurrency,
        muxPadding = DataStore.muxPadding,
        fragmentImplementation = DataStore.fragmentImplementation,
        fragmentDefaultOn = DataStore.fragmentDefaultOn,
        fragmentSize = DataStore.fragmentSize.ifBlank { SettingsRegistry.FRAGMENT_SIZE.default },
        fragmentSleep = DataStore.fragmentSleep.ifBlank { SettingsRegistry.FRAGMENT_SLEEP.default },
        tlsTricksDefaultOn = DataStore.tlsTricksDefaultOn,
        h2IdleTimeout = DataStore.h2IdleTimeout,
        h2KeepAlivePeriod = DataStore.h2KeepAlivePeriod,
        h2StreamReceiveWindow = DataStore.h2StreamReceiveWindow,
        h2ConnectionReceiveWindow = DataStore.h2ConnectionReceiveWindow,
        h2MaxConcurrentStreams = DataStore.h2MaxConcurrentStreams,
        quicInitialPacketSize = DataStore.quicInitialPacketSize,
        quicDisablePathMtuDiscovery = DataStore.quicDisablePathMtuDiscovery,
        xrayMuxConcurrency = DataStore.xrayMuxConcurrency,
        xrayMuxDefaultOn = DataStore.xrayMuxDefaultOn,
        defaultDomainStrategy = DataStore.outboundDomainStrategy,
        directDnsDisableIpv6 = DataStore.directDnsDisableIpv6,
        useDnsObject = DataStore.useDnsObject,
    )

    /** xray_vless_preference for link and subscription imports (utils.cpp:50-70). */
    fun xrayVlessPreference(): OutboundFactory.XrayVlessPreference =
        OutboundFactory.XrayVlessPreference.entries.getOrNull(DataStore.xrayVlessPreference)
            ?: OutboundFactory.DEFAULT_XRAY_VLESS_PREFERENCE
}
