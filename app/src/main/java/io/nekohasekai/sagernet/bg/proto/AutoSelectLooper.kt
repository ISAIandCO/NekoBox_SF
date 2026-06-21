package io.nekohasekai.sagernet.bg.proto

import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutoSelectLooper(private val proxy: ProxyInstance) : CoroutineScope {

    override val coroutineContext = Dispatchers.Default + Job()

    fun start() {
        val groups = proxy.config.autoSelectGroups
        if (groups.isEmpty()) return
        launch {
            groups.forEach { selectFirstAvailable(it) }
            while (isActive) {
                delay(AUTO_SELECT_CHECK_INTERVAL)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && SagerNet.power.isDeviceIdleMode) {
                    continue
                }
                groups.forEach { selectFirstAvailable(it) }
            }
        }
    }

    suspend fun stop() {
        coroutineContext[Job]?.cancel()
    }

    private suspend fun selectFirstAvailable(group: ConfigBuildResult.AutoSelectGroup) {
        for (profile in group.profiles) {
            val tag = group.tagMap[profile.id] ?: continue
            val latency = try {
                UrlTest().doTest(profile)
            } catch (e: Exception) {
                Logs.w("Auto select test failed: ${profile.displayName()}: ${e.readableMessage}")
                continue
            }
            if (latency > 0) {
                if (!proxy.box.selectOutboundFor(group.selectorTag, tag)) {
                    Logs.w("Auto select failed to select ${group.selectorTag} -> $tag")
                }
                return
            }
        }
        Logs.w("Auto select found no available proxy for ${group.selectorTag}")
    }

    private companion object {
        const val AUTO_SELECT_CHECK_INTERVAL = 10 * 60 * 1000L
    }
}
