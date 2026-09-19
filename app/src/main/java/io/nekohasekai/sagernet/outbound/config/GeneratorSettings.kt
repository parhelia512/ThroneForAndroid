package io.nekohasekai.sagernet.outbound.config

/**
 * Every setting the config skeleton reads (include/database/SettingsRepo.h), with the desktop default wherever the
 * Android app has no equivalent. Each field names the desktop key and, where one exists, the DataStore property the
 * storage layer maps it from. `direct_dns_disable_ipv6` and `use_dns_object` are read from
 * [io.nekohasekai.sagernet.outbound.BuildContext] (they also drive the domain strategies there), not duplicated here.
 */
data class GeneratorSettings(
    // ---- log
    /** log_level (SettingsRepo.h:67) <- DataStore.logLevel through [logLevelName]. */
    val logLevel: String = "info",

    // ---- ntp (SettingsRepo.h:245-249), no Android equivalent
    val ntpEnabled: Boolean = false,
    val ntpServer: String = "",
    val ntpServerPort: Int = 0,
    val ntpInterval: String = "",
    /** "direct" or "proxy". */
    val ntpOutbound: String = "direct",

    // ---- certificate
    /** use_mozilla_certs (SettingsRepo.h:164), no Android equivalent. */
    val useMozillaCerts: Boolean = false,

    // ---- inbounds
    /** core_dns_in_port (SettingsRepo.h:297). */
    val dnsInPort: Int = 5533,
    /** !disable_mixed_inbound (SettingsRepo.h:203) <- !DataStore.mixedInboundDisabled. */
    val mixedInboundEnabled: Boolean = true,
    /** inbound_address (SettingsRepo.h:204): "0.0.0.0" when true, "127.0.0.1" otherwise <- DataStore.allowAccess. */
    val allowLanAccess: Boolean = false,
    /** inbound_socks_port (SettingsRepo.h:205) <- DataStore.mixedPort. */
    val mixedPort: Int = 2080,
    /** inbound_auth / inbound_user / inbound_pass (SettingsRepo.h:208-210) <- DataStore.mixedInboundNeedsAuth, mixedUsername, mixedPassword. */
    val mixedAuth: Boolean = false,
    val mixedUsername: String = "",
    val mixedPassword: String = "",
    /** custom_inbound (SettingsRepo.h:207): a JSON object whose `inbounds` array is appended verbatim; no Android equivalent. */
    val customInboundJson: String = "",

    // ---- tun (only in VPN mode)
    /** spmode_vpn (SettingsRepo.h:48) <- DataStore.serviceMode == Key.MODE_VPN. */
    val vpnMode: Boolean = false,
    /** vpn_mtu (SettingsRepo.h:236) <- DataStore.mtu. */
    val tunMtu: Int = 9000,
    /** vpn_implementation (SettingsRepo.h:222-231) <- DataStore.tunImplementation through [tunStackName]. */
    val tunStack: String = "gvisor",
    /** vpn_strict_route (SettingsRepo.h:222-231) <- DataStore.strictRoute. */
    val tunStrictRoute: Boolean = true,
    /** vpn_tun_ipv4_cidr (SettingsRepo.h:240); the app's layout is VpnService.PRIVATE_VLAN4_CLIENT/28. */
    val tunIPv4Cidr: String = "172.19.0.1/28",
    /** vpn_tun_ipv6_cidr (SettingsRepo.h:241); VpnService.PRIVATE_VLAN6_CLIENT/126. */
    val tunIPv6Cidr: String = "fdfe:dcba:9876::1/126",
    /** vpn_ipv6 (SettingsRepo.h:239) <- DataStore.ipv6Mode != IPv6Mode.DISABLE. */
    val ipv6Enabled: Boolean = false,
    /** !disable_private_range_bypass (SettingsRepo.h:237) <- DataStore.bypassLan. */
    val bypassLan: Boolean = true,
    /** vpn_private_ranges (SettingsRepo.h:238, defaultTunPrivateRanges); no Android equivalent. */
    val privateRanges: List<String> = DEFAULT_PRIVATE_RANGES,
    /** DataStore.proxyApps: whether [perAppPackages] is applied at all. */
    val perAppEnabled: Boolean = false,
    /** DataStore.bypass: true excludes the listed packages from the tun (`exclude_package`), false routes only them (`include_package`). */
    val perAppBypass: Boolean = true,
    /** DataStore.individual split on newlines; the caller adds or removes its own package as it sees fit. */
    val perAppPackages: List<String> = emptyList(),
    /** platform.http_proxy.enabled: the app always pointed the system HTTP proxy at the mixed inbound (VpnService.kt:214-224). */
    val httpProxyEnabled: Boolean = true,
    /** platform.http_proxy.bypass_domain <- DataStore.httpProxyBypass split on newlines. */
    val httpProxyBypassDomains: List<String> = emptyList(),

    // ---- dns (SettingsRepo.h:179-197, :296)
    /** remote_dns <- DataStore.remoteDns (desktop address syntax, see DnsServers.buildDnsObj). */
    val remoteDns: String = "https://8.8.8.8/dns-query",
    /** remote_dns_disable_ipv6 <- DataStore.ipv6Mode == IPv6Mode.DISABLE. */
    val remoteDnsDisableIpv6: Boolean = false,
    /** direct_dns <- DataStore.directDns. */
    val directDns: String = "localhost",
    /** core_box_underlying_dns: the dns-local server, "" means "local". */
    val underlyingDns: String = "",
    /** dns_cache_capacity. */
    val dnsCacheCapacity: Int = 65536,
    val dnsDisableCache: Boolean = false,
    val dnsDisableExpire: Boolean = false,
    /** dns_persist_cache: experimental.cache_file.store_fakeip / store_dns. */
    val dnsPersistCache: Boolean = false,
    val dnsReverseMapping: Boolean = false,
    /** dns_object: the raw dns section used when BuildContext.useDnsObject is set (never for tests). */
    val dnsObject: String = "",
    /** dns_final_out: "remote" or "direct". */
    val dnsFinalOut: String = "remote",
    val dnsOptimistic: Boolean = false,
    val dnsOptimisticTimeout: String = "",
    val dnsQueryTimeout: String = "",
    val dnsUseHosts: Boolean = false,
    /** dns_predefined_enable / dns_predefined_rules (hosts-file lines). */
    val dnsPredefinedEnable: Boolean = true,
    val dnsPredefinedRules: List<String> = listOf("127.0.0.1 localhost"),
    /** fake_dns (SettingsRepo.h:219) <- DataStore.enableFakeDns. */
    val fakeDns: Boolean = false,
    /** fakeip_disable_ipv6 (SettingsRepo.h:220) <- DataStore.ipv6Mode == IPv6Mode.DISABLE. */
    val fakeIpDisableIpv6: Boolean = false,

    // ---- route
    /** resolve_domain_strategy (SettingsRepo.h:198): adds the `resolve` rule for mixed-in / tun-in when non-empty. */
    val resolveDomainStrategy: String = "",
    /** enable_stats (SettingsRepo.h:118) <- DataStore.profileTrafficStatistics: route.find_process and the api service. */
    val trafficStats: Boolean = true,

    // ---- experimental (SettingsRepo.h:290-292)
    /** core_box_clash_api > 0 <- DataStore.enableClashAPI. */
    val clashApiEnabled: Boolean = false,
    val clashApiListen: String = "127.0.0.1",
    val clashApiPort: Int = 9090,
    val clashApiSecret: String = "",
    /** external_ui; the desktop always sends "dashboard", "" omits the key. */
    val clashApiExternalUi: String = "dashboard",

    // ---- xray
    /** xray_log_level (SettingsRepo.h:300). */
    val xrayLogLevel: String = "warning",
) {
    /** inbound_address as the desktop stores it. */
    val mixedListen: String get() = if (allowLanAccess) "0.0.0.0" else "127.0.0.1"

    companion object {
        /** defaultTunPrivateRanges (SettingsRepo.h:18-20). */
        @JvmField
        val DEFAULT_PRIVATE_RANGES: List<String> =
            listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4")

        /** DataStore.logLevel (the index of the app's log-level list) to the core's level name. */
        @JvmStatic
        fun logLevelName(level: Int): String = when (level) {
            0 -> "panic"
            1 -> "warn"
            2 -> "info"
            3 -> "debug"
            4 -> "trace"
            else -> "info"
        }

        /** DataStore.tunImplementation (TunImplementation.GVISOR / SYSTEM / MIXED) to the tun `stack` name. */
        @JvmStatic
        fun tunStackName(implementation: Int): String = when (implementation) {
            1 -> "system"
            2 -> "mixed"
            else -> "gvisor"
        }

        /** A newline separated DataStore list (individual, httpProxyBypass) with blanks and `#` comments dropped. */
        @JvmStatic
        fun splitLines(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
