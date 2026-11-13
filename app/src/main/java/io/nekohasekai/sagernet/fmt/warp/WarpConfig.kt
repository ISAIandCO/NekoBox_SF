package io.nekohasekai.sagernet.fmt.warp

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxOutboundWireguardBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import java.util.UUID

// Kept in the existing private settings database, including its backup/restore.
class WarpIdentity(
    val id: String, val token: String, val privateKey: String, val peerPublicKey: String,
    val server: String, val port: Int, val localAddress: String, val reserved: String,
    val packets: List<String>,
)

data class WarpConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amnezia: Boolean,
    val identity: WarpIdentity,
) {
    fun toBean() = WireGuardBean().apply {
        initializeDefaultValues()
        name = this@WarpConfig.name
        serverAddress = identity.server
        serverPort = identity.port
        localAddress = identity.localAddress
        privateKey = identity.privateKey
        peerPublicKey = identity.peerPublicKey
        reserved = identity.reserved
        mtu = 1280
        persistentKeepalive = "10"
        if (amnezia) {
            require(identity.packets.size in 1..5 && identity.packets.none(String::isBlank)) {
                "WARP AmneziaWG camouflage is missing; generate the configuration again"
            }
            jc = 4; jmin = 40; jmax = 70
            // Cloudflare only accepts standard WireGuard headers and sizes.
            h1 = "1"; h2 = "2"; h3 = "3"; h4 = "4"
            i1 = identity.packets.getOrElse(0) { "" }
            i2 = identity.packets.getOrElse(1) { "" }
            i3 = identity.packets.getOrElse(2) { "" }
            i4 = identity.packets.getOrElse(3) { "" }
            i5 = identity.packets.getOrElse(4) { "" }
        }
    }

    fun outbound(tag: String, upstream: String) = buildSingBoxOutboundWireguardBean(toBean()).apply {
        require(tag != upstream) { "WARP routing loop" }
        _hack_config_map["tag"] = tag
        _hack_config_map["detour"] = upstream
    }
}

object WarpConfigs {
    fun all(): List<WarpConfig> = gson.fromJson(DataStore.warpConfigs, Array<WarpConfig>::class.java).toList()
    fun selected(): WarpConfig = all().find { it.id == DataStore.warpSelected }
        ?: error("Select a WARP configuration in Routing → Cloudflare WARP")
    fun save(config: WarpConfig) {
        val configs = all().toMutableList()
        val index = configs.indexOfFirst { it.id == config.id }
        if (index < 0) configs.add(config) else configs[index] = config
        DataStore.warpConfigs = gson.toJson(configs)
        if (DataStore.warpSelected.isEmpty()) DataStore.warpSelected = config.id
    }
    fun delete(id: String) {
        require(id != DataStore.warpSelected || !DataStore.warpEnabled) { "Disable WARP before deleting its active configuration" }
        DataStore.warpConfigs = gson.toJson(all().filterNot { it.id == id })
        if (DataStore.warpSelected == id) DataStore.warpSelected = ""
    }
}
