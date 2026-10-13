package com.coorlay.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.BindException
import java.net.ServerSocket

class WirelessListenerPortTest {
    @Test fun findsOnlyListenersOnThePort() {
        val lines = listOf(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
            "   0: 00000000:1B58 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 123",
            "   1: 012BA8C0:1B58 022BA8C0:C350 01 00000000:00000000 00:00000000 00000000 10051        0 124",
            "   2: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1013        0 125",
        )
        assertEquals(listOf("00000000:1B58 uid=1000"), WirelessListenerPort.parseListeners(lines, 7000))
    }

    @Test fun udpHoldersIgnoreSocketState() {
        val lines = listOf(
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops",
            "  12: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000  1013        0 300 2 c0000000 0",
        )
        assertEquals(listOf("00000000:14E9 uid=1013"), WirelessListenerPort.parseListeners(lines, 5353, state = null))
    }

    @Test fun fallsBackWhenPreferredPortStaysBusy() {
        val tried = mutableListOf<Int>()
        val server = WirelessListenerPort.bind(7000, {}) { port ->
            tried += port
            if (port == 7000 || port == 7001) throw BindException("bind failed: EADDRINUSE")
            ServerSocket(0)
        }
        server.close()
        assertEquals(listOf(7000, 7000, 7000, 7001, 7002), tried)
    }
}
