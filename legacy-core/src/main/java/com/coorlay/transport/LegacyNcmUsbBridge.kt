package com.coorlay.transport

import android.hardware.usb.UsbDeviceConnection
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class NcmUsbBridge private constructor(private val pipe: LegacyUsbPipe,
    private val input: LegacyUsbEndpoint, private val output: LegacyUsbEndpoint,
    private val mac: ByteArray?, private val onDiagnostic: (String) -> Unit,
    private val status: LegacyUsbEndpoint?) : Closeable {
    val hostMac: ByteArray? get() = mac?.copyOf()
    private val readLock = Any()
    private val writeLock = Any()
    private var sequence = 0
    private val rx = NcmRxBuffer(input.maxPacketSize, RX_CAPACITY, RX_READ_BYTES)
    private val frames = ArrayDeque<ByteArray>()
    private val closed = AtomicBoolean(false)
    private val sentBlocks = AtomicLong()
    private val receivedBlocks = AtomicLong()
    private val writeTimeouts = AtomicLong()
    private val readTimeouts = AtomicLong()
    private val startup = LegacyNcmStartupPolicy()
    private var writeNotReady = false
    private val statusThread = Thread(::drainStatus, "legacy-ncm-status").apply {
        isDaemon = true
        start()
    }
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        startup.attempted()
        val block = Ntb16Codec.build(frame, sequence)
        sequence = (sequence + 1) and 65535
        val started = System.nanoTime()
        val count = pipe.transfer(output.address, block, timeoutMillis)
        val tookMs = (System.nanoTime() - started) / 1_000_000L
        // Video freezes of ~2 s while swiping (2026-10-08): TCP ACKs and touches leave through this one
        // synchronous write, so a slow bulk-out would stall them all. Log every slow write to confirm or rule out.
        if (tookMs >= SLOW_WRITE_MS) onDiagnostic("NCM bulk-out slow ms=$tookMs bytes=${block.size} result=$count")
        if (count <= 0) {
            writeTimeouts.incrementAndGet()
            if (!writeNotReady) {
                writeNotReady = true
                onDiagnostic("NCM bulk-out not ready endpoint=0x${output.address.toString(16)} result=$count; waiting for phone")
            }
            return@synchronized
        }
        if (count != block.size) throw IphoneUsbException.DeviceUnavailable("Partial NCM bulk write: $count/${block.size}")
        startup.progressed()
        if (sentBlocks.incrementAndGet() == 1L) onDiagnostic("NCM first bulk-out bytes=$count")
        if (writeNotReady) {
            writeNotReady = false
            onDiagnostic("NCM bulk-out became ready bytes=$count")
        }
    }
    fun recv(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (true) {
            if (startup.timedOut()) throw IphoneUsbException.DeviceUnavailable(LegacyNcmStartupPolicy.MESSAGE)
            if (frames.isNotEmpty()) return@synchronized frames.removeFirst()
            if (rx.parse(frames)) continue
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining <= 0) return@synchronized null
            val offset = rx.readOffset()
            val count = pipe.transfer(input.address, rx.bytes, offset, RX_READ_BYTES, remaining.coerceAtMost(1000).toInt().coerceAtLeast(1))
            if (count > 0) {
                startup.progressed()
                if (receivedBlocks.incrementAndGet() == 1L) onDiagnostic("NCM first bulk-in bytes=$count")
                rx.received(count)
            } else readTimeouts.incrementAndGet()
        }
        @Suppress("UNREACHABLE_CODE") null
    }
    private fun drainStatus() {
        val buffer = ByteArray((status?.maxPacketSize ?: 64).coerceAtLeast(64))
        var statusEnabled = status != null
        var lastSummary = System.nanoTime()
        var notifications = 0L
        while (!closed.get()) {
            if (statusEnabled) {
                try {
                    val count = pipe.transfer(status!!.address, buffer, 100)
                    if (count > 0) {
                        notifications++
                        val header = buffer.take(count.coerceAtMost(32)).joinToString(" ") { "%02x".format(it.toInt() and 255) }
                        onDiagnostic("NCM status endpoint=0x${status.address.toString(16)} bytes=$count header=$header")
                    }
                } catch (error: Exception) {
                    if (!closed.get()) onDiagnostic("NCM status polling stopped: ${error.message}")
                    statusEnabled = false
                }
            }
            if (System.nanoTime() - lastSummary >= 15_000_000_000L) {
                onDiagnostic("NCM counters txBlocks=${sentBlocks.get()} rxBlocks=${receivedBlocks.get()} " +
                    "txTimeouts=${writeTimeouts.get()} rxTimeouts=${readTimeouts.get()} notifications=$notifications")
                lastSummary = System.nanoTime()
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { return }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        statusThread.interrupt()
        pipe.close()
        if (Thread.currentThread() !== statusThread) {
            try { statusThread.join(350) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        onDiagnostic("NCM closed txBlocks=${sentBlocks.get()} rxBlocks=${receivedBlocks.get()}")
    }
    companion object {
        /** Unparsed bytes allowed in flight (several 32 KiB NTBs). */
        private const val RX_CAPACITY = 131072
        /** One USBfs bulk-in request; old kernels cap synchronous bulk at 16 KiB. */
        private const val RX_READ_BYTES = 16384
        private const val SLOW_WRITE_MS = 150L
        fun open(connection: UsbDeviceConnection, function: NcmFunctionDiscovery.NcmFunction,
                 onDiagnostic: (String) -> Unit = {}): NcmUsbBridge {
            val pipe = LegacyUsbPipe(connection, listOf(function.control.id, function.data.id))
            try {
                val alt = LegacyUsbNative.alternate(connection.fileDescriptor, function.data.id, function.data.alternateSetting)
                if (alt < 0) throw IphoneUsbException.DeviceUnavailable("Could not select NCM alternate setting: errno=${-alt}")
                onDiagnostic("NCM selected alternate interface=${function.data.id}/${function.data.alternateSetting}; " +
                    "status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}")
                LegacyNcmControlSetup.configure(function.control.id, function.control.ncmCapabilities,
                    { type, request, value, iface, data, length ->
                        connection.controlTransfer(type, request, value, iface, data, length, 1000)
                    }, onDiagnostic)
                val index = function.control.macStringIndex
                val mac = if (index != 0) {
                    val data = ByteArray(256)
                    val count = connection.controlTransfer(0x80, 6, 0x300 or index, 0x409, data, data.size, 1000)
                    if (count >= 4) {
                        val text = data.copyOfRange(2, minOf(count, data[0].toInt() and 255)).toString(Charsets.UTF_16LE)
                        if (text.matches(Regex("[0-9A-Fa-f]{12}"))) ByteArray(6) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() } else null
                    } else null
                } else null
                return NcmUsbBridge(pipe, function.bulkIn, function.bulkOut, mac, onDiagnostic, function.statusIn)
            } catch (error: Throwable) { pipe.close(); throw error }
        }
    }
}

/**
 * The NCM bulk-in receive buffer: transfers land at the end, NTB16 blocks are parsed in place from the
 * start. One buffer for the whole session: the old per-read array + concat + copyOfRange produced
 * ~10 MB/s of garbage on the head unit and a 30-40 ms GC_FOR_ALLOC pause several times a second.
 */
internal class NcmRxBuffer(private val maxPacketSize: Int, private val capacity: Int, private val readBytes: Int) {
    val bytes = ByteArray(capacity + readBytes)
    private var start = 0
    private var end = 0
    // After an NTB of a whole number of USB packets the iPhone may end the transfer with a zero-length
    // packet or with one zero pad byte (DiPlay 0.2.16, 515bc9e). The pad, if any, comes before the next
    // NTB, possibly in a later bulk-in completion.
    private var optionalPad = false

    /** Parses one complete NTB (or a pad byte) into [frames]; false when more bytes are needed. */
    fun parse(frames: ArrayDeque<ByteArray>): Boolean {
        if (optionalPad && end > start) {
            optionalPad = false
            when (bytes[start].toInt() and 255) {
                0 -> { consume(1); return true }
                0x4e -> Unit // A zero-length packet ended the transfer; this is the next NTB16 header.
                else -> throw IphoneUsbException.Protocol("Invalid NCM short-packet pad")
            }
        }
        if (end - start < 12) return false
        fun u(index: Int) = bytes[start + index].toInt() and 255
        if (u(0) != 0x4e || u(1) != 0x43 || u(2) != 0x4d || u(3) != 0x48) {
            throw IphoneUsbException.Protocol("Invalid NCM NTB16 signature")
        }
        val length = u(8) or (u(9) shl 8)
        if (length < 28) throw IphoneUsbException.Protocol("Invalid NTB16 block length")
        // Parse as soon as the NTB itself is complete: waiting for the optional pad byte held a
        // packet-aligned NTB until the next transfer, and a zero-length packet then failed the session.
        if (end - start < length) return false
        for (frame in Ntb16Codec.parse(bytes, start, length)) {
            if (frames.size >= MAX_FRAMES) throw IphoneUsbException.Protocol("NCM frame queue overflow")
            frames.addLast(frame)
        }
        consume(length)
        optionalPad = length % maxPacketSize == 0
        return true
    }

    /** Where the next transfer of up to readBytes lands; keeps the partial NTB, moved to the front. */
    fun readOffset(): Int {
        if (bytes.size - end < readBytes) {
            System.arraycopy(bytes, start, bytes, 0, end - start)
            end -= start; start = 0
        }
        return end
    }

    fun received(count: Int) {
        if (end - start + count > capacity) throw IphoneUsbException.Protocol("NCM receive buffer overflow")
        end += count
    }

    private fun consume(count: Int) {
        start += count
        if (start == end) { start = 0; end = 0 }
    }

    private companion object {
        const val MAX_FRAMES = 256
    }
}
