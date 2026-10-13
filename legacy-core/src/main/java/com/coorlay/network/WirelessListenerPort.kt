package com.coorlay.network

import java.io.File
import java.net.BindException
import java.net.ServerSocket

/**
 * Port choice for the wireless AirPlay listener.
 *
 * The phone learns the port from Bonjour and the iAP2 wireless endpoint, both sent after the
 * listener is bound, so a busy 7000 does not have to fail the session: a stale socket of our own
 * gets a short retry, anything else falls back to a nearby or ephemeral port.
 */
internal object WirelessListenerPort {
    private const val RETRIES = 3
    private const val RETRY_DELAY_MS = 500L
    private val FALLBACK_PORTS = 7001..7010

    fun bind(preferred: Int, report: (String) -> Unit, attempt: (port: Int) -> ServerSocket): ServerSocket {
        repeat(RETRIES) { index ->
            try {
                return attempt(preferred)
            } catch (error: BindException) {
                report("AirPlay port $preferred busy (try ${index + 1}/$RETRIES): ${error.message}")
                if (index + 1 < RETRIES) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        listeners(preferred).forEach { report("AirPlay port $preferred is held by $it") }
        for (port in FALLBACK_PORTS + 0) {
            try {
                return attempt(port).also { report("AirPlay fallback port=${it.localPort}") }
            } catch (_: BindException) {
                // Try the next candidate.
            }
        }
        throw BindException("No free AirPlay port")
    }

    /** Listening sockets on [port] from /proc/net/tcp and tcp6, as "address uid=N". */
    fun listeners(port: Int): List<String> = listOf("/proc/net/tcp", "/proc/net/tcp6").flatMap { path ->
        try { parseListeners(File(path).readLines(), port) } catch (_: Exception) { emptyList() }
    }

    /** UDP sockets bound to [port] from /proc/net/udp and udp6, as "address uid=N". */
    fun udpHolders(port: Int): List<String> = listOf("/proc/net/udp", "/proc/net/udp6").flatMap { path ->
        try { parseListeners(File(path).readLines(), port, state = null) } catch (_: Exception) { emptyList() }
    }

    /** [state] "0A" keeps TCP LISTEN sockets; null keeps every socket (UDP has no listen state). */
    internal fun parseListeners(lines: List<String>, port: Int, state: String? = "0A"): List<String> {
        val suffix = ":%04X".format(port)
        return lines.drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            // sl local_address rem_address st tx:rx tr:when retrnsmt uid ...
            if (fields.size < 8 || !fields[1].endsWith(suffix) || (state != null && fields[3] != state)) null
            else "${fields[1]} uid=${fields[7]}"
        }
    }
}
