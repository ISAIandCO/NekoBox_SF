package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

fun buildSingBoxOutboundWireguardBean(bean: WireGuardBean): SingBoxOptions.Outbound_WireGuardOptions {
    return SingBoxOptions.Outbound_WireGuardOptions().apply {
        type = "wireguard"
        server = bean.serverAddress
        server_port = bean.serverPort
        local_address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        peer_public_key = bean.peerPublicKey
        pre_shared_key = bean.peerPreSharedKey
        mtu = bean.mtu
        if (bean.reserved.isNotBlank()) reserved = genReserved(bean.reserved)
        jc = bean.jc.trim().toIntOrNull()
        jmin = bean.jmin.trim().toIntOrNull()
        jmax = bean.jmax.trim().toIntOrNull()
        s1 = bean.s1.trim().toIntOrNull()
        s2 = bean.s2.trim().toIntOrNull()
        s3 = bean.s3.trim().toIntOrNull()
        s4 = bean.s4.trim().toIntOrNull()
        h1 = bean.h1.trim().takeIf { it.isNotBlank() }
        h2 = bean.h2.trim().takeIf { it.isNotBlank() }
        h3 = bean.h3.trim().takeIf { it.isNotBlank() }
        h4 = bean.h4.trim().takeIf { it.isNotBlank() }
        i1 = bean.i1.trim().takeIf { it.isNotBlank() }
        i2 = bean.i2.trim().takeIf { it.isNotBlank() }
        i3 = bean.i3.trim().takeIf { it.isNotBlank() }
        i4 = bean.i4.trim().takeIf { it.isNotBlank() }
        i5 = bean.i5.trim().takeIf { it.isNotBlank() }
    }
}
