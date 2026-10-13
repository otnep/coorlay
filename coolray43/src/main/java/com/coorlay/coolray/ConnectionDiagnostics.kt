package com.coorlay.coolray

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import java.io.File

object ConnectionDiagnostics {
    /** The driver's switches with the value in effect (stored, or the default when never changed). */
    private val SWITCHES = listOf("autoConnect" to true, AUTO_WIRELESS to false, WIFI_DIRECT to true,
        DETAILED_LOGS to BuildConfig.DETAILED_LOGS_DEFAULT)

    /** Every setting in the report (user, 2026-10-12): the switches first, then all other stored values. */
    fun settings(context: Context): List<String> {
        val stored = context.getSharedPreferences("coolray43", Context.MODE_PRIVATE).all
        val result = SWITCHES.map { (key, default) ->
            val value = stored[key] as? Boolean
            "$key=${value ?: default}" + if (value == null) " (default)" else ""
        }.toMutableList()
        val known = SWITCHES.map { it.first }.toSet()
        stored.keys.filter { it !in known }.sorted().forEach { key ->
            // The hotspot passphrase is never written out; its length shows whether one is set.
            result += if (key == "password") "password=<${(stored[key] as? String).orEmpty().length} characters>" else "$key=${stored[key]}"
        }
        return result
    }

    fun usb(context: Context): List<String> {
        val result = mutableListOf<String>()
        val preferences = context.getSharedPreferences("coolray43", Context.MODE_PRIVATE)
        result += "coorlay detailed journal=${preferences.getBoolean(DETAILED_LOGS, BuildConfig.DETAILED_LOGS_DEFAULT)}"
        try {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = manager.deviceList.values.sortedBy { it.deviceId }
            result += "USB Android: devices=${devices.size}, Apple=${devices.count { it.vendorId == 0x05ac }}"
            for (device in devices) {
                result += "USB Android: vid=%04x pid=%04x permission=%s interfaces=%d".format(
                    device.vendorId, device.productId, manager.hasPermission(device), device.interfaceCount)
                for (index in 0 until device.interfaceCount) {
                    val iface = device.getInterface(index)
                    result += "USB interface id=${iface.id} class=${iface.interfaceClass} " +
                        "subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} endpoints=${iface.endpointCount}"
                }
            }
        } catch (error: Exception) {
            result += "USB Android: ${error.javaClass.simpleName}: ${error.message}"
        }
        // Read only public numeric descriptors. Never read serial numbers or device strings.
        try {
            val entries = File("/sys/bus/usb/devices").listFiles()
            if (entries == null) result += "USB kernel: list unavailable"
            else {
                var count = 0
                var inaccessible = 0
                for (entry in entries.sortedBy { it.name }) {
                    val vendor = File(entry, "idVendor")
                    if (!vendor.exists()) continue
                    try {
                        val vid = vendor.readText().trim().toInt(16)
                        val pid = File(entry, "idProduct").readText().trim().toInt(16)
                        result += "USB kernel: path=${entry.name} vid=%04x pid=%04x".format(vid, pid)
                        for (name in listOf("bConfigurationValue", "bNumConfigurations", "speed", "authorized")) {
                            val value = File(entry, name)
                            if (value.canRead()) result += "USB kernel ${entry.name} $name=${value.readText().trim()}"
                        }
                        for (iface in entries.filter { it.name.startsWith(entry.name + ":") }.sortedBy { it.name }) {
                            val driver = File(iface, "driver")
                            result += "USB kernel interface=${iface.name} driver=${if (driver.exists()) driver.canonicalFile.name else "unbound"}"
                        }
                        count++
                    } catch (_: Exception) { inaccessible++ }
                }
                result += "USB kernel: devices=$count, unreadable descriptors=$inaccessible"
            }
        } catch (error: Exception) {
            result += "USB kernel: ${error.javaClass.simpleName}: ${error.message}"
        }
        return result
    }

    internal fun unquote(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') value.substring(1, value.length - 1) else value

    internal fun credentialCheck(actual: String?, entered: String): String {
        if (actual == null || actual == "*") return "unavailable"
        return if (unquote(actual) == entered) "MATCH" else "MISMATCH"
    }

    fun wifi(context: Context): List<String> {
        val result = mutableListOf<String>()
        val preferences = context.getSharedPreferences("coolray43", Context.MODE_PRIVATE)
        val entered = preferences.getString("password", "").orEmpty()
        val ssid = preferences.getString("ssid", "").orEmpty()
        result += "coorlay hotspot mode=" + if (hotspotMode(preferences)) "MANUAL" else "WIFI_P2P"
        val p2p = com.coorlay.network.WifiP2pGroupManager
        result += "Wi-Fi Direct: P2P enabled=${p2p.p2pEnabled(context) ?: "unknown"}; group created before=${p2p.workedBefore(context) ?: "never tried"}"
        result += WifiDirectReadiness.describe(context, preferences)
        result += "coorlay configured SSID=$ssid; credential characters=${entered.length}; UTF8 bytes=${entered.toByteArray(Charsets.UTF_8).size}; edge whitespace=${entered != entered.trim()}; nonASCII=${entered.any { it.code !in 32..126 }}"
        try {
            val manager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            result += "Wi-Fi Android state=${manager.wifiState}; enabled=${manager.isWifiEnabled}"
            // Root installer v5 sets it to an hour: Android's 60 s scans stalled Wi-Fi Direct sessions (2026-10-12).
            result += "Wi-Fi client scan interval during Wi-Fi Direct=" + (runCatching {
                android.provider.Settings.Global.getString(context.contentResolver, "wifi_scan_interval_p2p_connected_ms")
            }.getOrNull() ?: "default (60000)") + " ms"
            try {
                val state = manager.javaClass.getMethod("getWifiApState").invoke(manager) as? Int
                val label = when (state) { 10 -> "DISABLING"; 11 -> "DISABLED"; 12 -> "ENABLING"; 13 -> "ENABLED"; 14 -> "FAILED"; else -> "unknown" }
                result += "Head-unit hotspot: state=$state ($label)"
            } catch (error: Exception) {
                result += "Head-unit hotspot: state unavailable (${error.javaClass.simpleName})"
            }
            try {
                val config = manager.javaClass.getMethod("getWifiApConfiguration").invoke(manager) as? WifiConfiguration
                if (config == null) result += "Head-unit hotspot: configuration unavailable"
                else {
                    val actualSsid = config.SSID?.let(::unquote)
                    result += "Head-unit hotspot: SSID=$actualSsid; keyManagement=${config.allowedKeyManagement}; authAlgorithms=${config.allowedAuthAlgorithms}; protocols=${config.allowedProtocols}"
                    result += "Head-unit hotspot: Neusoft maxNumSta=" + (OemSoftApConfiguration.stationLimit(config) ?: "field unavailable on this Android")
                    result += "SSID check=" + if (actualSsid == null) "unavailable" else if (actualSsid == ssid) "MATCH" else "MISMATCH"
                    result += "Credential check=${credentialCheck(config.preSharedKey, entered)}; expected characters=" +
                        if (config.preSharedKey == null || config.preSharedKey == "*") "unavailable" else unquote(config.preSharedKey).length.toString()
                }
            } catch (error: Exception) {
                result += "Head-unit hotspot: configuration unavailable (${error.javaClass.simpleName}: ${error.cause?.javaClass?.simpleName ?: error.message})"
            }
        } catch (error: Exception) { result += "Wi-Fi: ${error.javaClass.simpleName}: ${error.message}" }
        return result
    }

    fun platform(context: Context): List<String> {
        val pm = context.packageManager
        return listOf(
            "coorlay ${BuildConfig.VERSION_NAME}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}; ${Build.CPU_ABI}",
            "Head unit ${Build.MANUFACTURER}/${Build.MODEL}; board=${Build.BOARD}; firmware=${Build.DISPLAY}; locale=${java.util.Locale.getDefault()}",
            "Android features: USB host=${pm.hasSystemFeature("android.hardware.usb.host")}; " +
                "Bluetooth=${pm.hasSystemFeature("android.hardware.bluetooth")}; Wi-Fi Direct=${pm.hasSystemFeature("android.hardware.wifi.direct")}",
        )
    }
}
