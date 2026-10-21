package com.coorlay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophonePacketizerTest {
    @Test
    fun packetUsesRtpHeaderAndInputKeyAad() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val counters = MicrophoneCounters()
        val body = byteArrayOf(0x28, 0x01, 0x02, 0x03)

        val packet = MicrophonePacketizer.sealPacket(key, 100, counters, body, 480)

        assertEquals(0x80, packet[0].toInt() and 0xff)
        assertEquals(100, packet[1].toInt() and 0xff)
        assertEquals(0, ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff))
        assertEquals(
            0,
            ((packet[4].toInt() and 0xff) shl 24) or
                ((packet[5].toInt() and 0xff) shl 16) or
                ((packet[6].toInt() and 0xff) shl 8) or
                (packet[7].toInt() and 0xff),
        )

        val sealedEnd = packet.size - MicrophonePacketizer.NONCE_LEN
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, sealedEnd, packet.size)
        val opened = AirPlayCrypto.chachaOpen(
            key,
            nonce,
            packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, sealedEnd),
            packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN),
        )

        assertArrayEquals(body, opened)
        assertEquals(1, counters.sequence)
        assertEquals(480, counters.timestamp)
        assertEquals(1L, counters.nonce)
    }

    @Test
    fun reusedSealerSendsTheSameBytesAsSealPacket() {
        val key = ByteArray(32) { index -> (index * 7 + 3).toByte() }
        val expected = MicrophoneCounters(sequence = 0xfffe, timestamp = 1000, nonce = 41)
        val reused = MicrophoneCounters(sequence = 0xfffe, timestamp = 1000, nonce = 41)
        val sealer = MicrophonePacketizer.PacketSealer(key, 100)
        // Shorter bodies after longer ones: the reused buffer keeps stale bytes past the packet.
        for ((index, size) in listOf(1920, 80, 3, 1920, 0, 120).withIndex()) {
            val body = ByteArray(size + 5) { (it * 13 + index).toByte() }
            val packet = MicrophonePacketizer.sealPacket(key, 100, expected, body.copyOf(size), 960)
            val length = sealer.seal(reused, body, size, 960)
            assertArrayEquals("packet $index", packet, sealer.packet.copyOf(length))
        }
        assertEquals(expected.sequence, reused.sequence)
        assertEquals(expected.timestamp, reused.timestamp)
        assertEquals(expected.nonce, reused.nonce)
    }

    @Test
    fun wirePcmInPlaceMatchesTheCopy() {
        val pcm = ByteArray(641) { (it * 31).toByte() }
        val output = ByteArray(700) { 0x55 }
        MicrophonePacketizer.toWirePcmInto(pcm, pcm.size, output)
        assertArrayEquals(MicrophonePacketizer.toWirePcm(pcm), output.copyOf(pcm.size))
        assertEquals(0x55, output[pcm.size].toInt())
    }

    @Test
    fun pcmIsConvertedToBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            MicrophonePacketizer.toWirePcm(
                byteArrayOf(0x34, 0x12, 0x78, 0x56),
            ),
        )
    }
}
