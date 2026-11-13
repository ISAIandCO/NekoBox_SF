package io.nekohasekai.sagernet.fmt.warp

import org.junit.Assert.*
import org.junit.Test

class WarpConfigTest {
    private fun config(amnezia: Boolean) = WarpConfig(name = "WARP", amnezia = amnezia,
        identity = WarpIdentity("device", "token", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "162.159.192.1", 2408,
            "172.16.0.2/32\n2606:4700:110::2/128", "AQID", listOf("<b 0x01020304>")))

    @Test fun wireguardExitDialsMainVpnAndPreservesRegistrationParameters() {
        val outbound = config(false).outbound("warp-exit", "vpn-selector")
        assertEquals("vpn-selector", outbound._hack_config_map["detour"])
        assertEquals("warp-exit", outbound._hack_config_map["tag"])
        assertEquals("162.159.192.1", outbound.server)
        assertEquals("AQID", outbound.reserved)
        assertEquals(1280, outbound.mtu)
        assertEquals(listOf("172.16.0.2/32", "2606:4700:110::2/128"), outbound.local_address)
        val peer = (outbound._hack_config_map["peers"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("0.0.0.0/0", "::/0"), peer["allowed_ips"])
        assertEquals("10", peer["persistent_keepalive_interval"])
        assertFalse(outbound._hack_config_map.containsKey("jc"))
    }

    @Test fun amneziaCamouflagesWithoutChangingCloudflareHandshakeHeaders() {
        val bean = config(true).toBean()
        assertTrue(bean.isAmneziaWG)
        assertEquals(listOf("1", "2", "3", "4"), listOf(bean.h1, bean.h2, bean.h3, bean.h4))
        assertEquals(listOf(0, 0, 0, 0), listOf(bean.s1, bean.s2, bean.s3, bean.s4))
        assertEquals("<b 0x01020304>", bean.i1)
        assertEquals(4, config(true).outbound("warp", "vpn")._hack_config_map["jc"])
    }

    @Test fun rejectsSelfDetour() {
        assertThrows(IllegalArgumentException::class.java) { config(false).outbound("warp", "warp") }
    }
}
