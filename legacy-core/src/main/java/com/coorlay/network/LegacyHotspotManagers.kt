package com.coorlay.network

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Handler
import android.os.Looper
import com.coorlay.UiText.tr
import com.coorlay.orchestration.ManualHotspotBand
import com.coorlay.orchestration.ManualHotspotSecurity
import com.coorlay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ManualHotspotManager(context: Context, private val ssid: String, private val passphrase: String,
    private val band: ManualHotspotBand, private val channel: Int, private val security: ManualHotspotSecurity,
    private val onDiagnostic: (String) -> Unit = {}) : WirelessHotspotManager {
    private val closed = AtomicBoolean(false)
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        require(ssid.isNotBlank()) { "Configure the head-unit hotspot SSID" }
        require(security == ManualHotspotSecurity.OPEN || security == ManualHotspotSecurity.WPA2) { "Android 4.3 supports open or WPA2 hotspots" }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (!closed.get() && System.nanoTime() < deadline) {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces.filter { it.isUp && !it.isLoopback &&
                (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("p2p")) }) {
                val address = Collections.list(iface.inetAddresses).filterIsInstance<Inet4Address>().firstOrNull() ?: continue
                onDiagnostic("Manual hotspot interface=${iface.name}; channel is not observable on this firmware")
                return WirelessHotspotInfo(ssid, passphrase,
                    if (security == ManualHotspotSecurity.OPEN) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2,
                    0, null, iface.hardwareAddress?.joinToString(":") { "%02x".format(it.toInt() and 255) },
                    iface.name, address, band.name, WirelessHotspotBackend.MANUAL_HOTSPOT)
            }
            Thread.sleep(200)
        }
        throw IOException("No active Wi-Fi hotspot interface; enable the head-unit hotspot first")
    }
    override fun close() { closed.set(true) }
}

/**
 * Wi-Fi Direct group owner through the Android 4.3 API; credentials are read from the group actually created by Android.
 *
 * The stock Coolray firmware creates p2p_supplicant.conf from /system/etc/wifi/wpa_supplicant.conf, whose
 * "ctrl_interface=DIR=/data/system/wpa_supplicant" Android does not rewrite, so the p2p0 control socket is not where
 * WifiHW looks (/data/misc/wifi/sockets) and P2P is never enabled ("WifiP2pService: P2p socket connection failed").
 * The coorlay root script (RootInstall) fixes that file. This class waits for the P2P state broadcast instead of
 * retrying blindly, and puts the head-unit hotspot back on every failure.
 */
class WifiP2pGroupManager(context: Context, private val onDiagnostic: (String) -> Unit = {}) : WirelessHotspotManager {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        ?: throw IOException("Wi-Fi Direct service unavailable")
    private val channel = manager.initialize(context, Looper.getMainLooper()) {}
    private val closed = AtomicBoolean(false)
    @Volatile private var ownsGroup = false
    @Volatile private var turnedOffHotspot = false
    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run Wi-Fi startup on the worker" }
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        try {
            prepareRadio(deadline)
            awaitP2pEnabled(minOf(deadline, System.nanoTime() + P2P_ENABLE_WAIT_NANOS))
            val group = groupState().takeIf { it.ready }?.also {
                // A group left by an earlier session (or re-invoked as persistent) makes createGroup fail; use it.
                onDiagnostic("Wi-Fi Direct: reusing the existing group")
                ownsGroup = true
            } ?: run {
                createGroupRetryingBusy(deadline)
                awaitGroup(deadline)
            }
            val owner = group.group!!
            val address = group.info!!.groupOwnerAddress
            val iface = NetworkInterface.getByInetAddress(address)
            onDiagnostic("Wi-Fi Direct group ready interface=${owner.`interface`}; channel is not exposed by API 18")
            remember(context, true)
            return WirelessHotspotInfo(owner.networkName, owner.passphrase, Iap2WirelessSecurity.WPA_WPA2,
                0, null, iface?.hardwareAddress?.joinToString(":") { "%02x".format(it.toInt() and 255) },
                owner.`interface`, address, "Auto", WirelessHotspotBackend.WIFI_P2P)
        } catch (failure: Exception) {
            restoreRadio()
            throw failure
        }
    }

    /**
     * P2P is enabled only while Wi-Fi client mode runs and wpa_supplicant answers on p2p0. The sticky
     * WIFI_P2P_STATE_CHANGED broadcast tells «still starting» from «this firmware cannot do it».
     */
    private fun awaitP2pEnabled(deadline: Long) {
        var last = p2pEnabled(context)
        onDiagnostic("Wi-Fi Direct: P2P state=${label(last)}")
        while (last != true && !closed.get() && System.nanoTime() < deadline) {
            Thread.sleep(250)
            val now = p2pEnabled(context)
            if (now != last) onDiagnostic("Wi-Fi Direct: P2P state=${label(now)}")
            last = now
        }
        if (last == true || closed.get()) return
        remember(context, false)
        throw IOException(tr(
            "Wi-Fi Direct не включился: в прошивке ГУ не настроен p2p_supplicant. Исправьте его root-скриптом coorlay " +
                "с флешки (инструкция ROOT-INSTALL) или включите «Использовать существующую точку доступа»",
            "Wi-Fi Direct did not turn on: the head-unit firmware has no working p2p_supplicant. Fix it with the coorlay " +
                "root script from a USB stick (ROOT-INSTALL guide) or turn on «Use an existing hotspot»"))
    }

    /** createGroup can still answer BUSY for a moment right after P2P reports enabled. */
    private fun createGroupRetryingBusy(deadline: Long) {
        val busyDeadline = minOf(deadline, System.nanoTime() + P2P_BUSY_WAIT_NANOS)
        var attempt = 0
        var failure: Int?
        while (true) {
            attempt++
            failure = createGroup(deadline)
            if (failure != WifiP2pManager.BUSY || closed.get() || System.nanoTime() + 500_000_000L >= busyDeadline) break
            if (attempt == 1) onDiagnostic("Wi-Fi Direct createGroup BUSY; retrying")
            Thread.sleep(500)
        }
        failure?.let { throw IOException("Wi-Fi Direct createGroup failed: ${reason(it)}") }
        if (attempt > 1) onDiagnostic("Wi-Fi Direct createGroup accepted on attempt $attempt")
    }

    private fun awaitGroup(deadline: Long): GroupState {
        while (!closed.get() && System.nanoTime() < deadline) {
            val state = groupState()
            if (state.ready) return state
            Thread.sleep(200)
        }
        throw IOException("Wi-Fi Direct group did not become ready")
    }

    private class GroupState(val group: WifiP2pGroup?, val info: WifiP2pInfo?) {
        val ready: Boolean get() = group != null && group.isGroupOwner && info?.groupOwnerAddress != null &&
            !group.networkName.isNullOrBlank() && !group.passphrase.isNullOrBlank()
    }

    private fun groupState(): GroupState {
        val ready = CountDownLatch(2)
        var group: WifiP2pGroup? = null
        var info: WifiP2pInfo? = null
        handler.post {
            try {
                manager.requestGroupInfo(channel) { group = it; ready.countDown() }
                manager.requestConnectionInfo(channel) { info = it; ready.countDown() }
            } catch (_: SecurityException) { ready.countDown(); ready.countDown() }
        }
        ready.await(1000, TimeUnit.MILLISECONDS)
        return GroupState(group, info)
    }

    /** Returns null on success, the WifiP2pManager failure reason otherwise. */
    private fun createGroup(deadline: Long): Int? {
        val latch = CountDownLatch(1)
        var failure: Int? = null
        handler.post {
            if (closed.get()) { latch.countDown(); return@post }
            try {
                manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        ownsGroup = true
                        if (closed.get()) removeOwnedGroup()
                        latch.countDown()
                    }
                    override fun onFailure(reason: Int) { failure = reason; latch.countDown() }
                })
            } catch (_: SecurityException) { failure = -1; latch.countDown() }
        }
        val waitMillis = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
        if (!latch.await(waitMillis, TimeUnit.MILLISECONDS)) { close(); throw IOException("Wi-Fi Direct startup timed out") }
        return failure
    }

    /**
     * Android 4.3 keeps P2P disabled while the SoftAP runs (Wi-Fi client mode is off then) and answers
     * createGroup with BUSY. The «existing hotspot» mode may have left the coorlay SoftAP on.
     */
    @Suppress("DEPRECATION")
    private fun prepareRadio(deadline: Long) {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val apState = try { wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int } catch (_: Exception) { null }
        if (apState == WIFI_AP_STATE_ENABLING || apState == WIFI_AP_STATE_ENABLED) {
            onDiagnostic("Wi-Fi Direct: SoftAP state=$apState is on; turning it off")
            try {
                wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(wifi, null, false)
            } catch (error: Exception) { throw IOException("Could not turn off the hotspot for Wi-Fi Direct", error) }
            turnedOffHotspot = true
            while (!closed.get() && System.nanoTime() < deadline &&
                (wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int) != WIFI_AP_STATE_DISABLED) Thread.sleep(200)
        }
        if (!wifi.isWifiEnabled) {
            onDiagnostic("Wi-Fi Direct: Wi-Fi state=${wifi.wifiState}; turning Wi-Fi on")
            if (!wifi.setWifiEnabled(true)) throw IOException("Android refused to turn on Wi-Fi for Wi-Fi Direct")
            while (!closed.get() && System.nanoTime() < deadline && wifi.wifiState != WifiManager.WIFI_STATE_ENABLED) Thread.sleep(200)
            onDiagnostic("Wi-Fi Direct: Wi-Fi state=${wifi.wifiState}")
        }
    }

    /** Puts back the head-unit hotspot that [prepareRadio] switched off, so the hotspot mode keeps working. */
    @Suppress("DEPRECATION")
    private fun restoreRadio() {
        if (!turnedOffHotspot) return
        turnedOffHotspot = false
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        try {
            if (wifi.isWifiEnabled) wifi.setWifiEnabled(false)
            val restored = wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
                .invoke(wifi, null, true)
            onDiagnostic("Wi-Fi Direct unavailable; head-unit hotspot restored accepted=$restored")
        } catch (error: Exception) {
            onDiagnostic("Wi-Fi Direct: hotspot restore failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    companion object {
        private const val P2P_ENABLE_WAIT_NANOS = 8_000_000_000L
        private const val P2P_BUSY_WAIT_NANOS = 4_000_000_000L
        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_ENABLING = 12
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val PREFS = "wifi-direct"
        private const val KEY_WORKED = "groupReady"

        /** P2P state from the sticky WIFI_P2P_STATE_CHANGED broadcast: true enabled, false disabled, null not sent yet. */
        fun p2pEnabled(context: Context): Boolean? {
            val intent = try {
                context.applicationContext.registerReceiver(null, IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION))
            } catch (_: Exception) { null } ?: return null
            return when (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)) {
                WifiP2pManager.WIFI_P2P_STATE_ENABLED -> true
                WifiP2pManager.WIFI_P2P_STATE_DISABLED -> false
                else -> null
            }
        }

        /** Whether the last Wi-Fi Direct start created a group (null: never tried). Automatic attempts need true. */
        fun workedBefore(context: Context): Boolean? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return if (prefs.contains(KEY_WORKED)) prefs.getBoolean(KEY_WORKED, false) else null
        }

        /** Drops a recorded failure once P2P was seen enabled (the root fix came after it); a success stays. */
        fun forgetFailure(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.contains(KEY_WORKED) && !prefs.getBoolean(KEY_WORKED, false)) prefs.edit().remove(KEY_WORKED).apply()
        }

        private fun remember(context: Context, worked: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WORKED, worked).apply()
        }

        private fun label(enabled: Boolean?) = when (enabled) { true -> "enabled"; false -> "disabled"; null -> "unknown" }

        private fun reason(code: Int) = when (code) {
            WifiP2pManager.ERROR -> "ERROR"
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            else -> code.toString()
        }
    }
    override fun close() {
        closed.set(true)
        handler.post { removeOwnedGroup() }
    }
    private fun removeOwnedGroup() {
        if (ownsGroup) {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { ownsGroup = false }
                override fun onFailure(reason: Int) { onDiagnostic("Wi-Fi Direct removeGroup failed: $reason") }
            })
        }
    }
}
