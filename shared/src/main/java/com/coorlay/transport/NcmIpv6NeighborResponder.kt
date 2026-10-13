package com.coorlay.transport

/** Own-address NDP for the Ethernet side of a layer-3 TUN, independent of kernel ND policy.
 * RFC 4861 sections 7.1.1 and 7.2.4; never advertises a peer's or another interface's address.
 */
class NcmIpv6NeighborResponder(hostAddress: ByteArray, hostMac: ByteArray) {
    private val address = hostAddress.copyOf().also {
        require(it.size == 16 && it[0] == 0xfe.toByte() && (it[1].toInt() and 0xc0) == 0x80)
    }
    private val mac = hostMac.copyOf().also {
        require(it.size == 6 && (it[0].toInt() and 1) == 0 && it.any { byte -> byte != 0.toByte() })
    }
    private val solicitedNode = ByteArray(16).apply {
        this[0] = 0xff.toByte(); this[1] = 2; this[11] = 1; this[12] = 0xff.toByte()
        address.copyInto(this, 13, 13, 16)
    }

    /** Returns a complete IPv6 NA, or null for other traffic, foreign targets and invalid NS. */
    fun reply(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset): ByteArray? {
        if (offset < 0 || length < 64 || offset > packet.size - length) return null
        if ((packet[offset].toInt() and 0xf0) != 0x60 || packet[offset + 6] != 58.toByte() ||
            (packet[offset + 7].toInt() and 255) != 255 ||
            (packet[offset + 40].toInt() and 255) != 135 || packet[offset + 41] != 0.toByte()) return null
        val payload = u16(packet, offset + 4)
        if (payload < 24 || payload > length - 40 || !matches(packet, offset + 48, address)) return null
        val dad = (offset + 8 until offset + 24).all { packet[it] == 0.toByte() }
        if ((packet[offset + 8].toInt() and 255) == 255) return null // multicast source
        val multicast = matches(packet, offset + 24, solicitedNode)
        if ((!multicast && !matches(packet, offset + 24, address)) || (dad && !multicast)) return null
        if (checksumSum(packet, offset, payload) != 0xffff) return null
        var option = offset + 64
        val end = offset + 40 + payload
        while (option < end) {
            if (option + 2 > end) return null
            val bytes = (packet[option + 1].toInt() and 255) * 8
            if (bytes == 0 || bytes > end - option || (dad && packet[option] == 1.toByte())) return null
            option += bytes
        }
        return ByteArray(72).apply {
            this[0] = 0x60; this[5] = 32; this[6] = 58; this[7] = 0xff.toByte()
            address.copyInto(this, 8)
            if (dad) { this[24] = 0xff.toByte(); this[25] = 2; this[39] = 1 }
            else packet.copyInto(this, 24, offset + 8, offset + 24)
            this[40] = 136.toByte(); this[44] = if (dad) 0x20 else 0x60 // Override, Solicited only for unicast
            address.copyInto(this, 48)
            this[64] = 2; this[65] = 1; mac.copyInto(this, 66)
            val checksum = checksumSum(this, 0, 32).inv() and 0xffff
            this[42] = (checksum ushr 8).toByte(); this[43] = checksum.toByte()
        }
    }

    private fun matches(packet: ByteArray, offset: Int, value: ByteArray): Boolean =
        value.indices.all { packet[offset + it] == value[it] }

    private fun checksumSum(packet: ByteArray, offset: Int, payload: Int): Int {
        var sum = payload.toLong() + 58
        for (i in offset + 8 until offset + 40 step 2) sum += u16(packet, i)
        val end = offset + 40 + payload
        var i = offset + 40
        while (i + 1 < end) { sum += u16(packet, i); i += 2 }
        if (i < end) sum += (packet[i].toInt() and 255) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.toInt()
    }
    private fun u16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255)
}
