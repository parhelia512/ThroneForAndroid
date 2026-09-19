package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf

/**
 * The desktop's config generator (src/configs/generate.cpp) for the Android app: [build] is BuildSingBoxConfig
 * (:2302-2378) for one selected profile with the built-in Default route profile, [buildTest] is BuildTestConfig
 * (:2527-2676) for a batch of URL-test candidates. Sections are emitted in the desktop's order and serialised
 * compact with sorted keys, so a config matches the desktop's byte for byte wherever the inputs match.
 *
 * Not generated in phase 1 (all desktop-only or out of scope): WARP, route-profile outbounds and rule sets, the
 * `hijack` / `hijack-dns` inbounds, extra cores, auxiliary VPN endpoints and the OpenVPN / OpenConnect tunnel DNS
 * servers, the L3 bridge, the api dashboard, Tailscale and auto-selector profiles.
 */
class ConfigGenerator @JvmOverloads constructor(
    private val profiles: ProfileProvider,
    private val settings: GeneratorSettings,
    private val buildContext: BuildContext = BuildContext.DEFAULT,
) {

    /**
     * The config for the started profile [profileId]; [landingProxyId] becomes the exit and [frontProxyId] the
     * entry of the main chain when > 0 (the group's landing / front proxy, generate.cpp:1806-1823).
     */
    @JvmOverloads
    fun build(profileId: Long, landingProxyId: Long = -1, frontProxyId: Long = -1): GeneratedConfig {
        val profile = profiles.get(profileId) ?: return GeneratedConfig.failure("Profile $profileId does not exist")
        if (profile.invalid) return GeneratedConfig.failure("Profile $profileId has a type this build cannot use: ${profile.type}")
        // A custom full config is the whole core config (:2302-2334).
        val custom = TypeAccess.asCustom(profile)
        if (custom != null && custom.isFullConfig()) {
            val core = custom.build(buildContext).json
            if (core.isEmpty()) return GeneratedConfig.failure("Custom full config is not a valid JSON object")
            return GeneratedConfig(
                coreConfig = core.toCompact(),
                xrayConfig = null,
                needXray = false,
                xrayDnsStrategy = "",
                xrayFullConfigs = emptyList(),
                outboundTags = emptyList(),
                tagToProfileId = emptyMap(),
                fullConfigs = emptyMap(),
                skipped = emptyMap(),
                tunIPv4Cidr = tunIPv4CidrOf(core),
                error = null,
            )
        }

        val state = BuildState(forTest = false)
        val chains = ChainBuilder(profiles, buildContext, state)
        // calculatePrerequisites (:541-753) with the Default route profile leaves only the Xray decision (:578, :710-719).
        state.proxyUsesXray = chains.proxyPathUsesXray(profile)
        for (id in listOf(frontProxyId, landingProxyId)) {
            if (id <= 0) continue
            val groupProxy = profiles.get(id) ?: continue
            if (chains.usesXrayCore(groupProxy)) state.proxyUsesXray = true
        }

        buildLogSection(state)
        buildNtpSection(state)
        buildCertificateSection(state)
        buildInboundSection(state)
        buildOutboundsSection(state, chains, profile, profileId, landingProxyId, frontProxyId)
        if (state.failed) return GeneratedConfig.failure(state.error)
        buildDnsSection(state, useDnsObj = true)
        buildRouteSection(state)
        if (state.failed) return GeneratedConfig.failure(state.error)
        buildExperimentalSection(state)
        buildServicesSection(state)
        buildXrayConfig(state)
        if (state.failed) return GeneratedConfig.failure(state.error)

        return GeneratedConfig(
            coreConfig = state.coreConfig.toCompact(),
            xrayConfig = if (state.isXrayNeeded) state.xrayConfig.toCompact() else null,
            needXray = state.isXrayNeeded,
            xrayDnsStrategy = if (state.isXrayNeeded) buildContext.xrayOutboundDomainStrategy() else "",
            xrayFullConfigs = emptyList(),
            outboundTags = emptyList(),
            tagToProfileId = emptyMap(),
            fullConfigs = emptyMap(),
            skipped = emptyMap(),
            tunIPv4Cidr = state.tunIPv4Cidr,
            error = null,
        )
    }

    /** [buildTest] for candidates that share one group's landing / front proxy. */
    @JvmOverloads
    fun buildTestForIds(candidateIds: List<Long>, landingProxyId: Long = -1, frontProxyId: Long = -1): GeneratedConfig =
        buildTest(candidateIds.map { TestCandidate(it, landingProxyId, frontProxyId) })

    /**
     * One test box for every candidate at once (BuildTestConfig, :2527-2676): candidate n is a chain under the
     * prefix `proxy-<n>` whose ingress tag `proxy-<n>-0` is reported in [GeneratedConfig.outboundTags]; there is no
     * `proxy` tag, no inbounds except the Xray -> sing-box bridges, no experimental or services section, and the
     * DNS falls through to dns-direct. Xray candidates share one Xray config; custom Xray full configs each get
     * their own instance ([GeneratedConfig.xrayFullConfigs]).
     */
    fun buildTest(candidates: List<TestCandidate>): GeneratedConfig {
        val ctx = buildContext.copy(buildingTestConfig = true)
        val state = BuildState(forTest = true)
        val chains = ChainBuilder(profiles, ctx, state)
        buildDnsSection(state, useDnsObj = false)
        buildLogSection(state)
        buildCertificateSection(state)
        buildNtpSection(state)

        val outboundTags = ArrayList<String>()
        val tagToProfileId = LinkedHashMap<String, Long>()
        val fullConfigs = LinkedHashMap<Long, String>()
        val xrayFullConfigs = ArrayList<String>()
        val skipped = LinkedHashMap<Long, String>()

        val resolved = candidates.map { it to profiles.get(it.id) }
        var xrayCount = 0
        var chainCount = 0
        for ((_, outbound) in resolved) {
            if (outbound == null) continue
            if (outbound.isXray()) xrayCount++
            if (outbound.type == "chain") chainCount++
        }
        // Reserved in one batch so no two candidates collide; every chain is assumed to transition twice (:2553-2554).
        val xrayPorts = LocalPorts.reserve(xrayCount + 2 * chainCount)
        var xrayPortIdx = 0
        var suffix = 1

        for ((candidate, outbound) in resolved) {
            val id = candidate.id
            if (outbound == null) {
                skipped[id] = "Profile does not exist"
                continue
            }
            val skipReason = classifyTestCandidate(outbound)
            if (skipReason != null) {
                skipped[id] = skipReason
                continue
            }
            if (outbound.invalid) {
                skipped[id] = "Unsupported profile type: ${outbound.type}"
                continue
            }
            if (outbound.isXrayFullConfig()) {
                val invalid = chains.candidateError(listOf(id))
                if (invalid.isNotEmpty()) {
                    skipped[id] = invalid
                    continue
                }
                // The single xrayConfig slot is drained per full config so they all share one sing-box (:2571-2587).
                val tag = chains.buildOutboundChain(ChainRequest(listOf(id), prefix = "${Tags.TEST_XRAY_FULL_PREFIX}-$id"))
                if (state.failed) return GeneratedConfig.failure(state.error)
                if (!state.isXrayNeeded || state.xrayConfig.isEmpty()) {
                    skipped[id] = "Custom Xray full config produced no Xray config"
                    continue
                }
                xrayFullConfigs.add(state.xrayConfig.toCompact())
                state.xrayConfig = JsonObject()
                state.isXrayNeeded = false
                outboundTags.add(tag)
                tagToProfileId[tag] = id
                continue
            }
            val customCandidate = TypeAccess.asCustom(outbound)
            if (customCandidate != null && customCandidate.isFullConfig()) {
                // Passed through as its own config with the inbounds emptied (:2606-2612).
                val obj = customCandidate.configObject()
                obj["inbounds"] = JsonArray()
                fullConfigs[id] = obj.toCompact()
                continue
            }
            val hopIds = ArrayList<Long>()
            if (candidate.landingProxyId > 0) hopIds.add(candidate.landingProxyId)
            hopIds.addAll(chains.unwrapChain(id))
            if (candidate.frontProxyId > 0) hopIds.add(candidate.frontProxyId)
            val invalid = chains.candidateError(hopIds)
            if (invalid.isNotEmpty()) {
                skipped[id] = invalid
                continue
            }
            var singToXrayPort = -1
            var xrayToSingPort = -1
            if (outbound.isXray()) singToXrayPort = xrayPorts[xrayPortIdx++]
            if (outbound.type == "chain") {
                singToXrayPort = xrayPorts[xrayPortIdx++]
                xrayToSingPort = xrayPorts[xrayPortIdx++]
            }
            val tag = chains.buildOutboundChain(
                ChainRequest(
                    hopIds, hopTag(Tags.TEST_CHAIN_PREFIX, suffix),
                    singToXrayPort = singToXrayPort, xrayToSingPort = xrayToSingPort,
                ),
            )
            if (state.failed) return GeneratedConfig.failure(state.error)
            outboundTags.add(tag)
            tagToProfileId[tag] = id
            suffix++
        }

        buildXrayConfig(state)
        if (state.failed) return GeneratedConfig.failure(state.error)
        state.outbounds.add(jsonObjectOf("type" to "direct", "tag" to Tags.DIRECT))
        state.coreConfig["outbounds"] = state.outbounds
        state.coreConfig["endpoints"] = state.endpoints
        val inbounds = JsonArray()
        val routeRules = JsonArray()
        for (i in state.xrayToSingBridges.indices) {
            val bridge = state.xrayToSingBridges[i]
            inbounds.add(socksBridgeInbound("${Tags.BRIDGE_PREFIX}-${bridge.port}", bridge))
            // The desktop never fills its rule list here (:2656-2668), which leaves a bridge on the box's first
            // outbound; routing it into its tailing hop like the main config does is what the bridge is for.
            if (i < state.singIngressTags.size) {
                routeRules.add(jsonObjectOf("inbound" to "${Tags.BRIDGE_PREFIX}-${bridge.port}", "action" to "route", "outbound" to state.singIngressTags[i]))
            }
        }
        val xrayDnsStrategy = if (state.isXrayNeeded || xrayFullConfigs.isNotEmpty()) ctx.xrayOutboundDomainStrategy() else ""
        // A running tun owns the OS resolver, so the sidecar resolves against the probe box's dns-direct instead.
        val route = jsonObjectOf(
            "auto_detect_interface" to true,
            "default_domain_resolver" to jsonObjectOf("server" to Tags.DNS_DIRECT, "strategy" to ctx.directDomainStrategy()),
        )
        if (routeRules.isNotEmpty()) route["rules"] = routeRules
        state.coreConfig["route"] = route
        state.coreConfig["inbounds"] = inbounds

        return GeneratedConfig(
            coreConfig = state.coreConfig.toCompact(),
            xrayConfig = if (state.isXrayNeeded) state.xrayConfig.toCompact() else null,
            needXray = state.isXrayNeeded,
            xrayDnsStrategy = xrayDnsStrategy,
            xrayFullConfigs = xrayFullConfigs,
            outboundTags = outboundTags,
            tagToProfileId = tagToProfileId,
            fullConfigs = fullConfigs,
            skipped = skipped,
            tunIPv4Cidr = null,
            error = null,
        )
    }

    /** classifyTestCandidate (:2236-2259): the skip reason, or null when the candidate is built. */
    private fun classifyTestCandidate(outbound: Outbound): String? {
        if (outbound.isExtraCore()) return "Skipping extra-core conf"
        if (outbound.isXrayFullConfig()) return null
        if (outbound.type == "chain") {
            for (hopId in TypeAccess.chainHops(outbound)) {
                val hop = profiles.get(hopId) ?: continue
                if (hop.isExtraCore() || hop.isXrayFullConfig()) {
                    return "Skipping chain with terminal (extra-core or Xray full config) hop (cannot test)"
                }
            }
            return null
        }
        if (outbound.type == "tailscale") return "Skipping Tailscale conf"
        if (outbound.type == "autoselector") return "Skipping auto selector conf (test its members instead)"
        return null
    }

    // ------------------------------------------------------------------------------------------------ sections

    /** buildLogSection (:757-759). */
    private fun buildLogSection(state: BuildState) {
        state.coreConfig["log"] = jsonObjectOf("level" to settings.logLevel)
    }

    /** buildNTPSection (:761-771). */
    private fun buildNtpSection(state: BuildState) {
        if (!settings.ntpEnabled) return
        state.coreConfig["ntp"] = jsonObjectOf(
            "enabled" to true,
            "server" to settings.ntpServer,
            "server_port" to settings.ntpServerPort,
            "interval" to settings.ntpInterval,
            "detour" to if (settings.ntpOutbound == Tags.PROXY && !state.forTest) Tags.PROXY else Tags.DIRECT,
        )
    }

    /** buildCertificateSection (:773-776). */
    private fun buildCertificateSection(state: BuildState) {
        state.coreConfig["certificate"] = jsonObjectOf("store" to if (settings.useMozillaCerts) "mozilla" else "system")
    }

    /** buildInboundSection (:1110-1200): dns-in, mixed-in, tun-in, then the custom inbounds; bridges come later. */
    private fun buildInboundSection(state: BuildState) {
        if (state.forTest) return
        val inbounds = JsonArray()
        inbounds.add(jsonObjectOf("tag" to Tags.DNS_IN, "type" to "direct", "listen" to "127.0.0.1", "listen_port" to settings.dnsInPort))
        if (settings.mixedInboundEnabled) {
            val mixed = jsonObjectOf(
                "tag" to Tags.MIXED_IN,
                "type" to "mixed",
                "listen" to settings.mixedListen,
                "listen_port" to settings.mixedPort,
            )
            if (settings.mixedAuth) {
                mixed["users"] = JsonArray.of(jsonObjectOf("username" to settings.mixedUsername, "password" to settings.mixedPassword))
            }
            inbounds.add(mixed)
        }
        if (settings.vpnMode) inbounds.add(buildTunInbound(state))
        for (item in JsonInput.parseObject(settings.customInboundJson).array("inbounds")) inbounds.add(item)
        state.coreConfig["inbounds"] = inbounds
    }

    /**
     * The tun inbound of :1133-1172 with the Android field set of design §2.4: no interface_name / auto_redirect
     * (the platform opens the device), per-app package lists instead of uid rules, the system HTTP proxy handed to
     * the VpnService builder through `platform.http_proxy`, and an explicit 1.14 `dns_mode`.
     */
    private fun buildTunInbound(state: BuildState): JsonObject {
        val tun = JsonObject()
        tun["tag"] = Tags.TUN_IN
        tun["type"] = "tun"
        tun["auto_route"] = true
        tun["mtu"] = settings.tunMtu
        tun["stack"] = settings.tunStack
        tun["strict_route"] = settings.tunStrictRoute
        state.tunIPv4Cidr = settings.tunIPv4Cidr
        val address = JsonArray.of(settings.tunIPv4Cidr)
        if (settings.ipv6Enabled) address.add(settings.tunIPv6Cidr)
        tun["address"] = address
        // sing-tun subtracts route_exclude_address from the routes it installs, so a rule aimed at an excluded range never fires (#1741).
        val routeExcludeAddrs = JsonArray()
        if (settings.bypassLan) {
            routeExcludeAddrs.add("127.0.0.0/8")
            routeExcludeAddrs.add("255.255.255.255/32")
            for (range in settings.privateRanges) routeExcludeAddrs.add(range)
        }
        tun["route_exclude_address"] = routeExcludeAddrs
        // hijack (the 1.14 default, spelled out): OpenTun receives the tun's derived DNS address for the VPN builder
        // and connections to it are hijacked into the DNS module; "disabled" would leave apps on the LAN resolver,
        // which the bypassed private ranges keep outside the tun (fork docs/configuration/inbound/tun.md, dns_mode).
        tun["dns_mode"] = "hijack"
        if (settings.perAppEnabled) {
            val packages = JsonValues.stringArray(settings.perAppPackages.map { it.trim() })
            if (packages.isNotEmpty()) tun[if (settings.perAppBypass) "exclude_package" else "include_package"] = packages
        }
        if (settings.mixedInboundEnabled && settings.httpProxyEnabled) {
            val httpProxy = jsonObjectOf("enabled" to true, "server" to "127.0.0.1", "server_port" to settings.mixedPort)
            val bypass = JsonValues.stringArray(settings.httpProxyBypassDomains.map { it.trim() })
            if (bypass.isNotEmpty()) httpProxy["bypass_domain"] = bypass
            tun["platform"] = jsonObjectOf("http_proxy" to httpProxy)
        }
        return tun
    }

    /** buildOutboundsSection (:1786-1903) for the main chain: no route-profile or auxiliary outbounds in phase 1. */
    private fun buildOutboundsSection(
        state: BuildState, chains: ChainBuilder, profile: Outbound, profileId: Long, landingProxyId: Long, frontProxyId: Long,
    ) {
        // Exit first: the landing proxy becomes "proxy", the stored chain list is reversed, the front proxy is dialed directly.
        val hopIds = ArrayList<Long>()
        if (landingProxyId > 0) hopIds.add(landingProxyId)
        if (profile.type == "chain") hopIds.addAll(TypeAccess.chainHops(profile).asReversed()) else hopIds.add(profileId)
        if (frontProxyId > 0) hopIds.add(frontProxyId)
        if (hopIds.isEmpty()) {
            state.error = "The chain has no hops"
            return
        }
        chains.buildOutboundChain(ChainRequest(hopIds, Tags.MAIN_CHAIN_PREFIX, includeProxy = true))
        if (state.failed) return

        val mismatch = state.bridgeIngressMismatch()
        if (mismatch.isNotEmpty()) {
            state.error = mismatch
            return
        }
        val inbounds = state.coreConfig.array("inbounds")
        for (i in state.xrayToSingBridges.indices) {
            inbounds.add(socksBridgeInbound(bridgeTagFor(state.singIngressTags[i]), state.xrayToSingBridges[i]))
        }
        state.coreConfig["inbounds"] = inbounds

        state.outbounds.add(jsonObjectOf("type" to "direct", "tag" to Tags.DIRECT))
        state.coreConfig["endpoints"] = state.endpoints
        state.coreConfig["outbounds"] = state.outbounds
    }

    /** buildDNSSection (:865-1106) with the Default route profile: no direct/proxy site rules, no tunnel DNS. */
    private fun buildDnsSection(state: BuildState, useDnsObj: Boolean) {
        if (buildContext.useDnsObject && useDnsObj) {
            state.coreConfig["dns"] = JsonInput.parseObject(settings.dnsObject)
            return
        }
        var independentCache = false
        val servers = JsonArray()
        val rules = JsonArray()
        // Merged in front of `rules` at the end.
        val headRules = JsonArray()

        if (!state.forTest) {
            var remoteDnsObj = DnsServers.buildDnsObj(settings.remoteDns)
            // Xray resolves through this server's transport and cannot carry udp or quic (:882-885).
            if (state.proxyUsesXray && (remoteDnsObj.string("type") == "udp" || remoteDnsObj.string("type") == "quic")) {
                remoteDnsObj = DnsServers.buildDnsObj(DnsServers.upgradeUdpDnsToDoH(remoteDnsObj.string("server")))
            }
            remoteDnsObj["tag"] = Tags.DNS_REMOTE
            remoteDnsObj["domain_resolver"] = Tags.DNS_LOCAL
            remoteDnsObj["detour"] = Tags.PROXY
            servers.add(remoteDnsObj)
        }

        val directDnsObj = DnsServers.buildDnsObj(settings.directDns)
        directDnsObj["tag"] = Tags.DNS_DIRECT
        directDnsObj["domain_resolver"] = Tags.DNS_LOCAL
        servers.add(directDnsObj)

        if (!state.forTest && settings.dnsPredefinedEnable) {
            val predefined = PredefinedDns.parse(settings.dnsPredefinedRules) ?: emptyList()
            for (entry in predefined) {
                emitPredefinedFamily(headRules, entry.domain, entry.v4, "A")
                emitPredefinedFamily(headRules, entry.domain, entry.v6, "AAAA")
            }
        }

        if (!state.forTest && settings.dnsUseHosts) {
            servers.add(jsonObjectOf("tag" to Tags.DNS_HOSTS, "type" to "hosts"))
            // The transport NXDOMAINs whatever it cannot answer, hence the preferred_by gate and query_type limit.
            headRules.add(
                jsonObjectOf(
                    "preferred_by" to JsonArray.of(Tags.DNS_HOSTS),
                    "query_type" to JsonArray.of("A", "AAAA"),
                    "action" to "route",
                    "server" to Tags.DNS_HOSTS,
                    "disable_cache" to true,
                ),
            )
        }

        if (settings.fakeDns) {
            val fakeServer = jsonObjectOf("tag" to Tags.DNS_FAKE, "type" to "fakeip", "inet4_range" to "198.18.0.0/15")
            // No inet6_range makes the transport answer AAAA empty itself; the rule stays on both types.
            if (!settings.fakeIpDisableIpv6) fakeServer["inet6_range"] = "fc00::/18"
            servers.add(fakeServer)
            rules.add(jsonObjectOf("query_type" to JsonArray.of("A", "AAAA"), "action" to "route", "server" to Tags.DNS_FAKE))
            independentCache = true
        }

        // A test box builds no dns-remote server at all, so its fall-through goes out direct.
        val useDirectFinalDns = state.forTest || settings.dnsFinalOut == Tags.DIRECT
        appendDnsRoute(
            rules, JsonObject(),
            if (useDirectFinalDns) Tags.DNS_DIRECT else Tags.DNS_REMOTE,
            if (useDirectFinalDns) buildContext.directDnsDisableIpv6 else settings.remoteDnsDisableIpv6,
        )

        val dnsLocalObj = DnsServers.buildDnsObj(settings.underlyingDns.ifEmpty { "local" })
        dnsLocalObj["tag"] = Tags.DNS_LOCAL
        servers.add(dnsLocalObj)

        val allRules = if (headRules.isEmpty()) rules else headRules.also { head -> for (rule in rules) head.add(rule) }
        val dns = jsonObjectOf("servers" to servers, "rules" to allRules, "cache_capacity" to settings.dnsCacheCapacity)
        if (settings.dnsDisableCache) dns["disable_cache"] = true
        if (settings.dnsDisableExpire) dns["disable_expire"] = true
        if (settings.dnsReverseMapping) dns["reverse_mapping"] = true
        if (independentCache) dns["independent_cache"] = true
        if (settings.dnsQueryTimeout.isNotEmpty()) dns["timeout"] = settings.dnsQueryTimeout
        // The core refuses the config outright when optimistic meets either cache switch.
        if (settings.dnsOptimistic && !settings.dnsDisableCache && !settings.dnsDisableExpire) {
            dns["optimistic"] = if (settings.dnsOptimisticTimeout.isEmpty()) true
            else jsonObjectOf("enabled" to true, "timeout" to settings.dnsOptimisticTimeout)
        }
        state.coreConfig["dns"] = dns
    }

    // "*." is rewritten to the queried name by the core; a family without an address is refused rather than passed
    // through, else the other family defeats the override (:936-957).
    private fun emitPredefinedFamily(rules: JsonArray, domain: String, addresses: List<String>, type: String) {
        if (addresses.isEmpty()) {
            rules.add(jsonObjectOf("domain" to domain, "action" to "predefined", "query_type" to type, "rcode" to "NXDOMAIN"))
            return
        }
        val answers = JsonArray()
        for (address in addresses) answers.add("*. IN $type $address")
        rules.add(jsonObjectOf("domain" to domain, "action" to "predefined", "query_type" to type, "rcode" to "NOERROR", "answer" to answers))
    }

    // appendDnsRoute (:370-383): the AAAA guard answers empty instead of falling through to another server.
    private fun appendDnsRoute(rules: JsonArray, conditions: JsonObject, server: String, disableIPv6: Boolean) {
        if (disableIPv6) {
            val guard = conditions.copy()
            guard["query_type"] = JsonArray.of("AAAA")
            guard["action"] = "predefined"
            rules.add(guard)
        }
        val route = conditions.copy()
        route["action"] = "route"
        route["server"] = server
        rules.add(route)
    }

    /** buildRouteSection (:1940-2129) for the Default route profile (RouteProfile::GetDefaultChain). */
    private fun buildRouteSection(state: BuildState) {
        val mismatch = state.bridgeIngressMismatch()
        if (mismatch.isNotEmpty()) {
            state.error = mismatch
            return
        }
        val rules = JsonArray()
        // Bridge rules come first: what Xray hands back must reach its tailing hop before any other rule sees it.
        for (i in state.xrayToSingBridges.indices) {
            rules.add(jsonObjectOf("inbound" to bridgeTagFor(state.singIngressTags[i]), "action" to "route", "outbound" to state.singIngressTags[i]))
        }
        rules.add(jsonObjectOf("action" to "sniff"))
        if (settings.resolveDomainStrategy.isNotEmpty()) {
            rules.add(jsonObjectOf("inbound" to JsonArray.of(Tags.MIXED_IN, Tags.TUN_IN), "action" to "resolve", "strategy" to settings.resolveDomainStrategy))
        }
        rules.add(jsonObjectOf("protocol" to "dns", "action" to "hijack-dns"))
        if (!state.forTest) rules.add(jsonObjectOf("inbound" to Tags.DNS_IN, "action" to "reject"))
        // The Default profile's own rule list: "Route DNS" (RouteProfile.cpp:661-670, RouteRule.cpp:81-185).
        rules.add(jsonObjectOf("protocol" to "dns", "action" to "hijack-dns"))

        val route = JsonObject()
        route["rules"] = rules
        route["rule_set"] = JsonArray()
        route["final"] = Tags.PROXY
        if (settings.trafficStats) route["find_process"] = true
        route["default_domain_resolver"] = jsonObjectOf("server" to Tags.DNS_DIRECT, "strategy" to buildContext.directDomainStrategy())
        if (settings.vpnMode) route["auto_detect_interface"] = true
        state.coreConfig["route"] = route
    }

    /** buildExperimentalSection (:2133-2155). */
    private fun buildExperimentalSection(state: BuildState) {
        if (state.forTest) return
        val experimental = JsonObject()
        if (settings.clashApiEnabled) {
            val clashApi = jsonObjectOf(
                "external_controller" to "${settings.clashApiListen}:${settings.clashApiPort}",
                "secret" to settings.clashApiSecret,
            )
            if (settings.clashApiExternalUi.isNotEmpty()) clashApi["external_ui"] = settings.clashApiExternalUi
            experimental["clash_api"] = clashApi
        }
        // enabled is unconditional: the same file backs the remote rule-set cache and the auto-selector's last pick.
        experimental["cache_file"] = jsonObjectOf(
            "enabled" to true,
            "store_fakeip" to settings.dnsPersistCache,
            "store_dns" to settings.dnsPersistCache,
        )
        state.coreConfig["experimental"] = experimental
    }

    /** buildServicesSection (:2160-2185): the core builds the traffic tracker from the mere presence of an api service. */
    private fun buildServicesSection(state: BuildState) {
        if (state.forTest || !settings.trafficStats) return
        state.coreConfig["services"] = JsonArray.of(
            jsonObjectOf("type" to "api", "listen" to "127.0.0.1", "listen_port" to 0, "secret" to ""),
        )
    }

    /** buildXrayConfig (:2189-2221): one socks inbound and routing rule per Xray ingress, no dns object. */
    private fun buildXrayConfig(state: BuildState) {
        if (state.xrayOutbounds.isEmpty()) return
        state.isXrayNeeded = true
        if (state.xrayIngressTags.size != state.singToXrayBridges.size) {
            state.error = "xray ingress tags size does not match bridge count!"
            return
        }
        val inbounds = JsonArray()
        val routeRules = JsonArray()
        for (i in state.xrayIngressTags.indices) {
            val outboundTag = state.xrayIngressTags[i]
            val inboundTag = "$outboundTag-inbound"
            inbounds.add(xraySocksInbound(inboundTag, state.singToXrayBridges[i]))
            routeRules.add(jsonObjectOf("type" to "field", "inboundTag" to JsonArray.of(inboundTag), "outboundTag" to outboundTag))
        }
        state.xrayConfig["log"] = jsonObjectOf(
            "loglevel" to settings.xrayLogLevel,
            "access" to if (settings.xrayLogLevel == "info") "" else "none",
        )
        state.xrayConfig["inbounds"] = inbounds
        state.xrayConfig["outbounds"] = state.xrayOutbounds
        state.xrayConfig["routing"] = jsonObjectOf("domainStrategy" to "AsIs", "rules" to routeRules)
    }

    /** The first IPv4 tun address of a custom full config (:2316-2331). */
    private fun tunIPv4CidrOf(core: JsonObject): String? {
        for (item in core.array("inbounds")) {
            val inbound = item as? JsonObject ?: continue
            if (inbound.string("type") != "tun") continue
            val addresses = if (inbound.isString("address")) listOf(inbound.string("address")) else inbound.array("address").strings()
            val cidr = addresses.firstOrNull { it.isNotEmpty() && !it.contains(':') }
            if (cidr != null) return cidr
        }
        return null
    }
}
