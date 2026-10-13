package com.coorlay.coolray

import android.test.AndroidTestCase
import com.coorlay.airplay.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Dalvik/libcore SETUP coverage; Robolectric uses the host JVM's socket implementation. */
class AirPlaySetupTest : AndroidTestCase() {
    fun testInitialSetupOpensTimingAndEventOnAndroid43() = checkSetup(false)
    fun testLowPowerSetupAlsoOpensKeepAliveOnAndroid43() = checkSetup(true)
    fun testInitialSetupOnScopedLinkLocalAddressOnAndroid43() = checkSetup(false, scopedLinkLocal())

    fun testIapTunnelAcceptsScopedLinkLocalConnectionOnAndroid43() {
        val address = scopedLinkLocal()
        val tunnel = IapTunnel(ByteArray(32), address)
        var peer: Socket? = null
        try {
            val port = tunnel.listen(object : IapTunnel.Listener {})
            peer = Socket(address, port)
            assertTrue("Scoped IPv6 data tunnel was not accepted", tunnel.awaitPeerConnection(3000))
        } finally { peer?.close(); tunnel.close() }
    }

    private fun scopedLinkLocal(): Inet6Address {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet6Address && address.isLinkLocalAddress)
                    return Inet6Address.getByAddress(null, address.address, iface)
            }
        }
        throw AssertionError("API 18 test VM has no IPv6 link-local interface")
    }

    fun testTimingClockExchangesIpv6DatagramsOnAndroid43() {
        val clock = NtpClock()
        val peer = DatagramSocket(null)
        try {
            val port = clock.listen()
            peer.bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
            peer.soTimeout = 3000
            val request = ByteArray(32).apply {
                this[0] = 0x80.toByte(); this[1] = 210.toByte(); this[3] = 7
                for (index in 24..31) this[index] = index.toByte()
            }
            peer.send(DatagramPacket(request, request.size, InetAddress.getByName("::1"), port))
            val reply = DatagramPacket(ByteArray(32), 32)
            peer.receive(reply)
            assertEquals(211, reply.data[1].toInt() and 255)
            assertTrue(request.copyOfRange(24, 32).contentEquals(reply.data.copyOfRange(8, 16)))
        } finally { clock.close(); peer.close() }
    }

    fun testEncryptedVideoFrameArrivesOverIpv6OnAndroid43() {
        val key = ByteArray(32)
        val received = CountDownLatch(1)
        var frame: ByteArray? = null
        val stream = ScreenStream(key)
        var peer: Socket? = null
        try {
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onFrame(naluBytes: ByteArray) { frame = naluBytes; received.countDown() }
            })
            peer = Socket(InetAddress.getByName("::1"), port)
            val plain = byteArrayOf(0, 0, 0, 2, 0x65, 0x12)
            val header = ByteArray(128).apply { this[0] = (plain.size + 16).toByte() }
            val sealed = AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(0), plain, header)
            peer.getOutputStream().apply { write(header); write(sealed); flush() }
            assertTrue("IPv6 video did not reach ScreenStream", received.await(3, TimeUnit.SECONDS))
            assertTrue(byteArrayOf(0, 0, 0, 1, 0x65, 0x12).contentEquals(frame))
        } finally { peer?.close(); stream.close() }
    }

    fun testEncryptedAudioArrivesOverIpv6OnAndroid43() {
        val key = ByteArray(32)
        val received = CountDownLatch(1)
        var rtp: ByteArray? = null
        val stream = AudioStream(key, streamType = 100)
        val peer = DatagramSocket(null)
        try {
            val ports = stream.listen(object : AudioStream.Listener {
                override fun onRtp(value: ByteArray, sample: Int) { rtp = value; received.countDown() }
            })
            assertTrue(ports.second > 0)
            peer.bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
            val header = ByteArray(12).apply { this[0] = 0x80.toByte(); this[1] = 96; this[7] = 1 }
            val plain = byteArrayOf(1, 2, 3, 4)
            val sealed = AirPlayCrypto.chachaSeal(key, ByteArray(12), plain, header.copyOfRange(4, 12))
            val wire = header + sealed + ByteArray(8)
            peer.send(DatagramPacket(wire, wire.size, InetAddress.getByName("::1"), ports.first))
            assertTrue("IPv6 audio did not reach AudioStream", received.await(3, TimeUnit.SECONDS))
            assertTrue((header + plain).contentEquals(rtp))
        } finally { peer.close(); stream.close() }
    }

    private fun checkSetup(lowPower: Boolean, address: InetAddress = InetAddress.getByName("::1")) {
        val journal = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 1, address)
        val client = Socket(address, server.localPort)
        client.soTimeout = 5000
        val session = AirPlaySession(
            socket = server.accept(),
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "220.68",
                AirPlayDisplayConfig(1920, 640), hevc = false),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { journal.add(message) }
            }, media = object : AirPlayMediaHandler {},
        )
        try {
            session.start()
            val setup = linkedMapOf<String, Any?>("name" to "test phone", "model" to "iPhone",
                "timingPort" to 0, "features" to listOf("iAPChannel", "viewAreas"))
            if (lowPower) setup["keepAliveLowPower"] = true
            val body = BplistCodec.encode(setup)
            val output = client.getOutputStream()
            output.write("SETUP /test RTSP/1.0\r\nCSeq: 6\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body); output.flush()
            val input = client.getInputStream()
            fun line(): String {
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val next = input.read(); check(next >= 0) { "RTSP EOF" }
                    if (next == 10) break
                    if (next != 13) bytes.write(next)
                }
                return bytes.toString("US-ASCII")
            }
            val status = line()
            assertTrue("$status\n" + journal.joinToString("\n"), status.contains("200"))
            var contentLength = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true))
                    contentLength = header.substringAfter(':').trim().toInt()
            }
            val responseBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val size = input.read(responseBytes, read, contentLength - read)
                check(size > 0) { "RTSP body EOF" }; read += size
            }
            val response = BplistCodec.decode(responseBytes) as Map<*, *>
            assertTrue((response["timingPort"] as Number).toInt() > 0)
            assertTrue((response["eventPort"] as Number).toInt() > 0)
            if (lowPower) assertTrue((response["keepAlivePort"] as Number).toInt() > 0)
            assertTrue((response["enabledFeatures"] as List<*>).contains("iAPChannel"))
        } finally {
            session.close(); client.close(); server.close()
        }
    }
}
