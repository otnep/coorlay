package com.coorlay.coolray

import android.test.AndroidTestCase
import android.util.Base64
import android.util.Log
import com.coorlay.transport.BlockingDuplexByteStream
import com.coorlay.transport.IphoneUsbException
import com.coorlay.transport.LockdownPairRecord
import com.coorlay.transport.LockdownPairRecordGenerator
import com.coorlay.transport.LockdownTlsEngineFactory
import com.coorlay.transport.TlsDuplexChannel
import java.io.ByteArrayInputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Real Dalvik/native TLS handshakes, including Lockdown's root client certificate. */
class UsbTlsTest : AndroidTestCase() {
    fun testMutualTls12RoundTripOnAndroid43() = roundTrip("TLSv1.2")
    fun testMutualTls13RoundTripOnAndroid43() = roundTrip("TLSv1.3")

    private fun roundTrip(protocol: String) {
        val systemProvider = SSLContext.getInstance("TLS").provider.name
        val platformEngine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine()
        Log.i(TAG, "Platform engine=${platformEngine.javaClass.name} supported=${platformEngine.supportedProtocols.joinToString(",")}")
        Log.i(TAG, LegacyUsbTls.install())
        assertEquals(systemProvider, SSLContext.getInstance("TLS").provider.name)
        val record = pairRecord()
        val expectedClientCertificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(record.rootCertificatePem)).encoded
        val payload = ByteArray(128 * 1024) { (it * 31 + 7).toByte() }
        val serverError = AtomicReference<Throwable?>()
        val server = LockdownTlsEngineFactory.createContext(record).serverSocketFactory
            .createServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.enabledProtocols = arrayOf(protocol)
        server.needClientAuth = true
        server.soTimeout = 10_000
        val worker = Thread({
            try {
                val socket = server.accept() as SSLSocket
                // java.net.Socket implements Closeable only from API 19 onward.
                try {
                    socket.soTimeout = 10_000
                    socket.startHandshake()
                    assertEquals(protocol, socket.session.protocol)
                    assertTrue(expectedClientCertificate.contentEquals(socket.session.peerCertificates[0].encoded))
                    val received = ByteArray(payload.size)
                    var offset = 0
                    while (offset < received.size) {
                        val count = socket.inputStream.read(received, offset, minOf(319, received.size - offset))
                        check(count > 0) { "TLS client ended before sending the payload" }
                        offset += count
                    }
                    assertTrue(payload.contentEquals(received))
                    socket.outputStream.write(received)
                    socket.outputStream.flush()
                } finally { socket.close() }
            } catch (error: Throwable) {
                serverError.set(error)
                Log.e(TAG, "$protocol server failed", error)
            }
        }, "CuRPlay-TLS-test-server")
        worker.isDaemon = true
        worker.start()
        val transport = SocketStream(Socket("127.0.0.1", server.localPort))
        try {
            val diagnostics = mutableListOf<String>()
            TlsDuplexChannel.open(transport, record, 10_000) {
                diagnostics += it
                Log.i(TAG, it)
            }.use { channel ->
                channel.send(payload)
                val received = ByteArray(payload.size)
                var offset = 0
                while (offset < received.size) {
                    val bytes = channel.recv(minOf(137, received.size - offset), 10_000)
                    check(bytes != null && bytes.isNotEmpty()) { "TLS server ended before echoing the payload" }
                    bytes.copyInto(received, offset)
                    offset += bytes.size
                }
                assertTrue(payload.contentEquals(received))
            }
            assertTrue(transport.closed)
            assertTrue(diagnostics.any { it.contains("handshake ready protocol=$protocol") })
            worker.join(10_000)
            assertFalse("TLS server did not finish", worker.isAlive)
            serverError.get()?.let { throw AssertionError("TLS server failed", it) }
            Log.i(TAG, "$protocol mutual handshake/root identity/128KiB fragmented echo/close passed")
        } finally {
            transport.close()
            server.close()
            worker.join(1000)
        }
    }

    fun testStalledHandshakeTimesOutAndClosesTransport() {
        LegacyUsbTls.install()
        var closed = false
        val stalled = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = Unit
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = null
            override fun close() { closed = true }
        }
        try {
            TlsDuplexChannel.open(stalled, pairRecord(), 100)
            fail("An unresponsive peer must not leave TLS startup hanging")
        } catch (_: IphoneUsbException.TimedOut) {
            assertTrue("Failed handshake must release the USB stream", closed)
        }
    }

    private fun pairRecord(): LockdownPairRecord {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        fun der(tag: Int, bytes: ByteArray): ByteArray {
            val length = if (bytes.size < 128) byteArrayOf(bytes.size.toByte())
                else byteArrayOf(0x82.toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte())
            return byteArrayOf(tag.toByte()) + length + bytes
        }
        val encoded = der(0x30, der(2, key.modulus.toByteArray()) + der(2, key.publicExponent.toByteArray()))
        val pem = ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.encodeToString(encoded, Base64.NO_WRAP) +
            "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
        return LockdownPairRecordGenerator.generate(pem, "02:00:00:00:00:02", "test-host", "test-system")
    }

    /** Deliberately fragments encrypted reads; production USBMUX can split TLS records too. */
    private class SocketStream(private val socket: Socket) : BlockingDuplexByteStream {
        var closed = false
            private set
        override fun send(data: ByteArray) { socket.outputStream.write(data); socket.outputStream.flush() }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            socket.soTimeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(minOf(maxBytes, 511))
            return try {
                val count = socket.inputStream.read(bytes)
                if (count < 0) ByteArray(0) else bytes.copyOf(count)
            } catch (_: SocketTimeoutException) { null }
        }
        override fun close() { closed = true; socket.close() }
    }

    companion object { private const val TAG = "CuRPlay-TLS-Test" }
}
