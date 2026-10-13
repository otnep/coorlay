package com.coorlay.coolray

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.test.InstrumentationTestCase
import android.os.Parcel
import android.os.Parcelable
import dalvik.system.DexClassLoader
import java.io.File

class HotspotTest : InstrumentationTestCase() {
    private fun firmwareConfigurationClass(): Class<*> {
        val context = instrumentation.context
        val cache = instrumentation.targetContext.cacheDir!!
        val optimized = File(cache, "firmware-dex-opt").apply { check(isDirectory || mkdirs()) }
        val dex = File(cache, "coolray-stock-wifi-configuration.dex")
        context.assets.open("coolray-stock-wifi-configuration.dex").use { input ->
            dex.outputStream().use { input.copyTo(it) }
        }
        // Load the actual firmware implementation instead of the emulator's boot class.
        // Dalvik's optimized .dex filename must not collide with the source .dex.
        val loader = object : DexClassLoader(dex.absolutePath, optimized.absolutePath,
            null, javaClass.classLoader) {
            @Synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("android.net.wifi.WifiConfiguration")) return super.loadClass(name, resolve)
                return (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
            }
        }
        return loader.loadClass("android.net.wifi.WifiConfiguration")
    }

    fun testRealFirmwareZeroStationLimitIsCorrectedAndSurvivesParcelAndCopy() {
        val type = firmwareConfigurationClass()
        val config = type.getConstructor().newInstance()
        assertEquals(0, OemSoftApConfiguration.stationLimit(config))
        try { OemSoftApConfiguration.verifyStationLimit(config); fail("Factory zero limit must be rejected") }
        catch (_: IllegalStateException) { }
        assertEquals(5, OemSoftApConfiguration.applyStationLimit(config))
        type.getField("SSID").set(config, "CuRPlay")
        type.getField("preSharedKey").set(config, "abcdefgh1234")
        (type.getField("allowedKeyManagement").get(config) as java.util.BitSet).set(4)
        OemSoftApConfiguration.verifyStationLimit(config)
        val copy = type.getConstructor(type).newInstance(config)
        assertEquals(5, OemSoftApConfiguration.stationLimit(copy))
        val parcel = Parcel.obtain()
        try {
            type.getMethod("writeToParcel", Parcel::class.java, Int::class.javaPrimitiveType).invoke(config, parcel, 0)
            parcel.setDataPosition(0)
            val creator = type.getField("CREATOR").get(null) as Parcelable.Creator<*>
            val restored = creator.createFromParcel(parcel)!!
            assertEquals(5, OemSoftApConfiguration.stationLimit(restored))
            assertEquals("CuRPlay", type.getField("SSID").get(restored))
            assertEquals("abcdefgh1234", type.getField("preSharedKey").get(restored))
            assertTrue((type.getField("allowedKeyManagement").get(restored) as java.util.BitSet).get(4))
        } finally { parcel.recycle() }
    }

    fun testStandardAndroidWithoutVendorStationLimitRemainsSupported() {
        val config = LegacySoftAp.configuration(HotspotSettings("CuRPlay", "abcdefgh1234"))
        assertNull(OemSoftApConfiguration.stationLimit(config))
        assertNull(OemSoftApConfiguration.applyStationLimit(config))
        OemSoftApConfiguration.verifyStationLimit(config)
    }

    fun testApi18SoftApMethodsAndRawWpa2Configuration() {
        val wifi = instrumentation.targetContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
        wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
        wifi.javaClass.getMethod("getWifiApConfiguration")
        wifi.javaClass.getMethod("getWifiApState")
        val config = LegacySoftAp.configuration(HotspotSettings("CuRPlay", "abcdefgh1234"))
        assertEquals("CuRPlay", config.SSID)
        assertEquals("abcdefgh1234", config.preSharedKey)
        assertEquals(1, config.allowedKeyManagement.cardinality())
        assertTrue(config.allowedKeyManagement.get(4))
        assertFalse(config.hiddenSSID)
    }

    fun testReadbackRejectsAnotherSsidCredentialOrSecurityAndDoesNotClaimMaskedKeyMatches() {
        val settings = HotspotSettings("CuRPlay", "abcdefgh1234")
        val config = LegacySoftAp.configuration(settings)
        assertEquals("MATCH", LegacySoftAp.verify(config, settings))
        for (change in listOf<(WifiConfiguration) -> Unit>(
            { it.SSID = "GEELY" },
            { it.preSharedKey = "different-secret" },
            { it.allowedKeyManagement.clear(); it.allowedKeyManagement.set(1) },
        )) {
            val changed = LegacySoftAp.configuration(settings).also(change)
            try { LegacySoftAp.verify(changed, settings); fail("Mismatched configuration must not report ready") }
            catch (_: IllegalStateException) { }
        }
        config.preSharedKey = "*"
        assertEquals("unavailable", LegacySoftAp.verify(config, settings))
        assertFalse(settings.toString().contains("abcdefgh1234"))
    }

    fun testInvalidWpa2CredentialsAndOversizedUtf8SsidAreRejectedBeforeChangingWifi() {
        for (credential in listOf("short", "abcdefgh ", "пароль123", "x".repeat(64), "abcdefgh\n")) {
            try { HotspotSettings("CuRPlay", credential).validate(); fail("Invalid WPA2 credential accepted") }
            catch (_: IllegalArgumentException) { }
        }
        for (ssid in listOf("", "Я".repeat(17), "CuRPlay\n")) {
            try { HotspotSettings(ssid, "abcdefgh1234").validate(); fail("Invalid SSID accepted") }
            catch (_: IllegalArgumentException) { }
        }
        HotspotSettings("Я".repeat(16), "abcdefgh1234").validate()
    }
}
