package io.nekohasekai.sagernet.database

import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.SubscriptionUpdateMode
import org.junit.Assert.*
import org.junit.Test

class SubscriptionBeanTest {
    @Test fun explicitlySavedReviewModeRemainsSelected() {
        val source = SubscriptionBean().apply {
            initializeDefaultValues()
            assertEquals(SubscriptionUpdateMode.APPLY_IMMEDIATELY, updateMode.toInt())
            updateMode = SubscriptionUpdateMode.REVIEW_CRITICAL
        }
        val output = ByteBufferOutput(1024)
        source.serializeToBuffer(output)
        val restored = SubscriptionBean().apply {
            deserializeFromBuffer(ByteBufferInput(output.toBytes()))
            initializeDefaultValues()
        }
        assertEquals(SubscriptionUpdateMode.REVIEW_CRITICAL, restored.updateMode.toInt())
    }

    @Test fun versionFiveAndShareRoundTripPreserveSettings() {
        val source = SubscriptionBean().apply {
            initializeDefaultValues()
            link = "https://subscription.test/nodes"
            sendHwid = true
            customHwidParams = "hwid=fixture"
            serverDnsResolver = "https://dns.example/dns-query"
            customHeaders = "Authorization: Bearer fixture"
            updateMode = SubscriptionUpdateMode.DEFER_ACTIVE
            lastChecked = 2_000
            reviewRequired = true
            deferredProfileIds = longArrayOf(9, 17)
            partialUpdateApplied = true
        }
        for (share in listOf(false, true)) {
            val output = ByteBufferOutput(1024)
            if (share) source.serializeForShare(output) else source.serializeToBuffer(output)
            val restored = SubscriptionBean().apply {
                if (share) deserializeFromShare(ByteBufferInput(output.toBytes())) else deserializeFromBuffer(ByteBufferInput(output.toBytes()))
                initializeDefaultValues()
            }
            assertEquals(source.link, restored.link)
            assertEquals(source.customHwidParams, restored.customHwidParams)
            assertEquals(source.sendHwid, restored.sendHwid)
            assertEquals(source.serverDnsResolver, restored.serverDnsResolver)
            assertEquals(source.customHeaders, restored.customHeaders)
            if (share) {
                assertEquals(SubscriptionUpdateMode.APPLY_IMMEDIATELY, restored.updateMode.toInt())
                assertFalse(restored.reviewRequired)
                assertTrue(restored.deferredProfileIds.isEmpty())
            } else {
                assertEquals(source.updateMode, restored.updateMode)
                assertEquals(source.lastChecked, restored.lastChecked)
                assertTrue(restored.reviewRequired)
                assertArrayEquals(source.deferredProfileIds, restored.deferredProfileIds)
                assertTrue(restored.partialUpdateApplied)
            }
        }
    }
    @Test fun oldVersionThreeKeepsHwidAndDefaultsNewFields() {
        val output = ByteBufferOutput(1024)
        output.writeInt(3)
        output.writeInt(0)
        output.writeString("https://old.example")
        output.writeBoolean(false)
        output.writeBoolean(true)
        output.writeBoolean(false)
        output.writeString("old-agent")
        output.writeBoolean(true)
        output.writeInt(1440)
        output.writeInt(100)
        output.writeString("")
        output.writeInt(0)
        output.writeString("")
        output.writeBoolean(true)
        output.writeString("hwid=old")
        val restored = SubscriptionBean().apply { deserializeFromBuffer(ByteBufferInput(output.toBytes())); initializeDefaultValues() }
        assertTrue(restored.sendHwid)
        assertEquals("hwid=old", restored.customHwidParams)
        assertEquals("", restored.serverDnsResolver)
        assertEquals("", restored.customHeaders)
        assertEquals(100, restored.lastUpdated.toInt())
        assertEquals(SubscriptionUpdateMode.APPLY_IMMEDIATELY, restored.updateMode.toInt())
        assertEquals(0, restored.lastChecked.toInt())
        assertFalse(restored.reviewRequired)
        assertTrue(restored.deferredProfileIds.isEmpty())
    }
}
