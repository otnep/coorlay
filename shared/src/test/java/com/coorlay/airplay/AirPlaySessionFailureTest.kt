package com.coorlay.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AirPlaySessionFailureTest {
    @Test
    fun setupFailureReachesPersistentListenerBeforeErrorResponse() {
        val journal = CopyOnWriteArrayList<String>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket(InetAddress.getByName("127.0.0.1"), server.localPort).use { client ->
                client.soTimeout = 5_000
                val session = AirPlaySession(
                    socket = server.accept(),
                    config = AirPlayConfig(
                        deviceName = "test",
                        deviceId = "02:00:00:00:00:02",
                        btMac = "02:00:00:00:00:01",
                        sourceVersion = "1.0",
                        main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
                    ),
                    identity = AirPlayIdentity.generate(),
                    pairings = PairingStore(),
                    mfi = null,
                    listener = object : AirPlaySessionListener {
                        override fun onDebugLog(message: String) { journal.add(message) }
                        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
                            throw IllegalStateException("SETUP callback failed", SocketException("socket failure"))
                        }
                    },
                    media = object : AirPlayMediaHandler {},
                )
                try {
                    session.start()
                    val body = BplistCodec.encode(mapOf("name" to "test"))
                    val output = client.getOutputStream()
                    output.write("SETUP /test RTSP/1.0\r\nCSeq: 6\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(body)
                    output.flush()

                    val status = client.getInputStream().bufferedReader().readLine()
                    assertTrue(status.contains("500"))
                    val persistent = journal.joinToString("\n")
                    assertTrue(persistent.contains("handler failed method=SETUP cseq=6"))
                    assertTrue(persistent.contains("java.lang.IllegalStateException: SETUP callback failed"))
                    assertTrue(persistent.contains("Caused by: java.net.SocketException: socket failure"))
                    assertTrue(persistent.contains("AirPlaySessionFailureTest.kt:"))
                } finally {
                    session.close()
                }
            }
        }
    }
}
