package com.coorlay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.coorlay.transport.EthernetIpv6Codec
import com.coorlay.transport.NcmUsbBridge
import com.coorlay.transport.NcmIpv6NeighborResponder
import com.coorlay.transport.NcmNetworkDiagnostics
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Own-address NDP is answered on the Ethernet side because
 * newer/vendor kernels can omit Ethernet neighbor discovery on a layer-3 TUN.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onDiagnostic: (String) -> Unit = {},
    hostAddress: ByteArray? = null,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 16
    private var outboundLogBudget = 24
    private val neighbors = hostAddress?.let { NcmIpv6NeighborResponder(it, hostMac) }
    private var neighborReplies = 0
    private var lastInboundNs = 0L
    private val inboundNetwork = NcmNetworkDiagnostics("inbound", ::diagnostic)
    private val outboundNetwork = NcmNetworkDiagnostics("outbound", ::diagnostic)
    private val running = AtomicBoolean(false)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY // Dalvik nice -8: every CarPlay byte crosses this pump
            start()
        }
        tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY // Dalvik nice -8: every CarPlay byte crosses this pump
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        ncm.close()
        tun.close()
        join(ncmToTunThread)
        join(tunToNcmThread)
    }

    private fun runNcmToTun() {
        val output = FileOutputStream(tun.fileDescriptor)
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS)
                if (frame == null) { inboundNetwork.tick(); continue }
                // Audio alone arrives ~135 times/s during a session: a longer silence means the phone or USB
                // stopped delivering, not our decoder (swipe freezes, 2026-10-08).
                val arrived = System.nanoTime()
                if (lastInboundNs != 0L && arrived - lastInboundNs >= INBOUND_GAP_NANOS) {
                    diagnostic("NCM inbound gap ms=${(arrived - lastInboundNs) / 1_000_000L}")
                }
                lastInboundNs = arrived
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame)
                if (ipv6 == null) { inboundNetwork.droppedEthernet(frame); continue }
                peerMac = ipv6.sourceMac
                val reply = neighbors?.reply(frame, ipv6.payloadOffset, ipv6.payloadLength)
                inboundNetwork.ipv6(frame, ipv6.payloadOffset, ipv6.payloadLength, reply != null)
                if (reply != null) {
                    val destination = EthernetIpv6Codec.multicastDestinationMac(reply) ?: ipv6.sourceMac
                    ncm.send(EthernetIpv6Codec.build(hostMac, destination, reply), WRITE_TIMEOUT_MILLIS)
                    if (++neighborReplies <= 3) diagnostic("NCM neighbor reply attempt=$neighborReplies ${reply.summary(0)}")
                    continue // One reply; do not ask the kernel to emit a duplicate NA.
                }
                if (!loggedInbound) {
                    loggedInbound = true
                    diagnostic(
                        "ncm first inbound ipv6 bytes=${ipv6.payloadLength} " +
                            "peer=${ipv6.sourceMac.macString()}",
                    )
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    diagnostic("ncm inbound ${frame.summary(ipv6.payloadOffset)}")
                }
                val writeStarted = System.nanoTime()
                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
                val writeMs = (System.nanoTime() - writeStarted) / 1_000_000L
                if (writeMs >= SLOW_TUN_WRITE_MS) diagnostic("tun write slow ms=$writeMs bytes=${ipv6.payloadLength}")
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        } finally {
            inboundNetwork.finish()
        }
    }

    private fun runTunToNcm() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val tunPacket = buffer.copyOf(length)
                outboundNetwork.ipv6(tunPacket)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size) {
                    diagnostic("ncm added target-link-layer option to neighbor advertisement")
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    diagnostic("ncm outbound ${ipv6.summary(0)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        diagnostic("ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    diagnostic(
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        } finally {
            outboundNetwork.finish()
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun diagnostic(message: String) {
        Log.i(TAG, message)
        onDiagnostic(message)
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String {
        val payloadBytes = size - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "coorlay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val INBOUND_GAP_NANOS = 500_000_000L
        const val SLOW_TUN_WRITE_MS = 100L
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
