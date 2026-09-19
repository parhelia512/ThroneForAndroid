package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.config.GeneratorSettings

/**
 * DataStore -> the config generator's settings and build-time globals. It reads nothing but DataStore, so the UI
 * process (config export) and the :bg process (running instances, URL / speed tests) map the keys identically.
 */
object SettingsMapper {

    /**
     * DataStore -> GeneratorSettings (the property docs in GeneratorSettings name the desktop key):
     * logLevel <- logLevel, mixedInboundEnabled <- !mixedInboundDisabled, allowLanAccess <- allowAccess,
     * mixedPort <- mixedPort, mixedAuth/user/pass <- mixedInboundNeedsAuth/mixedUsername/mixedPassword,
     * vpnMode <- serviceMode == vpn, tunMtu <- mtu, tunStack <- tunImplementation, tunStrictRoute <- strictRoute,
     * ipv6Enabled / remoteDnsDisableIpv6 / fakeIpDisableIpv6 <- ipv6Mode, bypassLan <- bypassLan,
     * perAppEnabled/perAppBypass/perAppPackages <- proxyApps/bypass/individual, httpProxyEnabled <- the mixed
     * inbound being on, httpProxyBypassDomains <- httpProxyBypass, remoteDns/directDns <- remoteDns/directDns,
     * fakeDns <- enableFakeDns, resolveDomainStrategy <- resolveDestination (+ ipv6Mode), trafficStats <-
     * profileTrafficStatistics, clashApiEnabled <- enableClashAPI. Everything else keeps the desktop default.
     */
    fun generatorSettings(): GeneratorSettings {
        val ipv6Mode = DataStore.ipv6Mode
        val ipv6Disabled = ipv6Mode == IPv6Mode.DISABLE
        val mixedEnabled = !DataStore.mixedInboundDisabled
        return GeneratorSettings(
            logLevel = GeneratorSettings.logLevelName(DataStore.logLevel),
            mixedInboundEnabled = mixedEnabled,
            allowLanAccess = DataStore.allowAccess,
            mixedPort = DataStore.mixedPort,
            mixedAuth = DataStore.mixedInboundNeedsAuth,
            mixedUsername = DataStore.mixedUsername,
            mixedPassword = DataStore.mixedPassword,
            vpnMode = DataStore.serviceMode == Key.MODE_VPN,
            tunMtu = DataStore.mtu,
            tunStack = GeneratorSettings.tunStackName(DataStore.tunImplementation),
            tunStrictRoute = DataStore.strictRoute,
            ipv6Enabled = !ipv6Disabled,
            bypassLan = DataStore.bypassLan,
            perAppEnabled = DataStore.proxyApps,
            perAppBypass = DataStore.bypass,
            perAppPackages = GeneratorSettings.splitLines(DataStore.individual),
            httpProxyEnabled = mixedEnabled,
            httpProxyBypassDomains = GeneratorSettings.splitLines(DataStore.httpProxyBypass),
            remoteDns = DataStore.remoteDns,
            remoteDnsDisableIpv6 = ipv6Disabled,
            directDns = DataStore.directDns,
            fakeDns = DataStore.enableFakeDns,
            fakeIpDisableIpv6 = ipv6Disabled,
            resolveDomainStrategy = if (!DataStore.resolveDestination) "" else when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> "prefer_ipv4"
            },
            trafficStats = DataStore.profileTrafficStatistics,
            clashApiEnabled = DataStore.enableClashAPI,
        )
    }

    /**
     * DataStore -> BuildContext: skipCert <- globalAllowInsecure, fragmentDefaultOn <- enableTLSFragment,
     * fragmentSize <- fragmentLength, fragmentSleep <- fragmentInterval, directDnsDisableIpv6 <- ipv6Mode ==
     * disable. The mux, uTLS, spoof, QUIC and Xray mux globals have no Android key and keep the desktop defaults.
     */
    fun buildContext(): BuildContext = BuildContext(
        skipCert = DataStore.globalAllowInsecure,
        fragmentDefaultOn = DataStore.enableTLSFragment,
        fragmentSize = DataStore.fragmentLength.ifBlank { "10-100" },
        fragmentSleep = DataStore.fragmentInterval.ifBlank { "2-5" },
        directDnsDisableIpv6 = DataStore.ipv6Mode == IPv6Mode.DISABLE,
    )
}
