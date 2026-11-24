package io.nekohasekai.sagernet.bg

import android.content.Intent
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hasSameCandidateConfig
import io.nekohasekai.sagernet.fmt.subscriptionProfileRevisions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl

internal fun canApplySubscriptionUpdate(
    powerRestricted: Boolean, deviceIdle: Boolean, networkAvailable: Boolean, recoveryFailed: Boolean,
) = networkAvailable && !deviceIdle && (!powerRestricted || recoveryFailed)

/** Coordinates subscription changes for the lifetime of the running VPN in the main dispatcher. */
internal class SubscriptionReload(private val service: BaseService.Interface) {
    companion object {
        const val GROUP_ID = "groupId"
        private const val UPDATE_DEBOUNCE_MS = 400L

        fun notifyChanged(groupId: Long) {
            runCatching {
                if (DataStore.restartProfileOnSubscriptionUpdate) {
                    app.sendBroadcast(Intent(Action.SUBSCRIPTION_UPDATED).setPackage(app.packageName)
                        .putExtra(GROUP_ID, groupId))
                }
            }.onFailure { Logs.w("Failed to signal subscription update to the VPN") }
        }
    }

    @Volatile private var pendingGroups = emptySet<Long>()
    val hasPendingChanges get() = pendingGroups.isNotEmpty()
    private val checkedRevisions = mutableMapOf<Long, Map<Long, Long>>()
    private var job: Job? = null
    private var checkAgain = false
    private var failedGroupTag: String? = null
    var holdsProbes = false
        private set

    fun onSubscriptionChanged(groupId: Long) {
        if (service !is VpnService || groupId <= 0) return
        val config = service.data.proxy?.takeIf { it.isInitialized() }?.config
        if (config != null && config.subscriptionProbeGroups.values.none { groupId in it.subscriptionIds }) return
        pendingGroups += groupId
        checkPending()
    }

    fun onConnected(appliedPreparedConfig: Boolean) {
        if (appliedPreparedConfig) schedule() else checkPending()
    }
    fun onNetworkAvailable() = checkPending()
    fun onUnlocked() = schedule(holdProbes = true)
    fun onProfileSelected() = schedule()

    fun onRecoveryFailed(tag: String) {
        val proxy = service.data.proxy ?: return
        if (!proxy.isInitialized() || proxy.config.profileTagMap[proxy.activeProfileId] != tag) return
        if (proxy.config.subscriptionProbeGroups[tag]?.subscriptionIds?.none { it in pendingGroups } != false) return
        if (!ready(recoveryFailed = true)) return
        failedGroupTag = tag
        schedule()
    }

    private fun checkPending() {
        val proxy = service.data.proxy ?: return
        if (!proxy.isInitialized()) return
        val tag = proxy.config.profileTagMap[proxy.activeProfileId] ?: return
        if (proxy.config.subscriptionProbeGroups[tag]?.subscriptionIds?.any { it in pendingGroups } == true) schedule()
    }

    fun clear() {
        job?.cancel()
        job = null
        pendingGroups = emptySet()
        checkedRevisions.clear()
        checkAgain = false
        failedGroupTag = null
        holdsProbes = false
    }

    private fun ready(recoveryFailed: Boolean): Boolean {
        val data = service.data
        return service is VpnService && DataStore.baseService === service && data.state.connected &&
            data.networkRestartJob == null && DataStore.serviceMode == Key.MODE_VPN &&
            DataStore.restartProfileOnSubscriptionUpdate &&
            canApplySubscriptionUpdate(data.powerRestricted, SagerNet.power.isDeviceIdleMode,
                SagerNet.underlyingNetwork != null, recoveryFailed)
    }

    private fun schedule(holdProbes: Boolean = false) {
        val proxy = service.data.proxy ?: return
        if (!proxy.isInitialized()) return
        val tag = proxy.config.profileTagMap[proxy.activeProfileId] ?: return
        if (proxy.config.subscriptionProbeGroups[tag]?.subscriptionIds.isNullOrEmpty()) return
        if (!ready(failedGroupTag == tag)) return
        holdsProbes = holdsProbes || holdProbes
        if (job != null) {
            checkAgain = true
            return
        }
        job = runOnMainDispatcher {
            try {
                do {
                    delay(if (holdsProbes || failedGroupTag != null) 1L else UPDATE_DEBOUNCE_MS)
                    checkAgain = false
                    if (checkForRestart()) return@runOnMainDispatcher
                } while (checkAgain)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Logs.w("Failed to check for VPN subscription changes")
            } finally {
                if (job === coroutineContext[Job]) {
                    job = null
                    failedGroupTag = null
                    if (holdsProbes) {
                        holdsProbes = false
                        service.data.syncProbePause()
                    }
                }
            }
        }
    }

    private suspend fun checkForRestart(): Boolean {
        val running = service.data.proxy ?: return false
        val id = running.activeProfileId
        val tag = running.config.profileTagMap[id] ?: return false
        val groups = running.config.subscriptionProbeGroups[tag]?.subscriptionIds.orEmpty()
        if (groups.isEmpty() || !ready(failedGroupTag == tag)) return false
        val underlyingNetwork = SagerNet.underlyingNetwork
        val selectedId = DataStore.selectedProxy
        val revisions = withContext(Dispatchers.IO) { subscriptionProfileRevisions().filterKeys { it in groups } }
        if (checkAgain) return false
        if (revisions == running.config.subscriptionRevisions.filterKeys { it in groups } ||
            revisions == checkedRevisions[id]
        ) {
            pendingGroups -= groups
            return false
        }

        val replacement = withContext(Dispatchers.IO) {
            try {
                val profile = SagerDatabase.proxyDao.getById(id) ?: return@withContext null
                val config = buildConfig(profile, trackSubscriptionUpdates = true)
                val newTag = config.profileTagMap[id] ?: return@withContext null
                if (running.config.hasSameCandidateConfig(tag, config, newTag)) return@withContext null
                ProxyInstance(profile, service, config).also {
                    it.initExternalPlugins(config)
                    Libcore.checkConfig(config.config, LocalResolverImpl)
                }
            } catch (_: Exception) {
                Logs.w("VPN subscription changes could not be validated. Keeping the current connection.")
                null
            }
        }

        val latest = withContext(Dispatchers.IO) { subscriptionProfileRevisions().filterKeys { it in groups } }
        if (latest != revisions) checkAgain = true
        if (checkAgain || service.data.proxy !== running || running.activeProfileId != id) return false
        if (SagerNet.underlyingNetwork != underlyingNetwork || DataStore.selectedProxy != selectedId ||
            !ready(failedGroupTag == tag)
        ) return false

        if (replacement == null) {
            checkedRevisions[id] = revisions
            pendingGroups -= groups
            return false
        }

        holdsProbes = false
        Logs.i("Restarting the active VPN group after a subscription update")
        service.stopRunner(restart = true, preparedProxy = replacement)
        return true
    }
}
