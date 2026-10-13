package com.coorlay.orchestration

/** Quiet USB logging keeps connection milestones and every failure, without raw packet dumps. */
object TransportLogPolicy {
    private val failure = Regex("(?i)\\b(failed|errors?|denied|timeouts?)\\b")

    fun keep(message: String, detailed: Boolean): Boolean {
        if (detailed) return true
        // Prefixes first: the regex runs only for the noisy lines that a failure word could still keep.
        if (NOISE.none(message::startsWith)) return true
        return failure.containsMatchIn(message)
    }

    /**
     * Per-packet and per-message lines that are only useful while debugging the transport. "Receive:" (one line per
     * stream every 5 s: RTP gaps, read/processing time) stays: without it packet loss could not be told apart from
     * late processing in a log with the detailed journal off (2026-10-12).
     */
    private val NOISE = listOf("TRACE", "IAP2 RX", "IAP2 TX", "IAP2 FLOW", "NCM counters",
        // "video stats" (one line per 5 s) stays: it tells iPhone/USB gaps from decoder lag.
        "Video processing", "airplay tx status=200",
        // Now-playing/route traffic every second and raw NCM packet dumps.
        "wired iap2 rx=", "iAP tunnel iap2 rx=", "ncm inbound bytes=", "ncm outbound bytes=", "NCM network inbound mDNS",
        "USB descriptor ",
        // The controller logs each AirPlay command twice; modesChanged keeps its own detailed line.
        "airplay command type=", "AirPlay command type=")
}
