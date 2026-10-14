package com.coorlay.coolray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WlanStatsTest {
    private fun dev(rx: Long, drop: Long) = """
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:    1072      16    0    0    0     0          0         0     1072      16    0    0    0     0       0          0
 wlan0: ${rx * 1024} $rx 0 $drop 0 0 0 0 7056 45 0 1 0 0 0 0
""".trimIndent()

    private fun snmp(retrans: Long) = "Tcp: RtoAlgorithm RetransSegs InErrs\nTcp: 1 $retrans 0\n"
    private fun netstat(ofo: Long) = "TcpExt: SyncookiesSent TCPOFOQueue\nTcpExt: 0 $ofo\n"

    @Test fun reportsDeltasFromTheSecondSample() {
        var files = mapOf("/proc/net/dev" to dev(100, 0), "/proc/net/snmp" to snmp(5), "/proc/net/netstat" to netstat(1))
        val stats = WlanStats { files[it] }
        assertNull(stats.sample())
        files = mapOf("/proc/net/dev" to dev(350, 3), "/proc/net/snmp" to snmp(7), "/proc/net/netstat" to netstat(9))
        assertEquals("WLAN wlan0 rx=250pk/250KB err=0 drop=3 tx=0pk err=0 drop=0 InErrs=0 RetransSegs=2 TCPOFOQueue=8",
            stats.sample())
    }

    @Test fun wifiDirectTrafficOnP2pIsNotSilence() {
        fun devP2p(p2p: Long) = """
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
 wlan0: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
p2p-p2p0-0: ${p2p * 1024} $p2p 0 0 0 0 0 0 0 0 0 0 0 0 0 0
""".trimIndent()
        var files = mapOf("/proc/net/dev" to devP2p(10))
        val stats = WlanStats { files[it] }
        stats.sample()
        files = mapOf("/proc/net/dev" to devP2p(510))
        assertEquals("WLAN wlan0+p2p-p2p0-0 rx=500pk/500KB err=0 drop=0 tx=0pk err=0 drop=0", stats.sample())
        assertEquals(0, stats.silentSamples)
        stats.sample()
        assertEquals(1, stats.silentSamples)
    }

    @Test fun wifiDirectSessionCountsOnlyTheGroup() {
        fun devAll(other: Long, group: Long) = """
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
 wlan0: ${other * 1024} $other 0 0 0 0 0 0 0 0 0 0 0 0 0 0
  p2p0: ${other * 1024} $other 0 0 0 0 0 0 0 0 0 0 0 0 0 0
p2p-p2p0-0: ${group * 1024} $group 0 0 0 0 0 0 0 0 0 0 0 0 0 0
""".trimIndent()
        var files = mapOf("/proc/net/dev" to devAll(0, 100))
        val stats = WlanStats(groupOnly = true) { files[it] }
        stats.sample()
        files = mapOf("/proc/net/dev" to devAll(20, 100))
        assertEquals("WLAN p2p-p2p0-0 rx=0pk/0KB err=0 drop=0 tx=0pk err=0 drop=0", stats.sample())
        assertEquals(1, stats.silentSamples)
    }

    @Test fun kernelUdpDropsAreReported() {
        fun udp(inErrors: Long, rcvbuf: Long) =
            "Udp: InDatagrams NoPorts InErrors OutDatagrams RcvbufErrors SndbufErrors\nUdp: 900 0 $inErrors 10 $rcvbuf 0\n"
        var files = mapOf("/proc/net/dev" to dev(1, 0), "/proc/net/snmp" to udp(3, 1))
        val stats = WlanStats { files[it] }
        stats.sample()
        files = mapOf("/proc/net/dev" to dev(2, 0), "/proc/net/snmp" to udp(10, 5))
        assertEquals("WLAN wlan0 rx=1pk/1KB err=0 drop=0 tx=0pk err=0 drop=0 UdpInErrors=7 UdpRcvbufErrors=4", stats.sample())
    }

    @Test fun unreadableProcGivesNothing() {
        assertNull(WlanStats { null }.sample())
    }
}
