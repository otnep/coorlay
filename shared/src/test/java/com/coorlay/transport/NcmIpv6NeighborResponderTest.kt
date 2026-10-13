package com.coorlay.transport

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class NcmIpv6NeighborResponderTest {
    private val host = ip("fe80::2")
    private val phone = ip("fe80::ca1:cad2:ba45:5ed6")
    private val hostMac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val responder = NcmIpv6NeighborResponder(host, hostMac)

    @Test fun multicastResolutionProducesUnicastSolicitedAdvertisementWithEthernetAddress() {
        val reply = responder.reply(request())!!
        assertEquals(72, reply.size)
        assertArrayEquals(host, reply.copyOfRange(8, 24)); assertArrayEquals(phone, reply.copyOfRange(24, 40))
        assertArrayEquals(host, reply.copyOfRange(48, 64)); assertArrayEquals(hostMac, reply.copyOfRange(66, 72))
        assertEquals(136, reply[40].toInt() and 255); assertEquals(0x60, reply[44].toInt() and 255)
        assertEquals(255, reply[7].toInt() and 255); assertEquals(0xffff, checksumSum(reply))
    }
    @Test fun unicastReachabilityProbeWorksWithoutASourceLinkLayerOption() {
        val reply = responder.reply(request(destination = host))!!
        assertArrayEquals(phone, reply.copyOfRange(24, 40)); assertEquals(0xffff, checksumSum(reply))
    }
    @Test fun duplicateAddressProbeIsAnsweredToAllNodesWithoutSolicitedFlag() {
        val reply = responder.reply(request(source = ByteArray(16)))!!
        assertArrayEquals(ip("ff02::1"), reply.copyOfRange(24, 40)); assertEquals(0x20, reply[44].toInt())
        assertArrayEquals(byteArrayOf(0x33, 0x33, 0, 0, 0, 1), EthernetIpv6Codec.multicastDestinationMac(reply))
        assertEquals(0xffff, checksumSum(reply))
    }
    @Test fun ethernetViewPaddingAndOffsetsDoNotChangeTheResponse() {
        val packet = request()
        val ethernet = EthernetIpv6Codec.build(byteArrayOf(2, 0, 0, 0, 0, 3), hostMac, packet) + ByteArray(20)
        assertArrayEquals(responder.reply(packet), responder.reply(ethernet, 14, packet.size + 20))
        assertArrayEquals(packet, ethernet.copyOfRange(14, 14 + packet.size))
    }
    @Test fun foreignTargetsIncludingThePhoneDadAreNeverAdvertised() {
        assertNull(responder.reply(request(target = phone)))
        assertNull(responder.reply(request(source = ByteArray(16), target = phone)))
        assertNull(responder.reply(request(destination = ip("ff02::2"))))
    }
    @Test fun invalidCodeHopLimitChecksumLengthsAndOptionsAreRejected() {
        val corruptions = listOf<(ByteArray) -> Unit>(
            { it[7] = 254.toByte() }, { it[41] = 1 }, { it[42] = (it[42].toInt() xor 1).toByte() },
            { it[5] = 23 }, { it[5] = 40 }, { it[6] = 44 }, { it[0] = 0x40 })
        corruptions.forEach { mutate -> assertNull(responder.reply(request().also(mutate))) }
        assertNull(responder.reply(request(option = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))))
        assertNull(responder.reply(request(option = byteArrayOf(1, 2, 0, 0, 0, 0, 0, 0))))
        assertNull(responder.reply(request(option = byteArrayOf(9))))
        assertNull(responder.reply(ByteArray(63))); assertNull(responder.reply(request(), -1))
        assertNull(responder.reply(request(), 0, Int.MAX_VALUE))
    }
    @Test fun dadMustUseSolicitedNodeAndMustNotIncludeSourceAddressOption() {
        assertNull(responder.reply(request(source = ByteArray(16), destination = host)))
        assertNull(responder.reply(request(source = ByteArray(16), option = byteArrayOf(1, 1, 2, 0, 0, 0, 0, 3))))
        assertNull(responder.reply(request(source = ip("ff02::1"))))
    }
    @Test fun validUnknownOptionsDoNotPreventAddressResolution() {
        val reply = responder.reply(request(option = byteArrayOf(99, 1, 0, 0, 0, 0, 0, 0)))!!
        assertEquals(0xffff, checksumSum(reply))
    }
    private fun request(source: ByteArray = phone, destination: ByteArray = ip("ff02::1:ff00:2"),
                        target: ByteArray = host, option: ByteArray = byteArrayOf()): ByteArray {
        val packet = ByteArray(64 + option.size)
        packet[0] = 0x60; packet[5] = (24 + option.size).toByte(); packet[6] = 58; packet[7] = 255.toByte()
        source.copyInto(packet, 8); destination.copyInto(packet, 24)
        packet[40] = 135.toByte(); target.copyInto(packet, 48); option.copyInto(packet, 64)
        val sum = checksumSum(packet).inv() and 65535
        packet[42] = (sum ushr 8).toByte(); packet[43] = sum.toByte()
        return packet
    }
    // Independent reference over an explicitly serialized IPv6 pseudo-header.
    private fun checksumSum(packet: ByteArray): Int {
        val count = ((packet[4].toInt() and 255) shl 8) or (packet[5].toInt() and 255)
        val pseudo = packet.copyOfRange(8, 40) + byteArrayOf(0, 0, packet[4], packet[5], 0, 0, 0, 58) +
            packet.copyOfRange(40, 40 + count)
        var sum = 0
        for (index in pseudo.indices step 2) {
            sum += (pseudo[index].toInt() and 255) shl 8
            if (index + 1 < pseudo.size) sum += pseudo[index + 1].toInt() and 255
            sum = (sum and 65535) + (sum ushr 16)
        }
        return (sum and 65535) + (sum ushr 16)
    }
    private fun ip(value: String) = InetAddress.getByName(value).address
}
