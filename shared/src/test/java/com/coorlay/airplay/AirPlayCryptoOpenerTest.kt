package com.coorlay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AirPlayCryptoOpenerTest {
    private val key = ByteArray(32) { (it * 3 + 1).toByte() }

    /** An audio datagram: 12-byte RTP header, ciphertext + tag sealed with header[4..12] as AAD, 8-byte nonce. */
    private fun datagram(payload: ByteArray, counter: Long): ByteArray {
        val header = ByteArray(12) { (0x80 + it).toByte() }
        val nonce = AirPlayCrypto.nonce64(counter)
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12))
        return header + sealed + nonce.copyOfRange(4, 12)
    }

    @Test fun reusedOpenerMatchesTheOneShotOpenForManyPackets() {
        val opener = AirPlayCrypto.Opener(key)
        val buffer = ByteArray(4096)
        for (counter in 0L until 50L) {
            val payload = ByteArray((counter * 37 % 900 + 1).toInt()) { (it + counter).toByte() }
            val wire = datagram(payload, counter)
            // Leftovers of a longer earlier datagram must not matter.
            wire.copyInto(buffer)
            val sealedEnd = wire.size - 8
            val output = ByteArray(12 + sealedEnd - 12 - AirPlayCrypto.Opener.TAG_SIZE)
            val plain = opener.open(buffer, 4, 8, 12, sealedEnd - 12, sealedEnd, output, 12)
            assertEquals(payload.size, plain)
            assertArrayEquals(payload, output.copyOfRange(12, output.size))
        }
    }

    @Test fun aTamperedTagIsRejectedAndTheOpenerStillWorksAfterwards() {
        val opener = AirPlayCrypto.Opener(key)
        val good = datagram(byteArrayOf(1, 2, 3), 7)
        val bad = good.copyOf().apply { this[size - 9] = (this[size - 9].toInt() xor 1).toByte() }
        val output = ByteArray(15)
        try { opener.open(bad, 4, 8, 12, bad.size - 20, bad.size - 8, output, 12); fail("tampered packet opened") }
        catch (_: Exception) { }
        assertEquals(3, opener.open(good, 4, 8, 12, good.size - 20, good.size - 8, output, 12))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.copyOfRange(12, 15))
    }
}
