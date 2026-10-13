package com.coorlay.coolray

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.coorlay.network.WifiP2pGroupManager
import java.io.File

/**
 * Hidden check whether Wi-Fi Direct works on this head unit, shown next to the «Wi-Fi Direct» switch and needed for
 * automatic wireless attempts. Since 2026-10-13 the switch itself picks the mode (on by default); nothing is switched
 * automatically any more.
 *
 * The stock Coolray firmware cannot enable P2P (p2p_supplicant.conf, see [WifiP2pGroupManager]); the coorlay root
 * script fixes it and leaves [ROOT_MARKER]. Evidence: a group was created before, or Android reported P2P enabled
 * (sticky WIFI_P2P_STATE_CHANGED, sent only while Wi-Fi client mode runs). The head-unit hotspot keeps client mode
 * off, so once per root fix (and once for an unmarked one, on the Coolray only) [probe] silently turns the hotspot off for a few seconds
 * while no session runs.
 */
internal object WifiDirectReadiness {
    const val ROOT_MARKER = "/data/local/tmp/coorlay-wifi-direct"
    private const val SEEN = "wifiDirectSeen"
    private const val PROBED = "wifiDirectProbed"
    private const val UNMARKED = "unmarked"
    private const val PROBE_P2P_MS = 10_000L
    private const val RADIO_MS = 10_000L
    private const val AP_DISABLED = 11
    private const val AP_ENABLING = 12
    private const val AP_ENABLED = 13

    fun ready(context: Context, prefs: SharedPreferences): Boolean {
        val worked = WifiP2pGroupManager.workedBefore(context)
        return worked == true || (worked != false && prefs.getBoolean(SEEN, false))
    }

    /** Every P2P state report; true when it newly proves Wi-Fi Direct works. */
    fun observed(context: Context, prefs: SharedPreferences, enabled: Boolean?): Boolean {
        if (enabled != true || ready(context, prefs)) return false
        // A failure recorded before the root fix must not hide the fix.
        WifiP2pGroupManager.forgetFailure(context)
        prefs.edit().putBoolean(SEEN, true).apply()
        return true
    }

    fun marker(): String? = try {
        File(ROOT_MARKER).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /** Without the root marker only the Coolray is probed: on another head unit the hotspot is never switched off. */
    fun needsProbe(context: Context, prefs: SharedPreferences): Boolean {
        val marker = marker()
        if (marker == null && !isCoolray()) return false
        return context.packageManager.hasSystemFeature("android.hardware.wifi.direct") && !ready(context, prefs) &&
            prefs.getString(PROBED, null) != (marker ?: UNMARKED)
    }

    /** The same identity the root installer checks. */
    private fun isCoolray() = Build.MANUFACTURER == "Neusoft" && Build.MODEL == "GEELY" && Build.BOARD == "C3ALFUS" &&
        Build.VERSION.SDK_INT == 18

    fun describe(context: Context, prefs: SharedPreferences): String =
        "Wi-Fi Direct readiness: ready=${ready(context, prefs)}; seen enabled=${prefs.getBoolean(SEEN, false)}; " +
            "root marker=${marker() ?: "none"}; probed=${prefs.getString(PROBED, null) ?: "never"}"

    /**
     * Hotspot off, Wi-Fi on, wait for the P2P state, then the radio as it was. Worker thread only, never during a
     * session. Runs once per [marker] value, also when it fails.
     */
    @Suppress("DEPRECATION")
    fun probe(context: Context, prefs: SharedPreferences, report: (String) -> Unit): Boolean {
        val key = marker() ?: UNMARKED
        prefs.edit().putString(PROBED, key).apply()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val getAp = wifi.javaClass.getMethod("getWifiApState")
        val setAp = wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
        fun apState() = getAp.invoke(wifi) as Int
        fun await(millis: Long, done: () -> Boolean): Boolean {
            val end = SystemClock.uptimeMillis() + millis
            while (!done()) { if (SystemClock.uptimeMillis() > end) return false; Thread.sleep(250) }
            return true
        }
        val apWasOn = apState().let { it == AP_ENABLED || it == AP_ENABLING }
        val wifiWasOn = wifi.isWifiEnabled
        report("WIFI-DIRECT probe: root fix=$key; hotspot on=$apWasOn; Wi-Fi on=$wifiWasOn")
        var enabled: Boolean? = null
        try {
            if (apWasOn) { setAp.invoke(wifi, null, false); await(RADIO_MS) { apState() == AP_DISABLED } }
            if (!wifi.isWifiEnabled) { wifi.setWifiEnabled(true); await(RADIO_MS) { wifi.wifiState == WifiManager.WIFI_STATE_ENABLED } }
            await(PROBE_P2P_MS) { WifiP2pGroupManager.p2pEnabled(context) == true }
            enabled = WifiP2pGroupManager.p2pEnabled(context)
        } catch (error: Exception) {
            report("WIFI-DIRECT probe: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            try {
                if (!wifiWasOn && wifi.isWifiEnabled) {
                    wifi.setWifiEnabled(false)
                    await(RADIO_MS) { wifi.wifiState == WifiManager.WIFI_STATE_DISABLED }
                }
                if (apWasOn) report("WIFI-DIRECT probe: hotspot restored accepted=${setAp.invoke(wifi, null, true)}")
            } catch (error: Exception) {
                report("WIFI-DIRECT probe: radio restore failed ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        report("WIFI-DIRECT probe: P2P state=${when (enabled) { true -> "enabled"; false -> "disabled"; null -> "unknown" }}")
        return observed(context, prefs, enabled) || ready(context, prefs)
    }
}
