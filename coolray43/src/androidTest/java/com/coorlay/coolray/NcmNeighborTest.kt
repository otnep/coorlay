package com.coorlay.coolray

import android.test.AndroidTestCase
import com.coorlay.transport.EthernetIpv6Codec
import com.coorlay.transport.NcmIpv6NeighborResponder
import com.coorlay.transport.NcmNetworkDiagnostics
import java.net.InetAddress

/** Frozen RFC packet built with Python struct/checksum, exercised by Dalvik rather than the JVM. */
class NcmNeighborTest : AndroidTestCase() {
    private val host = InetAddress.getByName("fe80::2").address
    private val mac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val responder = NcmIpv6NeighborResponder(host, mac)
    private fun fixture(): ByteArray = "6000000000183afffe800000000000000ca1cad2ba455ed6".plus(
        "ff0200000000000000000001ff00000287008d1200000000fe800000000000000000000000000002")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun testEthernetNeighborReplyCanBeGeneratedBeforeTunInput() {
        val request = fixture()
        val peerMac = byteArrayOf(2, 0, 0, 0, 0, 3)
        val frame = EthernetIpv6Codec.build(peerMac, byteArrayOf(0x33, 0x33, 0xff.toByte(), 0, 0, 2), request)
        val view = EthernetIpv6Codec.parseIpv6View(frame)!!
        val reply = responder.reply(frame, view.payloadOffset, view.payloadLength)!!
        assertEquals(136, reply[40].toInt() and 255); assertEquals(0x60, reply[44].toInt() and 255)
        assertTrue(host.contentEquals(reply.copyOfRange(48, 64)))
        assertTrue(request.copyOfRange(8, 24).contentEquals(reply.copyOfRange(24, 40)))
        val ethernetReply = EthernetIpv6Codec.build(mac, view.sourceMac, reply)
        assertTrue(peerMac.contentEquals(ethernetReply.copyOfRange(0, 6)))
        assertTrue(mac.contentEquals(reply.copyOfRange(66, 72)))
    }
    fun testForeignAddressAndBadChecksumNeverCreateAReply() {
        val request = fixture()
        assertNull(responder.reply(request.copyOf().apply { this[63] = 3 }))
        assertNull(responder.reply(request.copyOf().apply { this[42] = 0 }))
        assertNull(responder.reply(ByteArray(63)))
    }
    fun testBringUpMetadataOnDalvikIncludesNeighborTarget() {
        val lines = mutableListOf<String>()
        val diagnostics = NcmNetworkDiagnostics("inbound", { lines.add(it) }) { 0L }
        diagnostics.ipv6(fixture(), ownNeighborReply = true)
        diagnostics.finish()
        assertTrue(lines.any { it.contains("icmp6=135") && it.contains("ownReply=generated") })
        assertTrue(lines.last().contains("ns=1"))
    }
    fun testMalformedNetworkMetadataDoesNotCrashDalvik() {
        val lines = mutableListOf<String>()
        val diagnostics = NcmNetworkDiagnostics("inbound", { lines.add(it) }) { 0L }
        diagnostics.ipv6(ByteArray(39)); diagnostics.ipv6(fixture(), -1, 64)
        diagnostics.finish()
        assertTrue(lines.last().contains("malformed=2"))
    }
}
