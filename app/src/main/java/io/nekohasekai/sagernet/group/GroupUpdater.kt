package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.types.Naive
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(
        profiles: List<Outbound>, groupId: Long?
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val lookupPool = newFixedThreadPoolContext(5, "DNS Lookup")
        val lookupJobs = mutableListOf<Job>()
        val progress = Progress(profiles.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        val ipv6First = ipv6Mode >= IPv6Mode.PREFER

        for (profile in profiles) {
            // SNI rewrite unsupported
            if (profile is Naive) continue

            val server = profile.server
            if (server.isEmpty() || server.isIpAddress()) continue

            lookupJobs.add(GlobalScope.launch(lookupPool) {
                try {
                    val results = if (
                        SagerNet.underlyingNetwork != null &&
                        DataStore.enableFakeDns &&
                        DataStore.serviceState.started &&
                        DataStore.serviceMode == Key.MODE_VPN
                    ) {
                        // FakeDNS
                        SagerNet.underlyingNetwork!!
                            .getAllByName(server)
                            .filterNotNull()
                    } else {
                        // System DNS is enough (when VPN connected, it uses v2ray-core)
                        InetAddress.getAllByName(server).filterNotNull()
                    }
                    if (results.isEmpty()) error("empty response")
                    rewriteAddress(profile, results, ipv6First)
                } catch (e: Exception) {
                    Logs.d("Lookup $server failed: ${e.readableMessage}", e)
                }
                if (groupId != null) {
                    progress.progress++
                    GroupManager.postReload(groupId)
                }
            })
        }

        lookupJobs.joinAll()
        lookupPool.close()
    }

    /** Swaps the host for a literal address; the certificate name must survive, so an empty SNI takes the host first. */
    protected fun rewriteAddress(
        outbound: Outbound, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }[0].hostAddress ?: return
        val host = outbound.server

        if (outbound.isXray()) {
            val stream = outbound.getXrayStream()
            when (stream.security) {
                "tls" -> if (stream.tls.serverName.isEmpty()) stream.tls.serverName = host
                "reality" -> if (stream.reality.serverName.isEmpty()) stream.reality.serverName = host
            }
        } else if (outbound.hasTls()) {
            val tls = outbound.getTls()
            if ((tls.enabled || outbound.mustTls()) && tls.server_name.isEmpty()) tls.server_name = host
        }

        outbound.setAddress(address)
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            // supervisorScope: one failing subscription must not cancel the rest of the batch
            return supervisorScope {
                // a second trigger for the same subscription returns false instead of throwing
                if (!updating.add(proxyGroup.id)) return@supervisorScope false
                GroupManager.postReload(proxyGroup.id)

                val subscription = proxyGroup.subscription ?: run {
                    Logs.w("Group ${proxyGroup.id} has no subscription, skip update")
                    finishUpdate(proxyGroup)
                    return@supervisorScope false
                }
                val connected = DataStore.serviceState.connected
                val userInterface = GroupManager.userInterface

                if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                    if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                        finishUpdate(proxyGroup)
                        return@supervisorScope true
                    }
                }

                try {
                    RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: Throwable) {
                    Logs.w(e)
                    // a failed background update stays silent (log only); only a user-triggered one reports
                    if (byUser) userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                    finishUpdate(proxyGroup)
                    false
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

    }

}
