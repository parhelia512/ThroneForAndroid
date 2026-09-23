package io.nekohasekai.sagernet.route

/**
 * A structured RouteProfile (include/database/entities/RouteProfile.h) without the desktop's raw and endpoint
 * parts. [id] 0 means not saved yet.
 */
@Suppress("PropertyName")
class RouteProfile {
    @JvmField var id: Long = 0L
    @JvmField var name: String = ""
    @JvmField var default_outbound_id: Long = OutboundIds.PROXY
    @JvmField var is_remote: Boolean = false
    @JvmField var remote_url: String = ""
    @JvmField var auto_update: Boolean = false
    @JvmField var remote_last_update: Long = 0L
    @JvmField var rules: MutableList<RouteRule> = mutableListOf()

    fun copy(): RouteProfile {
        val c = RouteProfile()
        c.id = id
        c.name = name
        c.default_outbound_id = default_outbound_id
        c.is_remote = is_remote
        c.remote_url = remote_url
        c.auto_update = auto_update
        c.remote_last_update = remote_last_update
        c.rules = rules.mapTo(ArrayList()) { it.copy() }
        return c
    }

    fun isEmpty(): Boolean = rules.all { it.isEmpty() }

    /**
     * get_used_outbounds (RouteProfile.cpp:672-685), deduplicated (D12) and limited to the rules whose JSON
     * carries an outbound, so a leftover id on a reject or sniff rule does not require that server.
     */
    fun usedOutboundIds(): List<Long> {
        val out = LinkedHashSet<Long>()
        for (rule in rules) {
            if (rule.type == RuleType.ENDPOINT_PREFERRED_BY.id) continue
            val action = rule.effectiveAction()
            if (action == "route" || action == "bypass") out.add(rule.outbound_id)
        }
        return ArrayList(out)
    }

    /** get_used_rule_sets (RouteProfile.cpp:687-695), trimmed and deduplicated in first-seen order (D12). */
    fun usedRuleSets(): List<String> {
        val out = LinkedHashSet<String>()
        for (rule in rules) {
            for (entry in rule.rule_set) {
                val e = entry.trim()
                if (e.isNotEmpty()) out.add(e)
            }
        }
        return ArrayList(out)
    }

    fun directSites(): List<String> = sites(OutboundIds.DIRECT)

    fun proxySites(): List<String> = sites(OutboundIds.PROXY)

    /** get_direct_ips (RouteProfile.cpp:745-759). */
    fun directIps(): List<String> {
        val out = ArrayList<String>()
        for (rule in rules) {
            if (rule.outbound_id != OutboundIds.DIRECT || rule.action != "route") continue
            for (entry in rule.rule_set) {
                val e = entry.trim()
                if (e.startsWith("geoip-")) out.add("ruleset:$e")
            }
            addPrefixed(out, "ip:", rule.ip_cidr)
        }
        return out
    }

    /** get_hijacked_ips (RouteProfile.cpp:761-772): the CIDRs non-direct route and reject rules take away from direct. */
    fun hijackedIps(privateRanges: List<String>): List<String> {
        val out = ArrayList<String>()
        for (rule in rules) {
            if (rule.action == "route" && rule.outbound_id == OutboundIds.DIRECT) continue
            if (rule.action != "route" && rule.action != "reject") continue
            if (rule.ip_is_private) addPrefixed(out, "", privateRanges)
            addPrefixed(out, "", rule.ip_cidr)
        }
        return out
    }

    /** get_direct_sites / get_proxy_sites (RouteProfile.cpp:697-743). */
    private fun sites(outbound: Long): List<String> {
        val out = ArrayList<String>()
        for (rule in rules) {
            if (rule.outbound_id != outbound || rule.action != "route") continue
            for (entry in rule.rule_set) {
                val e = entry.trim()
                if (e.startsWith("geosite-")) out.add("ruleset:$e")
            }
            addPrefixed(out, "domain:", rule.domain)
            addPrefixed(out, "suffix:", rule.domain_suffix)
            addPrefixed(out, "keyword:", rule.domain_keyword)
            addPrefixed(out, "regex:", rule.domain_regex)
        }
        return out
    }

    private fun addPrefixed(out: MutableList<String>, prefix: String, values: List<String>) {
        for (value in values) {
            val v = value.trim()
            if (v.isNotEmpty()) out.add(prefix + v)
        }
    }

    companion object {
        /** GetDefaultChain (RouteProfile.cpp:661-670). */
        fun defaultProfile(): RouteProfile = RouteProfile().apply {
            name = "Default"
            rules.add(RouteRule().apply {
                name = "Route DNS"
                action = "hijack-dns"
                protocol = "dns"
            })
        }
    }
}
