package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.SubscriptionRuntime
import io.nekohasekai.sagernet.bg.subscriptionDelaySeconds
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
internal abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ): SubscriptionUpdateResult

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long?
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
            when (profile) {
                // SNI rewrite unsupported
                is NaiveBean -> continue
            }

            if (profile.serverAddress.isIpAddress()) continue

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
                            .getAllByName(profile.serverAddress)
                            .filterNotNull()
                    } else {
                        // System DNS is enough (when VPN connected, it uses v2ray-core)
                        InetAddress.getAllByName(profile.serverAddress).filterNotNull()
                    }
                    if (results.isEmpty()) error("empty response")
                    rewriteAddress(profile, results, ipv6First)
                } catch (e: Exception) {
                    Logs.d("Lookup ${profile.serverAddress} failed: ${e.readableMessage}", e)
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

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { (it is Inet4Address) xor ipv6First }[0].hostAddress

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isBlank()) sni = bean.serverAddress
                }
                is StandardV2RayBean -> {
                    when (security) {
                        "tls" -> if (sni.isBlank()) sni = bean.serverAddress
                    }
                }
                is TrojanBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is TrojanGoBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is HysteriaBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup.id, byUser)
            }
        }

        suspend fun executeUpdate(
            groupId: Long, byUser: Boolean, deferredOnly: Boolean = false,
        ): SubscriptionUpdateResult {
            if (!updating.add(groupId)) return SubscriptionUpdateResult.RETRY
            val userInterface = if (byUser) GroupManager.userInterface else null
            var group: ProxyGroup? = null
            try {
                val currentGroup = SagerDatabase.groupDao.getById(groupId)
                    ?: return SubscriptionUpdateResult.SKIPPED
                val subscription = currentGroup.subscription ?: return SubscriptionUpdateResult.SKIPPED
                if (!byUser && !subscription.autoUpdate) return SubscriptionUpdateResult.SKIPPED
                if (!byUser && deferredOnly &&
                    (subscription.updateMode != SubscriptionUpdateMode.DEFER_ACTIVE ||
                        subscription.deferredProfileIds.isEmpty())
                ) return SubscriptionUpdateResult.SKIPPED
                if (!byUser && !deferredOnly && subscriptionDelaySeconds(
                        System.currentTimeMillis() / 1000,
                        maxOf(subscription.lastUpdated, subscription.lastChecked).toLong(),
                        subscription.autoUpdateDelay,
                    ) > 0
                ) return SubscriptionUpdateResult.SKIPPED
                group = currentGroup
                GroupManager.postReload(groupId)

                if (subscription.updateWhenConnectedOnly ||
                    (byUser && subscription.link.startsWith("http://", ignoreCase = true))
                ) {
                    val state = SubscriptionRuntime.read().state
                    if (!state.connected && (!byUser ||
                            userInterface?.confirm(app.getString(R.string.update_subscription_warning)) != true)
                    ) return SubscriptionUpdateResult.SKIPPED
                }

                return RawUpdater.doUpdate(currentGroup, subscription, userInterface, byUser)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (e is SubscriptionResponseException) {
                    Logs.d("Subscription import failed: ${e.problem}", e)
                    Logs.w(app.getString(R.string.no_proxies_found))
                } else Logs.w(e)
                group?.let { userInterface?.onUpdateFailure(it, e.readableMessage) }
                return SubscriptionUpdateResult.RETRY
            } finally {
                updating.remove(groupId)
                progress.remove(groupId)
                if (group != null) withContext(NonCancellable) { GroupManager.postUpdate(groupId) }
            }
        }

    }

}
