package com.coorlay.coolray

import android.os.Debug
import android.test.AndroidTestCase
import android.util.Log
import com.coorlay.airplay.AirPlayCrypto
import com.coorlay.airplay.AudioStream
import com.coorlay.airplay.ScreenStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The video and audio receivers reuse their read buffers (0.0.47b-3): frames of changing size must still arrive
 * whole, and the log shows how many bytes each frame/packet allocates on API-18 Dalvik.
 */
class StreamReuseTest : AndroidTestCase() {
    private val key = ByteArray(32) { (it * 5 + 2).toByte() }

    private fun videoMessage(plain: ByteArray, counter: Long): ByteArray {
        val header = ByteArray(128).apply {
            val size = plain.size + 16
            this[0] = size.toByte(); this[1] = (size shr 8).toByte(); this[2] = (size shr 16).toByte(); this[3] = (size shr 24).toByte()
            this[8] = counter.toByte()
        }
        return header + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, header)
    }

    /** One NAL unit with a 4-byte length prefix, as the iPhone sends it. */
    private fun nal(size: Int, seed: Int) = ByteArray(size + 4).apply {
        this[0] = (size shr 24).toByte(); this[1] = (size shr 16).toByte(); this[2] = (size shr 8).toByte(); this[3] = size.toByte()
        for (i in 4 until this.size) this[i] = (i * 13 + seed).toByte()
    }

    fun testVideoFramesOfChangingSizeArriveWhole() {
        val sizes = listOf(30_000, 500, 400_000, 20, 60_000, 1)
        val delivered = CountDownLatch(sizes.size)
        val frames = CopyOnWriteArrayList<ByteArray>()
        val stream = ScreenStream(key, frameDecryptor = LegacyVideoCrypto.create(key))
        var peer: Socket? = null
        try {
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray) { frames += naluBytes; delivered.countDown() }
            })
            peer = Socket(InetAddress.getByName("::1"), port)
            sizes.forEachIndexed { index, size -> peer.outputStream.write(videoMessage(nal(size, index), index.toLong())) }
            peer.outputStream.flush()
            assertTrue("video delivery timed out", delivered.await(10, TimeUnit.SECONDS))
            sizes.forEachIndexed { index, size ->
                val expected = nal(size, index).apply { this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1 }
                assertTrue("frame $index size $size differs", expected.contentEquals(frames[index]))
            }
        } finally { peer?.close(); stream.close() }
    }

    fun testPooledVideoFramesOfChangingSizeArriveWholeAndBuffersAreReused() {
        val sizes = listOf(30_000, 500, 400_000, 20, 60_000, 1, 45_000, 45_000)
        val delivered = CountDownLatch(sizes.size)
        val frames = CopyOnWriteArrayList<ByteArray>()
        val buffers = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())
        val stream = ScreenStream(key, frameDecryptor = LegacyVideoCrypto.create(key))
        var peer: Socket? = null
        try {
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, length: Int, senderNanos: Long, arrivalNanos: Long,
                    recycle: (ByteArray) -> Unit) {
                    frames += naluBytes.copyOf(length); buffers += naluBytes; recycle(naluBytes); delivered.countDown()
                }
            })
            peer = Socket(InetAddress.getByName("::1"), port)
            sizes.forEachIndexed { index, size ->
                peer.outputStream.write(videoMessage(nal(size, index), index.toLong())); peer.outputStream.flush()
                Thread.sleep(30)
            }
            assertTrue("video delivery timed out", delivered.await(10, TimeUnit.SECONDS))
            sizes.forEachIndexed { index, size ->
                val expected = nal(size, index).apply { this[0] = 0; this[1] = 0; this[2] = 0; this[3] = 1 }
                assertTrue("pooled frame $index size $size differs", expected.contentEquals(frames[index]))
            }
            assertTrue("buffers were not reused: ${buffers.size}", buffers.size < sizes.size)
        } finally { peer?.close(); stream.close() }
    }

    fun testVideoAllocationPerFrame() {
        val frameCount = 150
        val messages = (0 until frameCount).map { videoMessage(nal(40_000, it), it.toLong()) }
        val delivered = CountDownLatch(frameCount)
        val stream = ScreenStream(key, frameDecryptor = LegacyVideoCrypto.create(key))
        var peer: Socket? = null
        try {
            // Like the decoder: the pooled buffer goes back once read.
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray, length: Int, senderNanos: Long, arrivalNanos: Long,
                    recycle: (ByteArray) -> Unit) { recycle(naluBytes); delivered.countDown() }
            })
            peer = Socket(InetAddress.getByName("::1"), port)
            val output = peer.outputStream
            output.write(messages[0]); output.flush()
            Thread.sleep(300)
            Debug.resetGlobalAllocSize(); Debug.resetGlobalAllocCount(); Debug.startAllocCounting()
            for (index in 1 until frameCount) output.write(messages[index])
            output.flush()
            assertTrue("video delivery timed out", delivered.await(20, TimeUnit.SECONDS))
            Debug.stopAllocCounting()
            val frames = frameCount - 1
            Log.i(TAG, "video alloc per 40 KB frame: bytes=${Debug.getGlobalAllocSize() / frames} objects=${Debug.getGlobalAllocCount() / frames}")
        } finally { peer?.close(); stream.close() }
    }

    private fun audioDatagram(payload: ByteArray, sequence: Int): ByteArray {
        val header = ByteArray(12).apply { this[0] = 0x80.toByte(); this[2] = (sequence shr 8).toByte(); this[3] = sequence.toByte(); this[7] = sequence.toByte() }
        val nonce = AirPlayCrypto.nonce64(sequence.toLong())
        return header + AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12)) + nonce.copyOfRange(4, 12)
    }

    fun testAudioPacketsArriveWholeAndAllocateLittle() {
        val count = 400
        val payloads = (0 until count).map { index -> ByteArray(if (index % 3 == 0) 1200 else 90 + index % 50) { (it + index).toByte() } }
        val datagrams = payloads.mapIndexed { index, payload -> audioDatagram(payload, index) }
        val received = CopyOnWriteArrayList<ByteArray>()
        val done = CountDownLatch(count)
        val stream = AudioStream(key, 100)
        val sender = DatagramSocket()
        try {
            val (port, _) = stream.listen(object : AudioStream.Listener {
                override fun onRtp(rtp: ByteArray, sample: Int) { received += rtp; done.countDown() }
            })
            val target = InetAddress.getByName("127.0.0.1")
            val packet = DatagramPacket(ByteArray(0), 0, target, port)
            fun send(index: Int) { packet.setData(datagrams[index]); sender.send(packet); Thread.sleep(2) }
            send(0)
            Thread.sleep(200)
            Debug.resetGlobalAllocSize(); Debug.resetGlobalAllocCount(); Debug.startAllocCounting()
            for (index in 1 until count) send(index)
            assertTrue("audio delivery timed out: ${received.size}", done.await(20, TimeUnit.SECONDS))
            Debug.stopAllocCounting()
            Log.i(TAG, "audio alloc per packet: bytes=${Debug.getGlobalAllocSize() / (count - 1)} objects=${Debug.getGlobalAllocCount() / (count - 1)}")
            received.forEachIndexed { index, rtp ->
                assertTrue("packet $index header", datagrams[index].copyOf(12).contentEquals(rtp.copyOf(12)))
                assertTrue("packet $index payload", payloads[index].contentEquals(rtp.copyOfRange(12, rtp.size)))
            }
        } finally { sender.close(); stream.close() }
    }

    /** The old per-packet path (copies + a new cipher) against the reused opener, same packets, this thread only. */
    fun testAudioDecryptAllocationOldAgainstReused() {
        val datagrams = (0 until 300).map { audioDatagram(ByteArray(480) { b -> (b + it).toByte() }, it) }
        fun measure(block: (ByteArray) -> Unit): Pair<Long, Long> {
            datagrams.take(20).forEach(block)
            Debug.resetThreadAllocSize(); Debug.resetThreadAllocCount(); Debug.startAllocCounting()
            datagrams.forEach(block)
            Debug.stopAllocCounting()
            return Debug.getThreadAllocSize().toLong() / datagrams.size to Debug.getThreadAllocCount().toLong() / datagrams.size
        }
        val old = measure { wire ->
            val sealedEnd = wire.size - 8
            val nonce = ByteArray(12).also { wire.copyInto(it, 4, sealedEnd, wire.size) }
            val payload = AirPlayCrypto.chachaOpen(key, nonce, wire.copyOfRange(12, sealedEnd), wire.copyOfRange(4, 12))
            wire.copyOf(12 + payload.size).also { payload.copyInto(it, 12) }
        }
        val opener = AirPlayCrypto.Opener(key)
        val reused = measure { wire ->
            val sealedEnd = wire.size - 8
            val rtp = ByteArray(sealedEnd - 16)
            opener.open(wire, 4, 8, 12, sealedEnd - 12, sealedEnd, rtp, 12)
        }
        Log.i(TAG, "audio decrypt alloc per 480-byte packet: old bytes=${old.first} objects=${old.second}; " +
            "reused bytes=${reused.first} objects=${reused.second}")
        assertTrue("reused path allocates more: $reused vs $old", reused.first < old.first)
    }

    companion object { private const val TAG = "coorlay-StreamReuse" }
}
