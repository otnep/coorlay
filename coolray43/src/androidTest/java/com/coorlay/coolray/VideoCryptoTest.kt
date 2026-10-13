package com.coorlay.coolray

import android.test.AndroidTestCase
import android.util.Log
import com.coorlay.airplay.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.BadPaddingException

/** Real API-18 JNI: equality, authenticated rejection, nonce progression and TCP delivery. */
class VideoCryptoTest : AndroidTestCase() {
    private val key = ByteArray(32) { (it * 11 + 3).toByte() }
    private val header = ByteArray(128) { (it * 3).toByte() }

    private fun native(): ScreenFrameDecryptor {
        val diagnostics = mutableListOf<String>()
        val opener = LegacyVideoCrypto.create(key) { diagnostics += it; Log.i(TAG, it) }
        assertTrue("Native backend not selected: $diagnostics", diagnostics.single().contains("native ChaCha20-Poly1305"))
        return opener
    }

    fun testNativeMatchesIndependentJavaBackendForFrameSizesAndCounters() {
        val opener = native()
        for ((size, counter) in listOf(0 to 0L, 1 to 1L, 4096 to 7L, 524288 to Long.MIN_VALUE, 1048576 to -1L)) {
            val plain = ByteArray(size) { (it * 31 + 9).toByte() }
            val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header)
            assertTrue("Native mismatch size=$size counter=$counter", opener.open(counter, header, sealed).contentEquals(plain))
        }
    }

    fun testBadTagHeaderAndNonceAreRejectedWithoutFallback() {
        val opener = native()
        val plain = ByteArray(4096) { it.toByte() }
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(5), plain, header)
        val badTag = sealed.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val badHeader = header.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
        fun reject(counter: Long, aad: ByteArray, data: ByteArray) {
            try { opener.open(counter, aad, data); fail("Unauthenticated frame accepted") }
            catch (_: BadPaddingException) { }
        }
        reject(5, header, badTag); reject(5, badHeader, sealed); reject(6, header, sealed)
        assertTrue(opener.open(5, header, sealed).contentEquals(plain))
    }

    fun testNativeScreenFramesArriveOverIpv6AndAdvanceNonce() {
        val delivered = CountDownLatch(3)
        val stream = ScreenStream(key, frameDecryptor = native())
        var peer: Socket? = null
        val frames = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        try {
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray) { frames += naluBytes; delivered.countDown() }
            })
            peer = Socket(InetAddress.getByName("::1"), port)
            for (counter in 0L..2L) {
                val plain = byteArrayOf(0, 0, 0, 2, 0x65, counter.toByte())
                val aad = ByteArray(128).apply { this[0] = (plain.size + 16).toByte() }
                val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, aad)
                peer.outputStream.apply { write(aad); write(sealed); flush() }
            }
            assertTrue("Native video TCP delivery timed out", delivered.await(5, TimeUnit.SECONDS))
            assertEquals(3, frames.size)
            frames.forEachIndexed { index, frame ->
                assertTrue(byteArrayOf(0, 0, 0, 1, 0x65, index.toByte()).contentEquals(frame))
            }
        } finally { peer?.close(); stream.close() }
    }

    fun testNativeAndJavaVideoDecryptionBenchmark() {
        val opener = native()
        val plain = ByteArray(524288) { (it * 17 + 5).toByte() }
        val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(42), plain, header)
        repeat(2) { opener.open(42, header, sealed); ScreenCodec.decryptFrame(key, 42, header, sealed) }
        val nativeStart = System.nanoTime()
        repeat(10) { assertTrue(opener.open(42, header, sealed).contentEquals(plain)) }
        val nativeNs = System.nanoTime() - nativeStart
        val javaStart = System.nanoTime()
        repeat(10) { assertTrue(ScreenCodec.decryptFrame(key, 42, header, sealed).contentEquals(plain)) }
        val javaNs = System.nanoTime() - javaStart
        // Timing is diagnostic, not a flaky pass/fail requirement on the host VM.
        Log.i(TAG, "video AEAD benchmark bytes=${plain.size * 10} nativeMs=${nativeNs / 1_000_000} javaMs=${javaNs / 1_000_000}")
    }

    companion object { private const val TAG = "coorlay-VideoCrypto-Test" }
}
