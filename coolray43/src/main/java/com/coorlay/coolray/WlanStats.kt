package com.coorlay.coolray

import java.io.File

/**
 * Wireless CarPlay stalls (2026-10-08): the iPhone sends nothing for 0.5–2 s while UDP audio keeps flowing, which
 * looks like TCP loss recovery on the head-unit SoftAP. Every sample logs what changed since the previous one on
 * wlan0 and in the kernel TCP counters, so packet loss can be told apart from an iPhone that simply sends less.
 */
internal class WlanStats(
    private val read: (String) -> String? = { path -> try { File(path).readText() } catch (_: Exception) { null } },
) {
    private var previous: Map<String, Long>? = null
    /** Interfaces counted in the last sample: wlan0 and the Wi-Fi Direct ones (p2p0, p2p-p2p0-N). */
    private var interfaces = listOf<String>()

    /**
     * Samples in a row in which the wireless interfaces received nothing. Wi-Fi Direct traffic runs on p2p-p2p0-N,
     * not wlan0: counting wlan0 alone ended the first working Wi-Fi Direct session as silent (2026-10-12). A running CarPlay iPhone sends video and a control
     * request every 2 s; with its Wi-Fi switched off the TCP sockets never close and the picture froze (2026-10-09).
     */
    var silentSamples = 0
        private set

    /** One journal line with the deltas since the last call; null on the first call or when nothing is readable. */
    fun sample(): String? {
        val current = counters()
        if (current.isEmpty()) return null
        val before = previous
        previous = current
        if (before == null) return null
        fun d(name: String) = current[name]?.let { now -> before[name]?.let { now - it } }
        silentSamples = if (d("rxPackets") == 0L) silentSamples + 1 else 0
        val parts = mutableListOf<String>()
        d("rxPackets")?.let { parts += "rx=${it}pk/${(d("rxBytes") ?: 0) / 1024}KB err=${d("rxErrs")} drop=${d("rxDrop")}" }
        d("txPackets")?.let { parts += "tx=${it}pk err=${d("txErrs")} drop=${d("txDrop")}" }
        for (name in TCP_NAMES) d(name)?.let { parts += "$name=$it" }
        // Kernel UDP drops (audio arrives over UDP): a full socket buffer here is ours, not the radio's.
        for (name in UDP_NAMES) d(UDP_PREFIX + name)?.let { parts += "Udp$name=$it" }
        return "WLAN ${interfaces.joinToString("+")} " + parts.joinToString(" ")
    }

    internal fun counters(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        val seen = mutableListOf<String>()
        read("/proc/net/dev")?.lineSequence()?.forEach { line ->
            val name = line.substringBefore(':', "").trim()
            if (name != "wlan0" && !name.startsWith("p2p")) return@forEach
            val v = line.substringAfter(':').trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            if (v.size < 12) return@forEach
            seen += name
            for ((key, index) in DEV_FIELDS) result[key] = (result[key] ?: 0L) + v[index]
        }
        interfaces = seen
        for (path in listOf("/proc/net/snmp", "/proc/net/netstat")) {
            val lines = read(path)?.lines() ?: continue
            for (i in 0 until lines.size - 1) {
                val names = lines[i].split(Regex("\\s+"))
                val values = lines[i + 1].split(Regex("\\s+"))
                val section = names.firstOrNull()
                if (section != values.firstOrNull() || section !in setOf("Tcp:", "TcpExt:", "Udp:")) continue
                for (j in 1 until minOf(names.size, values.size)) {
                    if (section == "Udp:") {
                        if (names[j] in UDP_NAMES) values[j].toLongOrNull()?.let { result[UDP_PREFIX + names[j]] = it }
                    } else if (names[j] in TCP_NAMES) values[j].toLongOrNull()?.let { result[names[j]] = it }
                }
            }
        }
        return result
    }

    private companion object {
        val DEV_FIELDS = listOf("rxBytes" to 0, "rxPackets" to 1, "rxErrs" to 2, "rxDrop" to 3, "txPackets" to 9, "txErrs" to 10, "txDrop" to 11)
        /** Receiver-side loss signs (out-of-order, duplicate data, prune/collapse) and our own retransmits. */
        val TCP_NAMES = listOf("InErrs", "RetransSegs", "TCPOFOQueue", "TCPDSACKOldSent", "TCPDSACKOfoSent",
            "PruneCalled", "TCPRcvCollapsed", "TCPTimeouts")
        val UDP_NAMES = listOf("InErrors", "RcvbufErrors")
        const val UDP_PREFIX = "udp."
    }
}
