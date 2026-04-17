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
        bean.jc?.takeIf { it > 0 }?.also { jc = it }
        bean.jmin?.takeIf { it > 0 }?.also { jmin = it }
        bean.jmax?.takeIf { it > 0 }?.also { jmax = it }
        bean.s1?.takeIf { it > 0 }?.also { s1 = it }
        bean.s2?.takeIf { it > 0 }?.also { s2 = it }
        bean.s3?.takeIf { it > 0 }?.also { s3 = it }
        bean.s4?.takeIf { it > 0 }?.also { s4 = it }
        bean.h1?.takeIf { it.isNotBlank() }?.also { h1 = it }
        bean.h2?.takeIf { it.isNotBlank() }?.also { h2 = it }
        bean.h3?.takeIf { it.isNotBlank() }?.also { h3 = it }
        bean.h4?.takeIf { it.isNotBlank() }?.also { h4 = it }
        bean.i1?.takeIf { it.isNotBlank() }?.also { i1 = it }
        bean.i2?.takeIf { it.isNotBlank() }?.also { i2 = it }
        bean.i3?.takeIf { it.isNotBlank() }?.also { i3 = it }
        bean.i4?.takeIf { it.isNotBlank() }?.also { i4 = it }
        bean.i5?.takeIf { it.isNotBlank() }?.also { i5 = it }
    }
}
