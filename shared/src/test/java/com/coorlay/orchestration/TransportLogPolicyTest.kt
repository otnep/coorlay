package com.coorlay.orchestration

import org.junit.Assert.*
import org.junit.Test

class TransportLogPolicyTest {
    @Test fun quietModeKeepsFailuresAndConnectionMilestones() {
        listOf("wired iap2 authentication accepted", "wired iPhone USB permission requested",
            "IAP2 RX [wired] FAILED: disconnected", "Receive: error reading video", "NCM counters timeout=1",
            "H.264 decoder error: failed", "video stats rx=30.0fps shown=29.8fps",
            "Receive: video packets=30 readMaxMs=1700", "video stats rx=0.0fps sinceLastFrame=3000ms").forEach { assertTrue(it, TransportLogPolicy.keep(it, false)) }
    }
    @Test fun packetDumpsAndPeriodicCountersCanBeEnabled() {
        listOf("TRACE headers=...", "IAP2 TX [wired] data", "IAP2 RX [wired] data", "IAP2 FLOW frames=1",
            "NCM counters txBlocks=1 txTimeouts=0 rxTimeouts=0",
            "Video processing frames=30", "airplay tx status=200", "wired iap2 rx=0x5001", "iAP tunnel iap2 rx=0x5001",
            "ncm inbound bytes=86 src=fe80::1", "AirPlay command type=modesChanged params=appStates").forEach {
            assertFalse(it, TransportLogPolicy.keep(it, false)); assertTrue(TransportLogPolicy.keep(it, true))
        }
    }
}
