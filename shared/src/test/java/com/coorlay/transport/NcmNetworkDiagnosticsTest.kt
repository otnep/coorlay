package com.coorlay.transport

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class NcmNetworkDiagnosticsTest {
    private val lines = mutableListOf<String>()
    private var clock = 0L
    private val diagnostics = NcmNetworkDiagnostics("inbound", lines::add) { clock }
    private fun packet(protocol: Int, body: ByteArray): ByteArray = ByteArray(40 + body.size).apply {
        this[0] = 0x60; this[4] = (body.size ushr 8).toByte(); this[5] = body.size.toByte()
        this[6] = protocol.toByte(); this[7] = 0xff.toByte()
        InetAddress.getByName("fe80::3").address.copyInto(this, 8)
        InetAddress.getByName("fe80::2").address.copyInto(this, 24)
        body.copyInto(this, 40)
    }
    private fun mdns(): ByteArray = packet(17, ByteArray(36).apply {
        this[0] = 0x14; this[1] = 0xe9.toByte(); this[2] = 0x14; this[3] = 0xe9.toByte()
        this[5] = 36; this[12] = 0; this[13] = 1
        "private-payload".toByteArray().copyInto(this, 20)
    })
    private fun solicitation(): ByteArray = packet(58, ByteArray(24).apply {
        this[0] = 135.toByte(); InetAddress.getByName("fe80::2").address.copyInto(this, 8)
    })
    private fun tcp(): ByteArray = ByteArray(20).apply {
        this[0] = 0x12; this[1] = 0x34; this[2] = 0x1b; this[3] = 0x58
        this[12] = 0x50; this[13] = 2
    }

    @Test fun mdnsCannotConsumeNeighborBudgetOrExposeDnsPayload() {
        repeat(100) { diagnostics.ipv6(mdns()) }
        diagnostics.ipv6(solicitation(), ownNeighborReply = true)
        assertEquals(4, lines.count { "mDNS" in it })
        assertEquals(1, lines.count { "icmp6=135" in it && "ownReply=generated" in it })
        assertFalse(lines.any { "private-payload" in it })
        diagnostics.finish()
        assertTrue(lines.last().contains("udp=100 ns=1"))
    }
    @Test fun tcpWithDestinationOptionsIsIdentifiedAndEndsInspection() {
        val options = ByteArray(8).apply { this[0] = 6 }
        val ipv6 = packet(60, options + tcp())
        val padded = byteArrayOf(0, 1, 2) + ipv6 + ByteArray(20)
        diagnostics.ipv6(padded, 3, ipv6.size)
        val line = lines.single { "first inbound TCP" in it }
        assertTrue(line.contains("extensions=60")); assertTrue(line.contains("tcp=4660->7000 flags=0x2"))
        val before = lines.size
        repeat(100) { diagnostics.ipv6(solicitation()) }
        diagnostics.finish()
        assertEquals(before, lines.size)
    }
    @Test fun laterFragmentsDoNotProduceFalseTcpEvents() {
        diagnostics.ipv6(packet(44, ByteArray(8).apply { this[0] = 6; this[3] = 8 } + tcp()))
        assertFalse(lines.any { "TCP" in it })
        assertTrue(lines.single().contains("next=44"))
    }
    @Test fun malformedLengthsAndExtensionChainAreBounded() {
        diagnostics.ipv6(packet(0, byteArrayOf(58, 5)))
        diagnostics.ipv6(packet(58, ByteArray(24)).apply { this[5] = 100 })
        diagnostics.ipv6(packet(0, ByteArray(9 * 8)))
        diagnostics.ipv6(ByteArray(39))
        diagnostics.ipv6(ByteArray(40), -1, 40)
        diagnostics.finish()
        assertEquals(4, lines.count { "malformed IPv6" in it })
        assertTrue(lines.last().contains("malformed=5"))
    }
    @Test fun timerReportsEvenWhenPhoneIsSilentAndStopsAfterWindow() {
        clock = 5000; diagnostics.tick()
        assertTrue(lines.single().contains("reason=waiting-tcp"))
        clock = 90_000; diagnostics.tick()
        assertTrue(lines.last().contains("reason=window-ended"))
        diagnostics.ipv6(solicitation()); clock = 100_000; diagnostics.tick(); diagnostics.finish()
        assertEquals(2, lines.size)
    }
    @Test fun droppedEtherTypesAndIcmpErrorsAreRecordedWithoutPayload() {
        diagnostics.droppedEthernet(ByteArray(18).apply { this[12] = 0x81.toByte() })
        diagnostics.droppedEthernet(ByteArray(4))
        diagnostics.ipv6(packet(58, byteArrayOf(1, 4, 0, 0)))
        diagnostics.finish()
        assertTrue(lines.any { "type=0x8100" in it }); assertTrue(lines.any { "type=truncated" in it })
        assertTrue(lines.any { "ICMP error" in it && "icmp6=1 code=4" in it })
        assertTrue(lines.last().contains("icmpErrors=1")); assertTrue(lines.last().contains("droppedEthernet=2"))
    }
    @Test fun neighborSamplingIsBoundedAndForeignTargetIsVisible() {
        val foreign = solicitation().apply { this[63] = 9 }
        repeat(100) { diagnostics.ipv6(foreign) }
        diagnostics.finish()
        assertEquals(12, lines.count { "icmp6=135" in it })
        assertTrue(lines.first().contains("target=")); assertTrue(lines.first().contains("ownReply=none"))
        assertTrue(lines.last().contains("ns=100"))
    }
    @Test fun shortTransportAndInvalidUdpLengthCannotReadPaddingAsHeader() {
        diagnostics.ipv6(packet(6, ByteArray(19)))
        diagnostics.ipv6(packet(17, ByteArray(8).apply { this[5] = 32 }))
        diagnostics.ipv6(packet(58, ByteArray(3)))
        diagnostics.finish()
        assertFalse(lines.any { "TCP" in it || "mDNS" in it })
        assertTrue(lines.last().contains("malformed=3"))
    }
}
