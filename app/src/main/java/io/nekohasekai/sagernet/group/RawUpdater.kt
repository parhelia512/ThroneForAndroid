package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.import.ProfileImport
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.Util

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        val link = subscription.link
        var proxies: List<Outbound>
        if (link.startsWith("content://")) {
            val contentText = app.contentResolver.openInputStream(link.toUri())
                ?.bufferedReader()
                ?.readText()

            proxies = contentText?.let { parseRaw(contentText) }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
        } else {

            // The subscription's own UA first, then the app's (user_agent2) and the common client UAs, until one
            // of them downloads and parses into a non-empty list.
            val candidateUserAgents = buildList {
                subscription.customUserAgent.takeIf { it.isNotBlank() }?.let { add(it) }
                add(appUserAgent())
                add("clash-meta")
                add("v2rayN/7.8.2")
                add("sing-box/1.14.0")
            }

            var lastError: Throwable? = null
            var lastUserinfo = ""
            var lastDisposition = ""
            var lastProfileTitle = ""
            proxies = emptyList()
            val viaProxy = appRequestsViaProxy()

            for (candidate in candidateUserAgents) {
                try {
                    val response = try {
                        fetchText(
                            subscription.link,
                            userAgent = candidate,
                            viaProxy = viaProxy,
                            allowInsecure = DataStore.netInsecure,
                            restrictTls13 = DataStore.appTLSVersion == "1.3",
                        )
                    } catch (proxyError: Throwable) {
                        if (!viaProxy || !DataStore.serviceState.connected) throw proxyError
                        // the proxied download failed while connected: retry the same UA directly
                        Logs.d(
                            "Subscription download via proxy failed with UA $candidate, " +
                                "fallback to direct: ${proxyError.message}"
                        )
                        fetchText(
                            subscription.link,
                            userAgent = candidate,
                            viaProxy = false,
                            allowInsecure = DataStore.netInsecure,
                            restrictTls13 = DataStore.appTLSVersion == "1.3",
                        )
                    }
                    val parsed = parseRaw(response.body)
                    if (parsed.isNullOrEmpty()) {
                        throw IllegalStateException("no proxies found with UA: $candidate")
                    }
                    proxies = parsed

                    response.header("Subscription-Userinfo")
                        .takeIf { it.isNotBlank() }?.let { lastUserinfo = it }
                    // the subscription title header (with the base64: prefix) names a still unnamed group
                    val profileTitle = response.header("profile-title")
                        .takeIf { it.isNotBlank() }
                        ?: response.header("x-profile-title")
                            .takeIf { it.isNotBlank() }
                    if (profileTitle != null) {
                        lastProfileTitle = if (profileTitle.startsWith("base64:")) {
                            NGUtil.decode(profileTitle.removePrefix("base64:"))
                        } else {
                            profileTitle
                        }
                    }
                    if (proxyGroup.name?.startsWith("Subscription #") == true) {
                        val remoteName = response.header("content-disposition")
                        if (remoteName.isNotBlank()) {
                            lastDisposition = remoteName
                        }
                    }
                    break
                } catch (e: SubscriptionFoundException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    Logs.d("Subscription download failed with UA $candidate: ${e.message}")
                }
            }

            if (proxies.isEmpty()) {
                throw (lastError ?: error(app.getString(R.string.no_proxies_found)))
            }

            subscription.subscriptionUserinfo = lastUserinfo

            if (proxyGroup.name?.startsWith("Subscription #") == true) {
                val remoteName = lastProfileTitle.ifBlank { Util.decodeFilename(lastDisposition) }
                if (remoteName.isNotBlank()) {
                    proxyGroup.name = remoteName
                }
            }
        }

        val proxiesMap = LinkedHashMap<String, Outbound>()
        for (proxy in proxies) {
            var index = 0
            var name = proxy.displayName()
            while (proxiesMap.containsKey(name)) {
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
                proxy.name = name
            }
            proxiesMap[proxy.displayName()] = proxy
        }
        proxies = proxiesMap.values.toList()

        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val filterMode = subscription.filterMode ?: SubscriptionFilterMode.DISABLED
        val filterRegex = subscription.filterRegex ?: ""
        if (filterMode != SubscriptionFilterMode.DISABLED && filterRegex.isNotBlank()) {
            val regex = filterRegex.toRegex()
            proxies = when (filterMode) {
                SubscriptionFilterMode.INCLUDE -> proxies.filter { regex.containsMatchIn(it.displayName()) }
                SubscriptionFilterMode.EXCLUDE -> proxies.filterNot { regex.containsMatchIn(it.displayName()) }
                else -> proxies
            }
            Logs.d("After filter (mode=$filterMode): ${proxies.size}")
        }

        val exists = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val unique = LinkedHashMap<String, Outbound>()
            for (proxy in proxies) {
                val key = proxy.dedupKey()
                val first = unique[key]
                if (first == null) {
                    unique[key] = proxy
                } else {
                    duplicate.add("${proxy.displayName()} = ${first.displayName()}")
                }
            }
            proxies = unique.values.toList()
        }

        Logs.d("New profiles: ${proxies.size}")

        val nameMap = proxies.associateBy { it.displayName() }

        Logs.d("Unique profiles: ${nameMap.size}")

        // Circuit breaker: with at least 10 existing profiles and a fetch below 70 % of them, nothing is
        // deleted, so a broken subscription response cannot wipe the group.
        val circuitBreak = exists.size >= 10 && proxies.size < exists.size * 0.7
        if (circuitBreak) {
            Logs.w(
                "Subscription diff circuit breaker tripped: " +
                    "exists=${exists.size}, fetched=${proxies.size}, skip deletion"
            )
        }

        // Existing entities are matched in order (first unmatched name wins) so userOrder follows the subscription.
        val existsList = exists.toMutableList()
        val toReplace = LinkedHashMap<String, ProxyEntity>()
        for (name in nameMap.keys) {
            val index = existsList.indexOfFirst { it.displayName() == name }
            if (index != -1) {
                toReplace[name] = existsList.removeAt(index)
            }
        }
        val toDelete = ArrayList<ProxyEntity>()
        if (circuitBreak) {
            Logs.d("Skipped deletion of ${existsList.size} unmatched profiles")
        } else {
            toDelete.addAll(existsList)
        }

        Logs.d("toDelete profiles: ${toDelete.size}")
        Logs.d("toReplace profiles: ${toReplace.size}")

        val toUpdate = ArrayList<ProxyEntity>()
        val toInsert = ArrayList<ProxyEntity>()
        val added = mutableListOf<String>()
        val updated = mutableMapOf<String, String>()
        val deleted = toDelete.map { it.displayName() }

        var userOrder = 1L
        var changed = toDelete.size
        for ((name, outbound) in nameMap.entries) {
            val entity = toReplace[name]
            if (entity != null) {
                val newJson = outbound.exportToJson().toCompact()
                when {
                    entity.type != outbound.type || entity.outboundJson != newJson -> {
                        changed++
                        entity.putOutbound(outbound)
                        entity.userOrder = userOrder
                        toUpdate.add(entity)
                        updated[entity.displayName()] = name

                        Logs.d("Updated profile: $name")
                    }

                    entity.userOrder != userOrder -> {
                        entity.userOrder = userOrder
                        toUpdate.add(entity)

                        Logs.d("Reordered profile: $name")
                    }

                    else -> {
                        Logs.d("Ignored profile: $name")
                    }
                }
            } else {
                changed++
                toInsert.add(
                    ProxyEntity(
                        groupId = proxyGroup.id, userOrder = userOrder
                    ).putOutbound(outbound)
                )
                added.add(name)
                Logs.d("Inserted profile: $name")
            }
            userOrder++
        }

        if (toInsert.isNotEmpty()) {
            SagerDatabase.proxyDao.insert(toInsert)
            Logs.d("Inserted profiles: ${toInsert.size}")
        }

        SagerDatabase.proxyDao.updateProxy(toUpdate).also {
            Logs.d("Updated profiles: $it")
        }

        SagerDatabase.proxyDao.deleteProxy(toDelete).also {
            Logs.d("Deleted profiles: $it")
        }

        val existCount = SagerDatabase.proxyDao.countByGroup(proxyGroup.id).toInt()

        if (existCount != proxies.size) {
            Logs.e("Exist profiles: $existCount, new profiles: ${proxies.size}")
        }

        subscription.lastUpdated = (System.currentTimeMillis() / 1000).toInt()
        SagerDatabase.groupDao.updateGroup(proxyGroup)
        finishUpdate(proxyGroup)

        userInterface?.onUpdateSuccess(
            proxyGroup, changed, added, updated, deleted, duplicate, byUser
        )
    }

    /**
     * The profiles of a subscription body, a pasted text, a scanned code or an opened file (ProfileImport with the
     * desktop's detection order). A lone subscription link throws [SubscriptionFoundException]; null when nothing
     * could be parsed. A WireGuard `.conf` carries its name in [fileName] only.
     */
    fun parseRaw(text: String, fileName: String = ""): List<Outbound>? {
        detectSubscriptionLink(text)?.let { throw SubscriptionFoundException(it) }
        val result = ProfileImport.parse(text, SettingsMapper.xrayVlessPreference())
        for (message in result.messages) Logs.d("ProfileImport: $message")
        val proxies = result.outbounds
        if (proxies.isEmpty()) return null
        if (fileName.isNotBlank()) {
            for (proxy in proxies) {
                if (proxy.type == "wireguard" && proxy.name.isEmpty()) proxy.name = fileName.removeSuffix(".conf")
            }
        }
        return proxies
    }

}
