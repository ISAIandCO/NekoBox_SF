package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import kotlinx.coroutines.runBlocking

class ProxyInstance(
    profile: ProxyEntity,
    var service: BaseService.Interface? = null,
    private val preparedConfig: ConfigBuildResult? = null,
) : BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    @Volatile var activeProfileId = profile.id
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        config = preparedConfig ?: buildConfig(profile, trackSubscriptionUpdates = service is VpnService)
        lastSelectorGroupId = config.selectorGroupId
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
    }

    override suspend fun loadConfig() {
        super.loadConfig()
        // Register before start so the first accepted connection is counted.
        val tags = config.trafficMap.keys + listOf("proxy", "bypass", "direct")
        box.setV2rayStats(tags.distinct().joinToString("\n"))
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        looper = service?.let { TrafficLooper(it.data) }
        looper?.start()
    }

    override fun close() {
        try {
            runBlocking {
                looper?.stop()
                looper = null
            }
        } finally {
            super.close()
        }
    }
}
