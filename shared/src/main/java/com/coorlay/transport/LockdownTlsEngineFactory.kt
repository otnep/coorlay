package com.coorlay.transport

import android.annotation.SuppressLint
import android.os.Build
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Provider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509TrustManager

/**
 * Builds an unstarted client TLS engine for the dedicated USB Lockdown channel.
 *
 * Matching the locked transport behavior, the engine does not authenticate the peer certificate.
 * It must not be reused for internet or general-purpose TLS connections.
 */
object LockdownTlsEngineFactory {
    @Volatile private var usbProvider: Provider? = null

    /** Uses a bundled provider only for USB Lockdown; never changes global JCA providers. */
    fun configureUsbProvider(provider: Provider) {
        SSLContext.getInstance("TLS", provider)
        usbProvider = provider
    }

    @Throws(GeneralSecurityException::class)
    fun create(pairRecord: LockdownPairRecord, onDiagnostic: (String) -> Unit = {}): SSLEngine {
        val context = createContext(pairRecord)
        return context.createSSLEngine(PEER_HOST, PEER_PORT).apply {
            useClientMode = true
            if (Build.VERSION.SDK_INT >= 24) {
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = null }
            }
            onDiagnostic("USB TLS engine provider=${context.provider.name} class=${javaClass.name} " +
                "supported=${supportedProtocols.joinToString(",")} enabled=${enabledProtocols.joinToString(",")}")
        }
    }

    /** Dedicated USB identity context; also used by the mutual TLS platform regression test. */
    @Throws(GeneralSecurityException::class)
    fun createContext(pairRecord: LockdownPairRecord): SSLContext {
        val password = charArrayOf('l', 'o', 'c', 'k', 'd', 'o', 'w', 'n')
        // Lockdown presents the root identity from the pair record for both the session and
        // service TLS channels. HostCertificate is part of pairing, not this TLS identity.
        val privateKeyPem = pairRecord.rootPrivateKeyPem
        val certificatePem = pairRecord.rootCertificatePem
        var privateKeyDer: ByteArray? = null
        try {
            privateKeyDer = decodePkcs8Pem(privateKeyPem)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry(KEY_ALIAS, privateKey, password, arrayOf(certificate))
            }
            val keyManagers = KeyManagerFactory.getInstance("PKIX").apply {
                init(keyStore, password)
            }.keyManagers
            val provider = usbProvider
            return (if (provider == null) SSLContext.getInstance("TLS") else SSLContext.getInstance("TLS", provider)).apply {
                init(keyManagers, arrayOf(UsbLockdownTrustManager), null)
            }
        } finally {
            password.fill('\u0000')
            privateKeyPem.fill(0)
            certificatePem.fill(0)
            privateKeyDer?.fill(0)
        }
    }

    private fun decodePkcs8Pem(pem: ByteArray): ByteArray {
        val begin = pem.indexOf(BEGIN_PRIVATE_KEY)
        val end = pem.indexOf(END_PRIVATE_KEY, begin + BEGIN_PRIVATE_KEY.size)
        if (begin < 0 || end < 0) throw GeneralSecurityException("Invalid PKCS#8 private key PEM")
        val encoded = pem.copyOfRange(begin + BEGIN_PRIVATE_KEY.size, end)
        return try {
            Base64.getMimeDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid PKCS#8 private key PEM", error)
        } finally {
            encoded.fill(0)
        }
    }

    private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int = 0): Int {
        if (needle.isEmpty()) return startIndex.coerceIn(0, size)
        for (offset in startIndex.coerceAtLeast(0)..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        return -1
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object UsbLockdownTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val KEY_ALIAS = "lockdown-host"
    private const val PEER_HOST = "Device"
    private const val PEER_PORT = 0
    private val BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----".toByteArray(StandardCharsets.US_ASCII)
    private val END_PRIVATE_KEY = "-----END PRIVATE KEY-----".toByteArray(StandardCharsets.US_ASCII)
}
