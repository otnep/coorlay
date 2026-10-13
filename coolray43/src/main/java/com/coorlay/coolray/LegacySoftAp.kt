package com.coorlay.coolray

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.coorlay.UiText.tr
import java.io.IOException
import java.io.InterruptedIOException

class HotspotSettings(val ssid: String, val credential: String) {
    fun validate() {
        require(ssid.isNotBlank() && ssid.none { it.isISOControl() } && ssid.toByteArray(Charsets.UTF_8).size <= 32) { tr("SSID должен содержать от 1 до 32 байт UTF-8 без управляющих символов", "The SSID must be 1 to 32 UTF-8 bytes without control characters") }
        require(credential.length in 8..63 && credential.all { it.code in 33..126 }) {
            tr("Пароль WPA2: 8–63 латинских символа/цифры, без пробелов", "WPA2 password: 8–63 Latin letters/digits, no spaces")
        }
    }
    override fun toString() = "SSID=$ssid; credential characters=${credential.length}"
}
/**
 * The head-unit network for wireless CarPlay. The driver never has to type it: the iPhone gets the name and
 * password over Bluetooth (iAP2), so coorlay makes them up once and keeps them.
 */
internal object HeadUnitNetwork {
    /** No 0/O, 1/l/I: the values are shown in the advanced settings and may be read aloud. */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val SSID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generate(random: java.util.Random = java.security.SecureRandom()): HotspotSettings = HotspotSettings(
        "coorlay-" + (1..4).map { SSID_ALPHABET[random.nextInt(SSID_ALPHABET.length)] }.joinToString(""),
        (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(""))

    fun saved(prefs: android.content.SharedPreferences) =
        HotspotSettings(prefs.getString("ssid", "").orEmpty(), prefs.getString("password", "").orEmpty())

    /** The saved network when it is usable, otherwise a new one, saved; [created] tells which. */
    fun ensure(prefs: android.content.SharedPreferences, created: (HotspotSettings) -> Unit = {}): HotspotSettings {
        val current = saved(prefs)
        if (try { current.validate(); true } catch (_: Exception) { false }) return current
        return generate().also {
            prefs.edit().putString("ssid", it.ssid).putString("password", it.credential).apply()
            created(it)
        }
    }
}

data class HotspotResult(val ready: Boolean, val error: String? = null) {
    fun describe() = "HOTSPOT ready=$ready" + (error?.let { "; $it" } ?: "")
}

/** Factory Android 4.3 SoftAP API. Runs on a bounded worker, independently of Bluetooth. */
object LegacySoftAp {
    private const val WPA2_PSK = 4 // API-18 SoftAP KeyMgmt; also observed in the GEELY configuration.
    @Suppress("DEPRECATION")
    fun configuration(settings: HotspotSettings): WifiConfiguration {
        settings.validate()
        return WifiConfiguration().apply {
            SSID = settings.ssid
            preSharedKey = settings.credential // SoftAP expects raw values, unlike client-network config.
            hiddenSSID = false
            allowedKeyManagement.clear(); allowedKeyManagement.set(WPA2_PSK)
            allowedAuthAlgorithms.clear(); allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            allowedProtocols.clear(); allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            allowedPairwiseCiphers.clear(); allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedGroupCiphers.clear(); allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            OemSoftApConfiguration.applyStationLimit(this)
        }
    }

    @Suppress("DEPRECATION")
    fun verify(actual: WifiConfiguration?, settings: HotspotSettings): String {
        check(actual != null) { tr("Android не вернул сохранённую конфигурацию точки", "Android did not return the saved hotspot configuration") }
        check(actual.SSID?.let(ConnectionDiagnostics::unquote) == settings.ssid) { tr("Android сохранил другой SSID", "Android saved a different SSID") }
        check(actual.allowedKeyManagement.get(WPA2_PSK) && actual.allowedKeyManagement.cardinality() == 1) { tr("Android сохранил другую защиту точки", "Android saved different hotspot security") }
        val match = ConnectionDiagnostics.credentialCheck(actual.preSharedKey, settings.credential)
        check(match != "MISMATCH") { tr("Android сохранил другой пароль точки", "Android saved a different hotspot password") }
        OemSoftApConfiguration.verifyStationLimit(actual)
        return match
    }

    /** True when the SoftAP reports WIFI_AP_STATE_ENABLED; null when the state cannot be read. */
    fun isEnabled(context: Context): Boolean? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int == 13
    } catch (_: Exception) { null }

    /**
     * Whether the SoftAP runs with exactly [settings]. A running one with another name (an earlier test network
     * called "iPhone", 2026-10-09) would send the iPhone to a network that is not there. Null when unreadable.
     */
    @Suppress("DEPRECATION")
    fun runsWith(context: Context, settings: HotspotSettings): Boolean? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int != 13) false
        else (wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration)?.let { actual ->
            actual.SSID?.let(ConnectionDiagnostics::unquote) == settings.ssid &&
                ConnectionDiagnostics.credentialCheck(actual.preSharedKey, settings.credential) != "MISMATCH"
        }
    } catch (_: Exception) { null }

    /** coorlay switched the head unit's Wi-Fi client off for the SoftAP; [stop] gives it back. */
    @Volatile private var clientTurnedOff = false

    /**
     * Switches the coorlay SoftAP off after a wireless session (only when it runs with [settings]). While it stays on,
     * an iPhone that turns its Wi-Fi back on rejoins it as an ordinary network, not as CarPlay, and sends its internet
     * there (tester 2026-10-11 23:37/23:40: joined 15 s before the CarPlay start, Spotify offline). The Wi-Fi client
     * comes back when coorlay had turned it off.
     */
    @Suppress("DEPRECATION")
    fun stop(context: Context, settings: HotspotSettings, report: (String) -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (runsWith(context, settings) != true) { report("HOTSPOT stop skipped: coorlay network not running"); return }
        val setEnabled = wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
        val accepted = setEnabled.invoke(wifi, null, false) != false
        val deadline = System.currentTimeMillis() + 5000
        while (isEnabled(context) != false && System.currentTimeMillis() < deadline) Thread.sleep(200)
        report("HOTSPOT stopped after the wireless session accepted=$accepted enabled=${isEnabled(context)}")
        if (clientTurnedOff) {
            clientTurnedOff = false
            report("HOTSPOT Wi-Fi client restored=${wifi.setWifiEnabled(true)}")
        }
    }

    @Suppress("DEPRECATION")
    fun start(context: Context, settings: HotspotSettings, cancelled: () -> Boolean, report: (String) -> Unit): HotspotResult {
        check(Build.VERSION.SDK_INT <= 18) { tr("Прямое управление точкой предназначено для Android 4.3 ГУ", "Direct hotspot control is meant for the Android 4.3 head unit") }
        val config = configuration(settings)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val setEnabled = wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
        val getState = wifi.javaClass.getMethod("getWifiApState")
        val setConfig = wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
        val getConfig = wifi.javaClass.getMethod("getWifiApConfiguration")
        fun active() { if (cancelled()) throw InterruptedIOException(tr("Создание точки отменено или превышено время ожидания", "Hotspot creation was cancelled or timed out")) }
        fun state() = getState.invoke(wifi) as Int
        fun awaitState(target: Int) {
            var previous: Int? = null
            while (true) {
                active()
                val current = state()
                if (current != previous) { report("HOTSPOT state=$current target=$target"); previous = current }
                if (current == target) return
                if (current == 14 && target == 13) throw IOException(tr("Драйвер сообщил WIFI_AP_STATE_FAILED", "The driver reported WIFI_AP_STATE_FAILED"))
                Thread.sleep(200)
            }
        }
        active()
        report("HOTSPOT configure $settings security=WPA2-PSK/CCMP")
        report("HOTSPOT Neusoft maxNumSta=" + (OemSoftApConfiguration.stationLimit(config) ?: "field unavailable on this Android"))
        if (state() != 11) {
            active()
            check(setEnabled.invoke(wifi, null, false) != false) { tr("Android отклонил остановку прежней точки", "Android refused to stop the previous hotspot") }
            awaitState(11)
        }
        active()
        if (wifi.isWifiEnabled) {
            report("HOTSPOT disabling Wi-Fi client before SoftAP")
            clientTurnedOff = true
            check(wifi.setWifiEnabled(false)) { tr("Android отклонил выключение Wi-Fi клиента", "Android refused to turn off Wi-Fi client mode") }
            while (wifi.wifiState != WifiManager.WIFI_STATE_DISABLED) { active(); Thread.sleep(200) }
        }
        active()
        check(setConfig.invoke(wifi, config) != false) { tr("Android отклонил конфигурацию точки", "Android rejected the hotspot configuration") }
        active()
        check(setEnabled.invoke(wifi, config, true) != false) { tr("Android отклонил запуск точки", "Android refused to start the hotspot") }
        awaitState(13)
        active()
        val actual = getConfig.invoke(wifi) as? WifiConfiguration
        val match = verify(actual, settings)
        report("HOTSPOT readback Neusoft maxNumSta=" + (actual?.let(OemSoftApConfiguration::stationLimit) ?: "field unavailable on this Android"))
        report("HOTSPOT readback SSID=MATCH credential=$match security=WPA2-PSK state=ENABLED")
        return HotspotResult(true, if (match == "unavailable") tr("Android скрывает пароль; подтвердите подключением iPhone", "Android hides the password; confirm by connecting the iPhone") else null)
    }
}
