package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionGroupConfigTest {
    @Test fun candidateOrderAndConnectionChangesRequireReload() {
        val running = config(node(1, 1080), node(2, 1081))
        assertFalse(running.hasSameCandidateConfig("group", config(node(2, 1081), node(1, 1080)), "group"))
        assertFalse(running.hasSameCandidateConfig("group", config(node(1, 2080), node(2, 1081)), "group"))
        assertFalse(running.hasSameCandidateConfig("group", config(node(2, 1081)), "group"))
    }

    @Test fun unchangedMatchingNodesKeepTheConnection() {
        val running = config(node(1, 1080))
        val updated = config(node(1, 1080, "Renamed"))
        assertTrue(running.hasSameCandidateConfig("group", updated, "group"))
    }

    private fun node(id: Long, port: Int, label: String = "Node"): ProxyEntity =
        ProxyEntity(id = id, groupId = 42).putBean(SOCKSBean().apply {
            initializeDefaultValues()
            name = label
            serverPort = port
        })

    private fun config(vararg nodes: ProxyEntity): ConfigBuildResult {
        val entries = nodes.associate { "node-${it.id}" to listOf(it) }
        return ConfigBuildResult("", emptyList(), 3, entries, mapOf(3L to "group"), -1).apply {
            subscriptionProbeGroups = mapOf("group" to SubscriptionProbeGroup(entries.keys.toList(), setOf(42L)))
        }
    }
}
