package com.coorlay.coolray

import android.os.Build
import android.test.AndroidTestCase
import android.util.Base64
import com.coorlay.AirPlayPersistence
import com.coorlay.CoorlayBootstrap
import com.coorlay.airplay.AirPlayCrypto
import com.coorlay.mfi.LocalMfiAuthenticationClient
import com.coorlay.transport.LegacyUsbNative
import com.coorlay.transport.LockdownPairRecordGenerator
import com.coorlay.transport.LockdownTlsEngineFactory
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey

/** Run on the actual API-18 VM: JVM tests cannot detect Dalvik linkage or platform JCA gaps. */
class PlatformSmokeTest : AndroidTestCase() {
    fun testApi18RandomAndPersistentPairingIdentity() {
        assertEquals(18, Build.VERSION.SDK_INT)
        assertEquals("CoolrayKernelRandom", SecureRandom().provider.name)
        val identity = AirPlayPersistence.loadIdentity(context)
        val restored = AirPlayPersistence.loadIdentity(context)
        assertTrue(identity.publicKey.contentEquals(restored.publicKey))
        val message = "API 18 smoke test".toByteArray(Charsets.UTF_8)
        val signed = AirPlayCrypto.ed25519Sign(identity.privateKey, message)
        assertTrue(AirPlayCrypto.ed25519Verify(identity.publicKey, message, signed))
        val local = AirPlayCrypto.x25519Generate()
        val remote = AirPlayCrypto.x25519Generate()
        assertTrue(AirPlayCrypto.x25519Shared(local.privateKey, remote.publicKey).contentEquals(
            AirPlayCrypto.x25519Shared(remote.privateKey, local.publicKey)))
    }
    fun testProvisionedMfiIdentitySignsChallengeOnAndroid43() {
        CoorlayBootstrap.ensure(context)
        val client = LocalMfiAuthenticationClient.load(File(context.filesDir, LocalMfiAuthenticationClient.DIRECTORY))
        assertEquals(3, client.protocolMajor())
        assertEquals(607, client.readCertificate(1280).size)
        val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
        assertEquals(64, client.signChallenge(challenge).size)
    }
    fun testNativeLibrariesAndControllerVerifyOnDalvik() {
        assertEquals(-9, LegacyUsbNative.transfer(-1, 129, ByteArray(1), 0, 1, 1))
        assertEquals(-9, LegacyUsbNative.configuration(-1, 6))
        assertEquals(-9, LegacyUsbNative.detach(-1, 1))
        assertEquals(-9, LegacyUsbNative.attach(-1, 1))
        try { LegacyUsbNative.driver(-1, 1); fail("Bad file descriptor must fail GETDRIVER") }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("errno=9")) }
        assertEquals(-9, LegacyUsbNative.alternate(-1, 4, 1))
        assertEquals(-9, LegacyUsbNative.claim(-1, 1))
        assertEquals(-9, LegacyUsbNative.release(-1, 1, false))
        Class.forName("com.coorlay.orchestration.CarPlayController")
        Class.forName("com.coorlay.transport.Iap2CsmChannel")
        Class.forName("com.coorlay.transport.Iap2LinkChannel")
        Class.forName("com.coorlay.airplay.AirPlaySession")
        Class.forName("com.coorlay.network.CarPlayBonjour")
        Class.forName("com.coorlay.network.CarPlayVpnService")
        Class.forName("com.coorlay.media.AndroidMediaSink")
    }

    fun testWifiCredentialDiagnosticsPreserveSpacesAndNeverExportSecrets() {
        assertEquals("MATCH", ConnectionDiagnostics.credentialCheck("\"abcdefgh\"", "abcdefgh"))
        assertEquals("MISMATCH", ConnectionDiagnostics.credentialCheck("abcdefgh ", "abcdefgh"))
        assertEquals("MATCH", ConnectionDiagnostics.credentialCheck("abcdefgh ", "abcdefgh "))
        assertEquals("unavailable", ConnectionDiagnostics.credentialCheck("*", "abcdefgh"))
        assertEquals("unavailable", ConnectionDiagnostics.credentialCheck(null, "abcdefgh"))
        val preferences = context.getSharedPreferences("coolray43", android.content.Context.MODE_PRIVATE)
        val old = preferences.getString("password", null)
        try {
            preferences.edit().putString("password", "must-not-appear-in-export").commit()
            val diagnostics = ConnectionDiagnostics.wifi(context).joinToString("\n")
            assertFalse(diagnostics.contains("must-not-appear-in-export"))
            assertTrue(diagnostics.contains("credential characters=25"))
        } finally { preferences.edit().putString("password", old).commit() }
    }
    fun testUsbLockdownCertificatesAndTlsEngineOnAndroid43() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        fun der(tag: Int, bytes: ByteArray): ByteArray {
            val length = if (bytes.size < 128) byteArrayOf(bytes.size.toByte())
                else byteArrayOf(0x82.toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte())
            return byteArrayOf(tag.toByte()) + length + bytes
        }
        fun integer(value: BigInteger) = der(2, value.toByteArray())
        val encoded = der(0x30, integer(key.modulus) + integer(key.publicExponent))
        val pem = ("-----BEGIN RSA PUBLIC KEY-----\n" + Base64.encodeToString(encoded, Base64.NO_WRAP) +
            "\n-----END RSA PUBLIC KEY-----\n").toByteArray(Charsets.US_ASCII)
        val record = LockdownPairRecordGenerator.generate(pem, "02:00:00:00:00:02", "test-host", "test-system")
        LegacyUsbTls.install()
        val engine = LockdownTlsEngineFactory.create(record)
        assertTrue(engine.useClientMode)
        assertTrue(engine.supportedProtocols.contains("TLSv1.2"))
        assertTrue(engine.supportedProtocols.contains("TLSv1.3"))
    }
}
