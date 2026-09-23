package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteDatabase
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.math.abs

/**
 * 解析 Throne 电脑版 `.thrbackup`（QDataStream + 内嵌 SQLite），
 * 并映射为 T4A 的分组/节点、路由配置（DesktopRouteImport）与设置（SettingsRegistry）。
 *
 * 忽略自定义图标（icons/ 下文件）。
 */
object ThroneDesktopBackupImporter {

    private const val MAGIC = "THRN"
    private const val MAX_FORMAT_VERSION = 2

    data class ParsedBackup(
        val formatVersion: Int,
        val meta: JSONObject,
        val hasProfiles: Boolean,
        val hasRoutes: Boolean,
        val hasSettings: Boolean,
        internal val dbFile: File,
    )

    class InvalidBackupException(message: String) : Exception(message)

    fun parse(bytes: ByteArray, cacheDir: File): ParsedBackup {
        if (bytes.size < 8) throw InvalidBackupException("file too small")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magicBytes = ByteArray(4)
        buf.get(magicBytes)
        if (String(magicBytes, Charsets.US_ASCII) != MAGIC) {
            throw InvalidBackupException("not a Throne backup (bad magic)")
        }
        val formatVersion = buf.int
        if (formatVersion < 1 || formatVersion > MAX_FORMAT_VERSION) {
            throw InvalidBackupException("unsupported thrbackup format version: $formatVersion")
        }
        val metaStr = readQString(buf)
            ?: throw InvalidBackupException("missing metadata")
        val meta = try {
            JSONObject(metaStr)
        } catch (e: Exception) {
            throw InvalidBackupException("invalid metadata JSON: ${e.message}")
        }
        val fileCount = buf.int
        if (fileCount < 0 || fileCount > 10000) {
            throw InvalidBackupException("invalid files map count: $fileCount")
        }
        var databaseBytes: ByteArray? = null
        repeat(fileCount) {
            val key = readQString(buf) ?: return@repeat
            val value = readQByteArray(buf) ?: return@repeat
            if (key == "database") databaseBytes = value
            // icons/ entries intentionally ignored
        }
        if (databaseBytes == null) {
            throw InvalidBackupException("backup has no database payload")
        }
        if (databaseBytes!!.size < 16 ||
            !databaseBytes!!.copyOfRange(0, 16).contentEquals(
                "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            )
        ) {
            throw InvalidBackupException("database payload is not SQLite")
        }

        val parts = meta.optJSONObject("parts")
        val hasProfiles: Boolean
        val hasRoutes: Boolean
        val hasSettings: Boolean
        if (formatVersion >= 2 && parts != null) {
            hasProfiles = parts.optBoolean("profiles", false)
            hasRoutes = parts.optBoolean("routes", false)
            hasSettings = parts.optBoolean("settings", false)
        } else {
            // v1: treat all as present; refine by table counts below
            hasProfiles = true
            hasRoutes = true
            hasSettings = true
        }

        cacheDir.mkdirs()
        val dbFile = File(cacheDir, "throne_import_${System.currentTimeMillis()}.db")
        dbFile.writeBytes(databaseBytes!!)

        // Refine availability by actual row counts (selective backup may leave empty tables)
        val refined = refineAvailability(dbFile, hasProfiles, hasRoutes, hasSettings)
        return ParsedBackup(
            formatVersion = formatVersion,
            meta = meta,
            hasProfiles = refined.first,
            hasRoutes = refined.second,
            hasSettings = refined.third,
            dbFile = dbFile,
        )
    }

    fun import(
        parsed: ParsedBackup,
        importProfiles: Boolean,
        importRules: Boolean,
        importSettings: Boolean,
    ) {
        val db = SQLiteDatabase.openDatabase(
            parsed.dbFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        try {
            val settingsMap = readSettings(db)
            val profiles = if (importProfiles && parsed.hasProfiles) readGroupsAndProfiles(db, settingsMap) else null
            var currentRouteId: Long? = null
            // One transaction (the route import nests in it): a failing write leaves the database untouched.
            SagerDatabase.instance.runInTransaction {
                profiles?.let { applyGroupsAndProfiles(it) }
                if (importRules && parsed.hasRoutes) {
                    // Rules naming server profiles only keep them when those profiles came along.
                    currentRouteId = DesktopRouteImport.fromDesktopDb(
                        db, profiles?.profileIdMap ?: emptyMap(), settingsMap["current_route_id"]?.toLongOrNull(),
                    )
                }
            }
            profiles?.let { applySelection(it) }
            if (importSettings && parsed.hasSettings) {
                applySettings(settingsMap)
            }
            currentRouteId?.let { DataStore.currentRouteId = it }
        } finally {
            db.close()
            parsed.dbFile.delete()
        }
    }

    // region QDataStream

    private fun readQString(buf: ByteBuffer): String? {
        if (buf.remaining() < 4) throw InvalidBackupException("truncated QString length")
        val n = buf.int
        if (n == -1) return null // 0xFFFFFFFF
        if (n < 0 || n > buf.remaining()) throw InvalidBackupException("bad QString length $n")
        if (n == 0) return ""
        val bytes = ByteArray(n)
        buf.get(bytes)
        return try {
            StandardCharsets.UTF_16LE.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.UTF_16LE)
        }
    }

    private fun readQByteArray(buf: ByteBuffer): ByteArray? {
        if (buf.remaining() < 4) throw InvalidBackupException("truncated QByteArray length")
        val n = buf.int
        if (n == -1) return null
        if (n < 0 || n > buf.remaining()) throw InvalidBackupException("bad QByteArray length $n")
        val bytes = ByteArray(n)
        buf.get(bytes)
        return bytes
    }

    // endregion

    private fun refineAvailability(
        dbFile: File,
        hasProfiles: Boolean,
        hasRoutes: Boolean,
        hasSettings: Boolean,
    ): Triple<Boolean, Boolean, Boolean> {
        val db = SQLiteDatabase.openDatabase(
            dbFile.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        )
        try {
            fun count(table: String): Int = try {
                db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            } catch (_: Exception) {
                0
            }
            val p = hasProfiles && (count("profiles") > 0 || count("groups") > 0)
            val r = hasRoutes && (count("route_rules") > 0 || count("route_profiles") > 0)
            val s = hasSettings && count("settings") > 0
            return Triple(p, r, s)
        } finally {
            db.close()
        }
    }

    private fun readSettings(db: SQLiteDatabase): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        try {
            db.rawQuery("SELECT key, value FROM settings", null).use { c ->
                val ki = c.getColumnIndex("key")
                val vi = c.getColumnIndex("value")
                while (c.moveToNext()) {
                    val k = c.getString(ki) ?: continue
                    map[k] = c.getString(vi) ?: ""
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
        return map
    }

    // region profiles

    private class DeskGroup(
        val id: Long,
        val name: String,
        val url: String,
        val info: String,
        val subLastUpdate: Long,
        val skipAutoUpdate: Boolean,
        val frontProxyId: Long,
        val landingProxyId: Long,
        val profilesJson: String,
        val order: Long,
    )

    private class DeskProfile(
        val id: Long,
        val type: String,
        val name: String,
        val gid: Long,
        val outboundJson: String,
        val trafficUp: Long,
        val trafficDl: Long,
        val latency: Int,
    )

    /** The desktop rows mapped to the app's entities, ready to be written in one transaction. */
    private class ProfileImportData(
        val groups: List<ProxyGroup>,
        val proxies: List<ProxyEntity>,
        val selectedGroup: Long?,
        val selectedProfile: Long?,
        /** Desktop profile id → id here. */
        val profileIdMap: Map<Long, Long>,
    )

    private fun readGroupsAndProfiles(db: SQLiteDatabase, settings: Map<String, String>): ProfileImportData {
        val orderMap = HashMap<Long, Long>()
        try {
            db.rawQuery("SELECT group_id, display_order FROM groups_order", null).use { c ->
                val gi = c.getColumnIndex("group_id")
                val oi = c.getColumnIndex("display_order")
                while (c.moveToNext()) {
                    orderMap[c.getLong(gi)] = c.getLong(oi)
                }
            }
        } catch (_: Exception) {
        }

        val deskGroups = ArrayList<DeskGroup>()
        db.rawQuery("SELECT * FROM groups", null).use { c ->
            fun idx(n: String) = c.getColumnIndex(n)
            val iId = idx("id")
            val iName = idx("name")
            val iUrl = idx("url")
            val iInfo = idx("info")
            val iSub = idx("sub_last_update")
            val iSkip = idx("skip_auto_update")
            val iFront = idx("front_proxy_id")
            val iLand = idx("landing_proxy_id")
            val iProfiles = idx("profiles_json")
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                deskGroups.add(
                    DeskGroup(
                        id = id,
                        name = c.getString(iName) ?: "",
                        url = if (iUrl >= 0) c.getString(iUrl) ?: "" else "",
                        info = if (iInfo >= 0) c.getString(iInfo) ?: "" else "",
                        subLastUpdate = if (iSub >= 0) c.getLong(iSub) else 0L,
                        skipAutoUpdate = iSkip >= 0 && c.getInt(iSkip) != 0,
                        frontProxyId = if (iFront >= 0) c.getLong(iFront) else -1L,
                        landingProxyId = if (iLand >= 0) c.getLong(iLand) else -1L,
                        profilesJson = if (iProfiles >= 0) c.getString(iProfiles) ?: "[]" else "[]",
                        order = orderMap[id] ?: id,
                    )
                )
            }
        }
        deskGroups.sortBy { it.order }

        val deskProfiles = ArrayList<DeskProfile>()
        db.rawQuery("SELECT * FROM profiles", null).use { c ->
            fun idx(n: String) = c.getColumnIndex(n)
            val iId = idx("id")
            val iType = idx("type")
            val iName = idx("name")
            val iGid = idx("gid")
            val iOut = idx("outbound_json")
            val iUp = idx("traffic_up")
            val iDl = idx("traffic_dl")
            val iLat = idx("latency")
            while (c.moveToNext()) {
                deskProfiles.add(
                    DeskProfile(
                        id = c.getLong(iId),
                        type = c.getString(iType) ?: "",
                        name = c.getString(iName) ?: "",
                        gid = c.getLong(iGid),
                        outboundJson = if (iOut >= 0) c.getString(iOut) ?: "" else "",
                        trafficUp = if (iUp >= 0) c.getLong(iUp) else 0L,
                        trafficDl = if (iDl >= 0) c.getLong(iDl) else 0L,
                        latency = if (iLat >= 0) c.getInt(iLat) else 0,
                    )
                )
            }
        }

        // sub_auto_update is a sign-encoded interval in minutes: negative means auto update off (SettingsRepo.h:152).
        val subAutoUpdate = settings["sub_auto_update"]?.toIntOrNull()
        val autoUpdateEnabled = subAutoUpdate != null && subAutoUpdate > 0
        val subAutoUpdateDelay = subAutoUpdate?.let { abs(it) }?.takeIf { it > 0 } ?: 1440

        // Build per-group profile order from profiles_json
        val orderInGroup = HashMap<Long, Long>() // desktop profile id -> userOrder
        for (g in deskGroups) {
            val arr = try {
                JSONArray(g.profilesJson)
            } catch (_: Exception) {
                JSONArray()
            }
            var ord = 1L
            for (i in 0 until arr.length()) {
                val pid = arr.optLong(i, -1L)
                if (pid > 0) {
                    orderInGroup[pid] = ord++
                }
            }
        }

        // Group ids: the desktop's Default group (id 1) becomes the app's ungrouped bucket unless it is a
        // subscription, in which case every desktop group shifts by one so that id 1 stays free for the bucket.
        val defaultGroup = deskGroups.find { it.id == 1L }
        val shift = if (defaultGroup != null && defaultGroup.url.isNotBlank()) 1L else 0L
        val desktopIsUngrouped = defaultGroup != null && shift == 0L
        val groupIdMap = HashMap<Long, Long>()
        for (g in deskGroups) groupIdMap[g.id] = g.id + shift
        // Profile ids are kept; every reference (chain lists, front / landing proxies, the remembered id) is
        // still routed through this map so a different policy needs one change.
        val profileIdMap = HashMap<Long, Long>()
        for (p in deskProfiles) profileIdMap[p.id] = p.id
        fun mapGroup(id: Long): Long = groupIdMap[id] ?: 1L
        fun mapProfile(id: Long): Long = profileIdMap[id] ?: id

        val groups = ArrayList<ProxyGroup>()
        if (!desktopIsUngrouped) {
            groups.add(
                ProxyGroup(
                    id = 1L,
                    userOrder = 0L,
                    ungrouped = true,
                    name = "Ungrouped",
                    type = GroupType.BASIC,
                )
            )
        }

        for (g in deskGroups) {
            val isSub = g.url.isNotBlank()
            val pg = ProxyGroup(
                id = mapGroup(g.id),
                userOrder = g.order,
                ungrouped = desktopIsUngrouped && g.id == 1L,
                name = g.name.ifBlank { "Group ${g.id}" },
                type = if (isSub) GroupType.SUBSCRIPTION else GroupType.BASIC,
                frontProxy = g.frontProxyId.takeIf { it > 0 }?.let(::mapProfile) ?: -1L,
                landingProxy = g.landingProxyId.takeIf { it > 0 }?.let(::mapProfile) ?: -1L,
            )
            if (isSub) {
                val sub = SubscriptionBean().applyDefaultValues()
                sub.link = g.url
                sub.subscriptionUserinfo = g.info
                sub.autoUpdate = autoUpdateEnabled && !g.skipAutoUpdate
                sub.autoUpdateDelay = subAutoUpdateDelay
                // desktop stores unix seconds; T4A lastUpdated is Int seconds
                sub.lastUpdated = g.subLastUpdate
                    .coerceIn(0L, Int.MAX_VALUE.toLong())
                    .toInt()
                pg.subscription = sub
            }
            groups.add(pg)
        }

        val proxies = ArrayList<ProxyEntity>()
        var fallbackOrder = 10_000L
        for (p in deskProfiles) {
            proxies.add(
                ProxyEntity(
                    id = mapProfile(p.id),
                    groupId = if (p.gid > 0) mapGroup(p.gid) else 1L,
                    type = OutboundFactory.canonicalType(p.type),
                    outboundJson = convertOutboundJson(p.type, p.name, p.outboundJson, ::mapProfile),
                    userOrder = orderInGroup[p.id] ?: fallbackOrder++,
                    tx = p.trafficUp,
                    rx = p.trafficDl,
                    ping = p.latency,
                )
            )
        }

        val selectedGroup = settings["current_group"]?.toLongOrNull()?.takeIf { it > 0 }?.let { groupIdMap[it] }
        val selectedProfile = settings["remember_id"]?.toLongOrNull()?.takeIf { it > 0 }?.let { profileIdMap[it] }
        return ProfileImportData(groups, proxies, selectedGroup, selectedProfile, profileIdMap)
    }

    private fun applyGroupsAndProfiles(data: ProfileImportData) {
        SagerDatabase.proxyDao.reset()
        SagerDatabase.groupDao.reset()
        SagerDatabase.groupDao.insert(data.groups)
        if (data.proxies.isNotEmpty()) {
            SagerDatabase.proxyDao.insert(data.proxies)
        }
    }

    private fun applySelection(data: ProfileImportData) {
        data.selectedGroup?.let { DataStore.selectedGroup = it }
        data.selectedProfile?.let {
            DataStore.selectedProxy = it
            DataStore.currentProfile = it
        }
    }

    /**
     * The desktop's outbound_json is already the contract (compact ExportToJson); it is re-serialised only to
     * carry the row's name when the JSON lacks one and to remap the ids of a chain. Text that is not a JSON
     * object is stored as is (the entity then reports an invalid profile).
     */
    private fun convertOutboundJson(
        type: String,
        name: String,
        outboundJson: String,
        mapProfile: (Long) -> Long,
    ): String {
        val obj = if (outboundJson.isBlank()) JsonObject() else JsonInput.parseObjectOrNull(outboundJson) ?: return outboundJson
        val nameKey = if (type == "chain" || type == "custom") "name" else "tag"
        if (name.isNotBlank() && !obj.contains(nameKey)) obj[nameKey] = name
        if (type == "chain" && obj.isArray("list")) {
            val list = JsonArray()
            for (v in obj.array("list")) list.add(mapProfile(JsonValues.toInteger(v)))
            obj["list"] = list
        }
        return obj.toCompact()
    }

    // endregion

    // region settings

    /**
     * The registry-driven upsert of the desktop's `settings` rows (R7 §4.0 S3/S5): every adopted key whose value
     * its entry accepts is written verbatim, anything else (desktop-only keys, invalid values) is skipped. The
     * guards: a non-loopback inbound_address is only taken while LAN access is already on here, vpn_strict_route is
     * not adopted (fixed on Android) and current_route_id comes from the route import.
     */
    private fun applySettings(s: Map<String, String>) {
        val lanAllowed = DataStore.allowLanAccess
        val updates = LinkedHashMap<String, String>()
        for ((key, raw) in s) {
            val setting = SettingsRegistry.find(key) ?: continue
            if (setting === SettingsRegistry.CURRENT_ROUTE_ID) continue
            if (!setting.accepts(raw)) {
                Logs.w("desktop settings import: $key=\"$raw\" rejected")
                continue
            }
            updates[key] = if (setting === SettingsRegistry.INBOUND_ADDRESS && !lanAllowed &&
                !SettingsRegistry.isLoopbackAddress(raw)
            ) SettingsRegistry.LOOPBACK_ADDRESS else raw
        }
        DataStore.configurationStore.putAll(updates)
    }

    // endregion
}
