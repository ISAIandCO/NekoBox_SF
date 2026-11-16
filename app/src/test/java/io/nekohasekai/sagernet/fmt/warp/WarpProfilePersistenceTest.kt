package io.nekohasekai.sagernet.fmt.warp

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.*
import org.junit.Test

class WarpProfilePersistenceTest {
    private fun profiles(): List<AbstractBean> = listOf(SOCKSBean(), WireGuardBean()) +
        listOf(ChainBean.STRATEGY_CHAIN, ChainBean.STRATEGY_FASTEST, ChainBean.STRATEGY_WATERFALL).map {
            ChainBean().apply { strategy = it; proxies = listOf(11L, 22L) }
        }

    @Test fun databaseBackupAndProfileCopiesKeepTheirExitSelection() {
        for (source in profiles()) {
            source.initializeDefaultValues()
            source.name = "profile"
            source.customConfigJson = "{\"log\":{\"level\":\"warn\"}}"
            source.customOutboundJson = "{\"test\":true}"
            for (selection in listOf("", WARP_EXIT_DISABLED, "saved-warp-configuration")) {
                source.warpExit = selection
                val restored = source.clone() // Same Kryo serialization as DB, Parcel and backups.
                assertEquals(selection, restored.warpExit)
                assertEquals(source.name, restored.name)
                assertEquals(source.customConfigJson, restored.customConfigJson)
                assertEquals(source.customOutboundJson, restored.customOutboundJson)
                if (source is ChainBean) {
                    assertEquals(source.strategy, (restored as ChainBean).strategy)
                    assertEquals(source.proxies, restored.proxies)
                }
            }
        }
    }

    @Test fun oldProfileMetadataInheritsRoutingWithoutLosingExistingFields() {
        for (source in profiles()) {
            source.initializeDefaultValues()
            val output = ByteBufferOutput(1024)
            source.serialize(output)
            output.writeInt(1) // Shared metadata from versions before per-profile WARP.
            output.writeString("legacy")
            output.writeString("{\"test\":true}")
            output.writeString("{\"log\":{\"level\":\"warn\"}}")
            val restored = KryoConverters.deserialize(source.clone(), output.toBytes())
            assertEquals("", restored.warpExit)
            assertEquals("legacy", restored.name)
            assertEquals("{\"test\":true}", restored.customOutboundJson)
            assertEquals("{\"log\":{\"level\":\"warn\"}}", restored.customConfigJson)
            if (source is ChainBean) {
                assertEquals(source.strategy, (restored as ChainBean).strategy)
                assertEquals(source.proxies, restored.proxies)
            }
        }
    }
}
