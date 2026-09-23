package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * Route profiles in the app's JSON backup: one object per profile with the desktop member names (remote metadata
 * included) and its rules in order, list members as string arrays.
 */
object RouteBackup {

    fun exportJson(): JSONArray = JSONArray().apply {
        for (p in RouteManager.all()) put(profileToJson(p))
    }

    /**
     * Replaces every route profile with the ones in [arr], keeping their ids so a restored current_route_id stays
     * valid. Rule outbounds that name server profiles go through [profileIdMap] (old id → id here); with an empty
     * map (profiles not restored) an id is kept when that profile exists here. Any other id becomes proxy with a
     * warning. Returns the warnings.
     */
    fun importJson(arr: JSONArray, profileIdMap: Map<Long, Long>): List<String> {
        val warnings = ArrayList<String>()
        val profiles = ArrayList<RouteProfile>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
            if (obj == null || obj.optJSONArray("rules") == null) {
                warnings.add("route profile #${i + 1} is not in the route profile format, skipped")
                continue
            }
            profiles.add(profileFromJson(obj, profileIdMap, warnings))
        }
        if (profiles.isEmpty()) return warnings
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.routeDao.reset()
            for (p in profiles) RouteManager.save(p)
        }
        return warnings
    }

    private fun profileToJson(p: RouteProfile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("default_outbound_id", p.default_outbound_id)
        put("is_remote", p.is_remote)
        put("remote_url", p.remote_url)
        put("auto_update", p.auto_update)
        put("remote_last_update", p.remote_last_update)
        put("rules", JSONArray().apply { for (r in p.rules) put(ruleToJson(r)) })
    }

    private fun ruleToJson(r: RouteRule) = JSONObject().apply {
        put("name", r.name)
        put("type", r.type)
        put("action", r.action)
        put("outbound_id", r.outbound_id)
        for (f in RouteRule.STRING_FIELDS) put(f.name, f.get(r))
        for (f in RouteRule.BOOL_FIELDS) put(f.name, f.get(r))
        for (f in RouteRule.LIST_FIELDS) put(f.name, JSONArray(f.get(r)))
    }

    private fun profileFromJson(obj: JSONObject, profileIdMap: Map<Long, Long>, warnings: MutableList<String>) = RouteProfile().apply {
        id = obj.optLong("id").coerceAtLeast(0L)
        name = obj.optString("name")
        default_outbound_id = obj.optLong("default_outbound_id", OutboundIds.PROXY)
        is_remote = obj.optBoolean("is_remote")
        remote_url = obj.optString("remote_url")
        auto_update = obj.optBoolean("auto_update")
        remote_last_update = obj.optLong("remote_last_update")
        val arr = obj.getJSONArray("rules")
        for (i in 0 until arr.length()) {
            val ruleObj = arr.optJSONObject(i) ?: continue
            val rule = ruleFromJson(ruleObj)
            remapOutbound(rule, name, profileIdMap, warnings, keepExisting = profileIdMap.isEmpty())
            rules.add(rule)
        }
    }

    private fun ruleFromJson(obj: JSONObject) = RouteRule().apply {
        name = obj.optString("name")
        type = obj.optInt("type")
        action = obj.optString("action", "route")
        outbound_id = obj.optLong("outbound_id", OutboundIds.DIRECT)
        for (f in RouteRule.STRING_FIELDS) f.set(this, obj.optString(f.name))
        for (f in RouteRule.BOOL_FIELDS) f.set(this, obj.optBoolean(f.name))
        for (f in RouteRule.LIST_FIELDS) {
            val values = obj.optJSONArray(f.name) ?: continue
            f.set(this, (0 until values.length()).map { values.optString(it) }.filterTo(ArrayList()) { it.isNotBlank() })
        }
    }

    /** A rule's server-profile outbound through [profileIdMap]; an unknown one on a rule that routes becomes proxy. */
    internal fun remapOutbound(
        rule: RouteRule,
        profileName: String,
        profileIdMap: Map<Long, Long>,
        warnings: MutableList<String>,
        keepExisting: Boolean,
    ) {
        val id = rule.outbound_id
        if (id <= 0L) return
        profileIdMap[id]?.let {
            rule.outbound_id = it
            return
        }
        if (keepExisting && ProfileManager.getProfile(id) != null) return
        val action = rule.effectiveAction()
        if (action == "route" || action == "bypass") {
            warnings.add("$profileName / ${rule.name}: outbound profile $id not found, using proxy")
            rule.outbound_id = OutboundIds.PROXY
        } else {
            rule.outbound_id = OutboundIds.DIRECT
        }
    }
}
