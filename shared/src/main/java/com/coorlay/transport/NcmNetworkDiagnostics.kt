package com.coorlay.transport

import java.net.InetAddress

/** Bounded bring-up metadata, separate from the generic packet/FPS trace budget.
 * Never records application payloads, DNS names, keys or certificates. Each instance belongs
 * to one bridge thread. Inspection ends on its first TCP packet or after 90 seconds.
 */
class NcmNetworkDiagnostics(
    private val direction: String,
    private val emit: (String) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    init { require(direction == "inbound" || direction == "outbound") }
    private val started = nowMillis()
    private var nextReport = started + REPORT_MILLIS
    private var finished = false
    private var ipv6 = 0L
    private var udp = 0L
    private var ns = 0L
    private var na = 0L
    private var errors = 0L
    private var other = 0L
    private var droppedEthernet = 0L
    private var malformed = 0L
    private val samples = mutableMapOf<String, Int>()

    fun droppedEthernet(frame: ByteArray) {
        if (!active()) return
        droppedEthernet++
        sample("ethernet", 4) {
            "NCM network $direction dropped Ethernet bytes=${frame.size} type=" +
                if (frame.size >= 14) "0x${u16(frame, 12).toString(16)}" else "truncated"
        }
    }

    fun ipv6(packet: ByteArray, offset: Int = 0, length: Int = packet.size - offset,
        ownNeighborReply: Boolean = false) {
        if (!active()) return
        val view = inspect(packet, offset, length)
        if (view == null) {
            malformed++
            sample("malformed", 4) { "NCM network $direction malformed IPv6 bytes=$length" }
            return
        }
        ipv6++
        val available = view.end - view.transport
        when (view.protocol) {
            6 -> if (available >= 20) {
                emit("NCM first $direction TCP ${view.summary()} tcp=${u16(packet, view.transport)}->${u16(packet, view.transport + 2)} flags=0x${(packet[view.transport + 13].toInt() and 255).toString(16)}")
                finish("first-tcp")
            } else invalidTransport()
            17 -> if (available >= 8 && u16(packet, view.transport + 4) in 8..available) {
                udp++
                val sourcePort = u16(packet, view.transport)
                val destinationPort = u16(packet, view.transport + 2)
                if ((sourcePort == 5353 || destinationPort == 5353) && u16(packet, view.transport + 4) >= 20) {
                    val dns = view.transport + 8
                    sample("mdns", 4) {
                        "NCM network $direction mDNS ${view.summary()} udp=$sourcePort->$destinationPort" +
                            " response=${packet[dns + 2].toInt() and 128 != 0}" +
                            " questions=${u16(packet, dns + 4)} answers=${u16(packet, dns + 6)}"
                    }
                }
            } else invalidTransport()
            58 -> if (available >= 4) {
                val type = packet[view.transport].toInt() and 255
                val code = packet[view.transport + 1].toInt() and 255
                when {
                    type == 135 || type == 136 -> {
                        if (type == 135) ns++ else na++
                        sample("nd", 12) {
                            val target = if (available >= 24) address(packet, view.transport + 8) else "truncated"
                            "NCM network $direction ND ${view.summary()} icmp6=$type code=$code target=$target" +
                                if (type == 135) " ownReply=${if (ownNeighborReply) "generated" else "none"}" else ""
                        }
                    }
                    type in 1..4 -> {
                        errors++
                        sample("icmp-error", 4) { "NCM network $direction ICMP error ${view.summary()} icmp6=$type code=$code" }
                    }
                    else -> other++
                }
            } else invalidTransport()
            else -> {
                other++
                sample("other", 4) { "NCM network $direction other ${view.summary()}" }
            }
        }
    }

    /** Called on receive timeout too, so a silent phone still leaves a progress report. */
    fun tick() { active() }

    fun finish(reason: String = "closed") {
        if (finished) return
        report(reason)
        finished = true
    }

    private fun active(): Boolean {
        if (finished) return false
        val now = nowMillis()
        if (now - started >= WINDOW_MILLIS) { finish("window-ended"); return false }
        if (now >= nextReport) { report("waiting-tcp"); nextReport = now + REPORT_MILLIS }
        return true
    }

    private fun report(reason: String) = emit(
        "NCM network $direction summary reason=$reason elapsedMs=${(nowMillis() - started).coerceAtLeast(0)}" +
            " ipv6=$ipv6 udp=$udp ns=$ns na=$na icmpErrors=$errors other=$other" +
            " droppedEthernet=$droppedEthernet malformed=$malformed",
    )
    private fun invalidTransport() { malformed++ }
    private inline fun sample(category: String, limit: Int, message: () -> String) {
        val count = samples[category] ?: 0
        if (count >= limit) return
        samples[category] = count + 1
        emit(message())
    }

    private data class View(val packet: ByteArray, val offset: Int, val end: Int,
        val transport: Int, val protocol: Int, val extensions: List<Int>) {
        fun summary(): String = "bytes=${end - offset} src=${address(packet, offset + 8)}" +
            " dst=${address(packet, offset + 24)} hop=${packet[offset + 7].toInt() and 255}" +
            " next=$protocol extensions=${if (extensions.isEmpty()) "none" else extensions.joinToString(",")}" 
    }

    private companion object {
        const val REPORT_MILLIS = 5_000L
        const val WINDOW_MILLIS = 90_000L
        fun address(packet: ByteArray, offset: Int): String =
            InetAddress.getByAddress(packet.copyOfRange(offset, offset + 16)).hostAddress ?: "unknown"
        fun u16(packet: ByteArray, offset: Int): Int =
            ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255)

        // Parse only metadata; extension/fragment support here does not change packet forwarding
        // or the stricter own-address NDP responder's validation.
        fun inspect(packet: ByteArray, offset: Int, length: Int): View? {
            if (offset < 0 || length < 40 || offset > packet.size - length ||
                packet[offset].toInt() and 0xf0 != 0x60) return null
            val payload = u16(packet, offset + 4)
            if (payload > length - 40) return null
            val end = offset + 40 + payload
            var at = offset + 40
            var next = packet[offset + 6].toInt() and 255
            val extensions = mutableListOf<Int>()
            while (next in listOf(0, 43, 44, 51, 60)) {
                if (extensions.size == 8 || end - at < 2) return null
                extensions.add(next)
                val bytes = when (next) {
                    44 -> 8
                    51 -> ((packet[at + 1].toInt() and 255) + 2) * 4
                    else -> ((packet[at + 1].toInt() and 255) + 1) * 8
                }
                if (bytes > end - at) return null
                // Non-initial fragments cannot contain a safely identifiable TCP/ICMP header.
                if (next == 44 && u16(packet, at + 2) and 0xfff8 != 0)
                    return View(packet, offset, end, at, 44, extensions)
                next = packet[at].toInt() and 255
                at += bytes
            }
            return View(packet, offset, end, at, next, extensions)
        }
    }
}
