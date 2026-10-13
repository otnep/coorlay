package com.coorlay.coolray

import com.coorlay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.min

/** ANW callback delivery cannot block its radio thread. Only this socket's bytes enter the queue. */
internal class AnwSocketStream(
    val index: Int,
    private val write: (ByteArray, Long) -> Int,
    private val connected: () -> Boolean,
    private val disconnect: () -> Unit,
) : BlockingDuplexByteStream {
    private val lock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var bytes = 0
    private var closed = false
    private var failure: IOException? = null

    fun receive(socket: Int, data: ByteArray?, length: Int) {
        if (socket != index) return
        synchronized(lock) {
            if (closed || failure != null) return
            if (data == null || length !in 0..data.size) {
                failure = IOException("ANW SocketDataIND invalid length")
            } else if (length > 0) {
                if (bytes + length > MAX_BYTES) failure = IOException("ANW receive queue overflow")
                else { pending.addLast(data.copyOf(length)); bytes += length }
            }
            lock.notifyAll()
        }
    }

    override fun send(data: ByteArray) = sendWithin(data, 5000L)

    internal fun sendWithin(bytes: ByteArray, timeoutMillis: Long) {
        require(timeoutMillis >= 0)
        val deadline = System.nanoTime() + min(timeoutMillis, 60_000L) * 1_000_000L
        var offset = 0
        while (offset < bytes.size) {
            synchronized(lock) { failure?.let { throw it }; if (closed) throw IOException("ANW stream closed") }
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) throw IOException("ANW write timed out")
            val chunk = bytes.copyOfRange(offset, min(bytes.size, offset + 16_384))
            val written = write(chunk, remaining)
            if (written !in 1..chunk.size) throw IOException("ANW invalid write count $written/${chunk.size}")
            offset += written
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0 && timeoutMillis >= 0)
        val deadline = System.nanoTime() + min(timeoutMillis, 60_000L) * 1_000_000L
        while (true) {
            var needsStatus = false
            synchronized(lock) {
                failure?.let { throw it }
                val chunk = pending.pollFirst()
                if (chunk != null) {
                    bytes -= chunk.size
                    if (chunk.size <= maxBytes) return chunk
                    val tail = chunk.copyOfRange(maxBytes, chunk.size)
                    pending.addFirst(tail); bytes += tail.size
                    return chunk.copyOf(maxBytes)
                }
                if (closed) return ByteArray(0)
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) return null
                try { lock.wait(min(remaining, 100L)) }
                catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("ANW receive cancelled", error) }
                if (closed) return ByteArray(0)
                needsStatus = pending.isEmpty()
            }
            if (needsStatus && !connected()) { close(); return ByteArray(0) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; pending.clear(); bytes = 0; lock.notifyAll()
        }
        disconnect()
    }

    private companion object { const val MAX_BYTES = 65_536 }
}
