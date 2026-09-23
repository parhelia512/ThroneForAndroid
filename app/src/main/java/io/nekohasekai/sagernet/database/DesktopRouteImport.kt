package io.nekohasekai.sagernet.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RuleType

/** The desktop's `route_profiles` / `route_rules` tables (RoutesRepo.cpp:14-102) from a `.thrbackup` database. */
object DesktopRouteImport {

    private val DEFAULT_OUTBOUNDS = setOf(OutboundIds.PROXY, OutboundIds.DIRECT, OutboundIds.BLOCK, OutboundIds.WARP_BYPASS)

    /**
     * Replaces every route profile with the desktop's, keeping the desktop ids. Raw profiles are skipped, endpoint
     * lists and endpoint rules dropped, and rule outbounds naming server profiles go through [profileIdMap]
     * (desktop id → id here); an id it lacks becomes proxy. Columns missing from older desktop versions read as
     * their defaults. Nothing is written when no profile can be imported. Returns the profile to make current (the
     * desktop's current one when imported, else the first), or null when nothing was imported; the warnings are
     * logged and added to [warnings].
     */
    fun fromDesktopDb(
        db: SQLiteDatabase,
        profileIdMap: Map<Long, Long>,
        desktopCurrentRouteId: Long?,
        warnings: MutableList<String>? = null,
    ): Long? {
        val notes = ArrayList<String>()
        val profiles = try {
            readProfiles(db, profileIdMap, notes)
        } catch (e: Exception) {
            notes.add("routing profiles could not be read: ${e.readableMessage}")
            emptyList()
        }
        for (note in notes) Logs.w("desktop route import: $note")
        warnings?.addAll(notes)
        if (profiles.isEmpty()) return null
        val desktopIds = profiles.map { it.id }
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.routeDao.reset()
            for (p in profiles) RouteManager.save(p)
        }
        val index = desktopIds.indexOf(desktopCurrentRouteId ?: 0L)
        return profiles[if (index >= 0) index else 0].id
    }

    private fun readProfiles(db: SQLiteDatabase, profileIdMap: Map<Long, Long>, notes: MutableList<String>): List<RouteProfile> {
        val profiles = ArrayList<RouteProfile>()
        db.rawQuery("SELECT * FROM route_profiles ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val name = c.text("name")
                if (c.long("is_raw", 0L) != 0L) {
                    notes.add("raw routing profile \"$name\" skipped: raw routing profiles are not supported on Android")
                    continue
                }
                if ((JsonInput.parseValue(c.text("endpoint_profile_ids")) as? JsonArray)?.isNotEmpty() == true) {
                    notes.add("\"$name\": endpoints dropped, they are not supported on Android")
                }
                profiles.add(RouteProfile().apply {
                    id = c.long("id", 0L)
                    this.name = name
                    default_outbound_id = c.long("default_outbound_id", OutboundIds.PROXY).takeIf { it in DEFAULT_OUTBOUNDS }
                        ?: OutboundIds.PROXY
                    is_remote = c.long("is_remote", 0L) != 0L
                    remote_url = c.text("remote_url")
                    auto_update = c.long("auto_update", 0L) != 0L
                    remote_last_update = c.long("remote_last_update", 0L)
                })
            }
        }
        for (p in profiles) p.rules = readRules(db, p, profileIdMap, notes)
        return profiles
    }

    private fun readRules(db: SQLiteDatabase, p: RouteProfile, profileIdMap: Map<Long, Long>, notes: MutableList<String>): MutableList<RouteRule> {
        val rules = ArrayList<RouteRule>()
        db.rawQuery(
            "SELECT * FROM route_rules WHERE route_profile_id = ? ORDER BY rule_order", arrayOf(p.id.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val rule = RouteRule()
                rule.name = c.text("name")
                rule.type = c.long("type", 0L).toInt()
                if (rule.type == RuleType.ENDPOINT_PREFERRED_BY.id) {
                    notes.add("\"${p.name}\": endpoint rule \"${rule.name}\" dropped, endpoints are not supported on Android")
                    continue
                }
                rule.action = c.textOrNull("action") ?: "route"
                rule.outbound_id = c.long("outbound_id", OutboundIds.DIRECT)
                for (f in RouteRule.STRING_FIELDS) f.set(rule, c.text(f.name))
                for (f in RouteRule.BOOL_FIELDS) f.set(rule, c.long(f.name, 0L) != 0L)
                for (f in RouteRule.LIST_FIELDS) f.set(rule, RouteRuleEntity.listFromJson(c.textOrNull(f.name + "_json")))
                RouteBackup.remapOutbound(rule, p.name, profileIdMap, notes, keepExisting = false)
                rules.add(rule)
            }
        }
        return rules
    }

    private fun Cursor.textOrNull(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }

    private fun Cursor.text(column: String): String = textOrNull(column) ?: ""

    private fun Cursor.long(column: String, default: Long): Long {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) default else getLong(i)
    }
}
