package com.coorlay.coolray

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.*
import android.widget.*
import com.coorlay.AirPlayPersistence
import com.coorlay.CoorlayBootstrap
import com.coorlay.UiText.tr
import com.coorlay.airplay.*
import com.coorlay.media.AndroidMediaSink
import com.coorlay.media.CarPlayTouchMapper
import com.coorlay.network.CarPlayVpnService
import com.coorlay.orchestration.*
import com.coorlay.transport.Iap2IdentificationConfig
import com.coorlay.transport.LegacyUsbNative
import java.util.concurrent.Executors

/** Native API-18 shell for the measured Neusoft/Coolray head unit. */
class CoolrayActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        fun autoConnectEnabled(context: Context) =
            context.getSharedPreferences("coolray43", Context.MODE_PRIVATE).getBoolean("autoConnect", true)
    }
    private val tasks = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothTasks = Executors.newSingleThreadExecutor { action ->
        Thread(action, "coolray-bluetooth-probe").apply { isDaemon = true }
    }
    private val bluetoothProbe = BluetoothProbe(handler, bluetoothTasks, read = ::readBluetooth)
    private val diagnosticBluetoothProbe = BluetoothProbe(handler, bluetoothTasks, read = ::readBluetooth)
    private val bluetoothEnableTasks = Executors.newSingleThreadExecutor { action ->
        Thread(action, "coorlay-bluetooth-enable").apply { isDaemon = true }
    }
    private val bluetoothEnabler = BluetoothEnabler(handler, bluetoothEnableTasks,
        request = { BluetoothActivation.request(applicationContext) { log(it, display = false) } },
        report = { log(it, display = false) })
    private val oemBluetoothEnabler = BoundedAction(handler, bluetoothEnableTasks,
        request = { cancelled -> OemBluetoothControl.request(applicationContext, cancelled) { log(it, display = false) } },
        failure = { OemBluetoothControl.Result(error = it) },
        describe = OemBluetoothControl.Result::describe, label = "OEM BT ENABLE",
        report = { log(it, display = false) })
    private val hotspotTasks = Executors.newSingleThreadExecutor { action ->
        Thread(action, "coorlay-hotspot").apply { isDaemon = true }
    }
    private var hotspotSettings = HotspotSettings("coorlay", "")
    private val hotspotAction = BoundedAction(handler, hotspotTasks,
        request = { cancelled -> LegacySoftAp.start(applicationContext, hotspotSettings, cancelled) { log(it, display = false) } },
        failure = { HotspotResult(false, it) }, describe = HotspotResult::describe, label = "HOTSPOT",
        report = { log(it, display = false) })
    /** P2P state reports (sticky: the current one arrives on registration); see [WifiDirectReadiness]. */
    private val p2pEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                p2pConnectionChanged(intent)
                return
            }
            val state = intent?.getIntExtra(android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_STATE, -1)
            val enabled = when (state) {
                android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_ENABLED -> true
                android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_DISABLED -> false
                else -> null
            }
            if (WifiDirectReadiness.observed(applicationContext, prefs, enabled))
                log("WIFI-DIRECT P2P reported enabled: Wi-Fi Direct works on this head unit", display = false)
        }
    }
    /** The silent Wi-Fi Direct check runs; a wireless connection waits for it ([afterProbe]). */
    private var wifiDirectProbe = false
    private var afterProbe: (() -> Unit)? = null
    private val bluetoothEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OEM_SOURCE_CHANGED) {
                val source = intent.getStringExtra("sourceType")
                log("OEM source=$source", display = false)
                // The head unit's Bluetooth stays connected to the CarPlay iPhone; its music player may take over by
                // itself when A2DP comes up. Without a MODE press that is not the driver's choice: CarPlay stays.
                if (source == "audio_bt" && controller != null && videoActive && !driverChoseSource()) {
                    log("OEM Bluetooth music came up without MODE; CarPlay keeps the screen and sound", display = false)
                    if (spuriousTakeoverAllowed()) {
                        sink?.resumeMusic("Bluetooth music without MODE")
                        returnToCarPlay(tr("Bluetooth-музыка без MODE", "Bluetooth music without MODE"), bringToFront = true)
                    }
                    return
                }
                // Only a running CarPlay session (video shown) takes part in MODE; with coorlay open but no
                // iPhone connected, MODE stays the firmware's own radio/Bluetooth rotation.
                if (source != null && source != "CarPlay" && controller != null && videoActive && modeCycle.sourceChanged(source)) {
                    // The firmware's MODE rotation wrapped: this press belongs to CarPlay.
                    modeReturn.schedule(MODE_RETURN_DELAY_MS) {
                        returnToCarPlay(tr("MODE: очередь CarPlay после $source", "MODE: CarPlay's turn after $source"), bringToFront = true)
                    }
                }
            } else if (intent?.action == OEM_CALL_STATE) {
                // With the stock Bluetooth kept, this shows whether a call runs on the head unit's phone (HFP) or in CarPlay.
                oemCallState = intent.getIntExtra("callstate", -1)
                log("OEM call state=$oemCallState carPlay=${controller != null && videoActive} " +
                    "audio: ${oemAudioState()}", display = false)
            } else if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                log("BT STATE broadcast previous=${intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1)} " +
                    "current=${intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)}", display = false)
            }
        }
    }
    private val prefs by lazy { getSharedPreferences("coolray43", Context.MODE_PRIVATE) }
    /** «Detailed journal»: packet dumps, Wi-Fi counters, ANW SDK log. Off in releases unless the driver turns it on. */
    private val detailedLogs get() = prefs.getBoolean(DETAILED_LOGS, BuildConfig.DETAILED_LOGS_DEFAULT)
    private val journal by lazy { ConnectionJournal(filesDir) { Log.e("coorlay", "Could not write the journal", it) } }
    private val screens by lazy { SettingsScreens(this, settingsHost, journal) }
    private val settingsHost = object : SettingsScreens.Host {
        override val prefs get() = this@CoolrayActivity.prefs
        override val destroyed get() = this@CoolrayActivity.destroyed
        override val videoActive get() = this@CoolrayActivity.videoActive
        override var bluetoothSnapshot
            get() = this@CoolrayActivity.bluetoothSnapshot
            set(value) { this@CoolrayActivity.bluetoothSnapshot = value }
        override fun log(message: String, display: Boolean) = this@CoolrayActivity.log(message, display)
        override fun cancelPreflight() = this@CoolrayActivity.cancelPreflight()
        override fun stopSession() = this@CoolrayActivity.stopSession(releaseHotspot = true)
        override fun enableBluetooth() = beginBluetooth(forCarPlay = false)
        override fun probeBluetooth(done: (BluetoothSnapshot) -> Unit) = bluetoothProbe.start(callback = done)
        override fun cancelBluetoothProbe() = bluetoothProbe.cancel()
        override fun probeBluetoothForDiagnostics(done: (BluetoothSnapshot) -> Unit) = diagnosticBluetoothProbe.start(callback = done)
        override fun displaySelection() = this@CoolrayActivity.displaySelection()
        override fun recordUsbDiagnostics() = this@CoolrayActivity.recordUsbDiagnostics()
        override fun wallpaperChanged() = home.invalidate()
    }
    private lateinit var screen: SurfaceView
    private lateinit var content: FrameLayout
    private var sessionCanvas: CarPlayResolution.Size? = null
    private lateinit var home: CoorlayHomeView
    private lateinit var sessionBar: LinearLayout
    /** The coorlay home screen is shown over a running CarPlay session («Назад»). */
    private var menuOverCarPlay = false
    private var sessionWireless = false
    /** A [LegacySoftAp.stop] is queued on the hotspot worker; the next wireless start must turn the network on again. */
    private var hotspotStopping = false
    private var resumed = false
    private var autoConnect = false
    private var autoAttemptAt = 0L
    private var autoAttempts = 0
    private var autoModeWarned = false
    private lateinit var progress: ConnectionProgressView
    /** The driver pressed «Wireless»: progress is shown and failures are retried before anything is asked. */
    private var wirelessFlow = false
    /** The wireless steps in the order they really run: Wi-Fi Direct makes its network after Bluetooth (2026-10-12). */
    private var wirelessOrder = WirelessStep.values().toList()
    /** Wi-Fi Direct had worked on this head unit when the attempt began: only then is its failure retried quietly. */
    private var wifiDirectWorkedBefore = false
    /** A USB connection is shown on the progress card (pressed, or the iPhone was plugged in with coorlay open). */
    private var usbFlow = false
    private val usbNotFound = Runnable { if (usbFlow && lastUsbStatus == CarPlayStatus.WaitingForIphone) usbFailed(UsbFailure.NOT_FOUND, "no iPhone in 15 s") }
    private var lastUsbStatus: CarPlayStatus? = null
    private var wirelessRetries = 0
    private var wirelessRetryToken = 0
    private var wirelessAttemptId = 0
    private var failedAttemptId = -1
    /** Last status of the wireless session, for a progress card opened while it runs. */
    private var lastWirelessStatus: CarPlayStatus? = null
    /** The driver went to the head unit's Bluetooth to pair; coming back continues the connection. */
    private var resumeWirelessAfterPairing = false
    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoConnect || destroyed) return
            autoStep()
            handler.postDelayed(this, AUTO_TICK_MS)
        }
    }
    private val hideSessionBar = Runnable {
        if (::sessionBar.isInitialized && sessionBar.visibility == View.VISIBLE) {
            sessionBar.visibility = View.GONE
        }
    }
    private var videoActive = false
    @Volatile private var controller: CarPlayController? = null
    @Volatile private var sink: AndroidMediaSink? = null
    private var pendingVpn = false
    private var vpnWindowDeparted = false
    private var bluetoothRequest = 0
    // Written by the session worker (wired Bluetooth address) and read on the UI thread.
    @Volatile private var bluetoothSnapshot: BluetoothSnapshot? = null
    private var destroyed = false
    @Volatile private var generation = 0
    /** This connection's phone: picked by the driver or the only connected one (only that one is tried). */
    private var sessionPhone: String? = null
    /** This connection's first try when the choice timed out; the other paired phones follow. */
    private var sessionPreferredPhone: String? = null
    /** The [generation] whose start threw (its status line already says why). */
    @Volatile private var failedRun = -1
    private val usbRecovery = UsbRecoveryPolicy()
    private var usbRecoveryTask: Runnable? = null
    private var wiredRequested = false
    private var hardKeys: OemHardKeys? = null
    private val wheelKeys = OemHardKeys.Decoder()
    private val modeReturn = PendingModeReturn({ task, delay -> handler.postDelayed(task, delay) }, handler::removeCallbacks)
    private val modeCycle = ModeCycle({ SystemClock.uptimeMillis() })
    @Volatile private var clusterNavigation: com.coorlay.hud.CoolrayClusterNavigation? = null
    @Volatile private var clusterMedia: com.coorlay.hud.CoolrayClusterMedia? = null
    private val clusterTick = object : Runnable {
        override fun run() {
            val navigation = clusterNavigation
            val media = clusterMedia
            if (navigation == null && media == null) return
            tasks.execute { navigation?.tick(); media?.tick() }
            handler.postDelayed(this, 1000)
        }
    }
    @Volatile private var wlanStats: WlanStats? = null
    @Volatile private var wlanStatsLogged = false
    private val wlanTick = object : Runnable {
        override fun run() {
            val stats = wlanStats ?: return
            tasks.execute {
                stats.sample()?.let { if (wlanStatsLogged) log(it, display = false) }
                if (stats.silentSamples >= WIRELESS_SILENT_SAMPLES) runOnUiThread { wirelessSilent(stats) }
            }
            handler.postDelayed(this, WLAN_STATS_MS)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingVpn = state?.getBoolean("pendingVpn", false) ?: false
        vpnWindowDeparted = pendingVpn
        journal.loadHistory()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this)
        content = root
        root.setBackgroundColor(Color.BLACK)
        screen = SurfaceView(this)
        screen.holder.addCallback(this)
        screen.setOnTouchListener { view, event -> sendTouch(view, event); true }
        root.addView(screen, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        home = CoorlayHomeView(this, listOf(
            { connect(true) }, { connect(false) }, { screens.settingsMenu() }, ::exitPressed
        ))
        // Over a running CarPlay the menu must not let taps fall through to the phone's screen.
        home.isClickable = true
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        // Over CarPlay the head unit's Back key opens two buttons at the top edge (the OEM climate bar covers the bottom).
        sessionBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        fun barButton(label: String, danger: Boolean, action: () -> Unit) = Button(this).apply {
            text = label; textSize = 19f; isAllCaps = false; setSingleLine(true)
            setTextColor(if (danger) 0xffffaab6.toInt() else CoorlayStyle.TEXT)
            background = CoorlayStyle.clickable(this@CoolrayActivity, primary = !danger, danger = danger)
            setPadding(dp(30), dp(8), dp(30), dp(8)); minimumHeight = dp(54); minWidth = dp(220)
            setOnClickListener { action() }
        }
        sessionBar.addView(barButton("‹   " + tr("Назад", "Back"), danger = false) { showAppMenu() },
            LinearLayout.LayoutParams(-2, dp(56)))
        sessionBar.addView(barButton(tr("Отключить", "Disconnect"), danger = true) { stopAuto("disconnect"); stopSession(releaseHotspot = true); log(tr("Отключено", "Disconnected")) },
            LinearLayout.LayoutParams(-2, dp(56)).apply { leftMargin = dp(18) })
        root.addView(sessionBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
        // No status line under the cards any more (agreed 2026-10-13): the progress card speaks during a connection,
        // everything else goes to the journal (Settings → Diagnostics).
        progress = ConnectionProgressView(this)
        root.addView(progress, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // Newer test Android has a software navigation bar; the API-18 head unit
        // keeps its existing fullscreen theme and native navigation behavior.
        if (android.os.Build.VERSION.SDK_INT >= 19) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            updateVideoBounds(right - left, bottom - top)
        }
        ConnectionDiagnostics.platform(this).forEach { log(it, display = false) }
        try { registerReceiver(bluetoothEvents, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(OEM_SOURCE_CHANGED); addAction(OEM_CALL_STATE) }) }
        catch (error: Exception) { log("BT STATE receiver: ${error.javaClass.simpleName}: ${error.message}", display = false) }
        try { registerReceiver(p2pEvents, IntentFilter(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION).apply {
            addAction(android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) }) }
        catch (error: Exception) { log("P2P STATE receiver: ${error.javaClass.simpleName}: ${error.message}", display = false) }
        log(WifiDirectReadiness.describe(applicationContext, prefs) + "; mode=${if (hotspotMode(prefs)) "hotspot" else "Wi-Fi Direct"}", display = false)
        // A previous run that held Bluetooth off for wireless CarPlay and did not get to turn it on again.
        restoreBluetooth("coorlay started")
        // Wheel keys are always on: the setting was removed (user, 2026-10-12).
        hardKeys = OemHardKeys(this, report = { log(it, display = false) }) { key, action ->
            runOnUiThread { if (!destroyed) onWheelKey(key, action) }
        }.also { it.start() }
        log(tr("Готово к подключению  ·  CarPlay для вашего Coolray", "Ready to connect  ·  CarPlay for your Coolray"))
        if (!pendingVpn && intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) connect(false, manual = false)
        if (prefs.getBoolean(AUTO_WIRELESS, false)) {
            autoConnect = true
            handler.postDelayed(autoTick, AUTO_FIRST_DELAY_MS)
            log("AUTO connect enabled; first wireless check in ${AUTO_FIRST_DELAY_MS / 1000} s", display = false)
        } else log("AUTO wireless connection off; waiting for «Wireless connection»", display = false)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) moveToBackgroundForAutostart()
    }
    /** Steering-wheel keys from the Neusoft key service; every key is journaled to map further codes. */
    private fun onWheelKey(key: Int, action: Int) {
        if (key == OemHardKeys.KEY_MODE && action == OemHardKeys.ACTION_UP) {
            // The driver moved on to the next source before the delayed return ran.
            if (modeReturn.cancel()) log("MODE: delayed return to CarPlay cancelled by a new press", display = false)
            modeCycle.modePressed()
            lastModePressAt = SystemClock.uptimeMillis()
        }
        if (key == OemHardKeys.KEY_CALL) {
            onCallKey(action)
            return
        }
        val button = wheelKeys.mediaButton(key, action)
        val sent = button?.let { controller?.sendMediaButton(it) }
        log("HARDKEY key=0x${Integer.toHexString(key)} action=$action" +
            (button?.let { " -> CarPlay media=$it sent=${sent == true}" } ?: ""), display = false)
    }
    /** Last com.neusoft.geely.callstatechanged value: non-zero while the stock phone has a Bluetooth (HFP) call. */
    @Volatile private var oemCallState = 0
    /**
     * The wheel's phone key during a CarPlay call: a short press answers a ringing call and ends any other.
     * The stock Bluetooth phone gets the key as well; with no HFP call of its own it opens its screen over
     * CarPlay (user, 2026-10-21: «the call keys switch to Bluetooth»), so coorlay comes back to the front.
     * An HFP call is the stock phone's: then it alone handles the key, as before.
     */
    private fun onCallKey(action: Int) {
        val short = wheelKeys.shortPress(OemHardKeys.KEY_CALL, action)
        val active = controller
        val phase = active?.callPhase()
        if (!short || active == null || !videoActive || phase == null || oemCallState != 0) {
            log("HARDKEY key=0x${Integer.toHexString(OemHardKeys.KEY_CALL)} action=$action" +
                (if (short) " CarPlay call=${phase ?: "none"} oemCall=$oemCallState: left to the head unit" else ""), display = false)
            return
        }
        val answer = phase == com.coorlay.media.CarPlayCallState.Phase.RINGING
        val sent = if (answer) active.answerCall() else active.endCall()
        log("HARDKEY key=0x${Integer.toHexString(OemHardKeys.KEY_CALL)} action=$action call=$phase -> CarPlay " +
            "${if (answer) "answer" else "end call"} sent=$sent", display = false)
        // The stock phone opens its screen on the same release; its start takes a moment on this unit.
        for (delay in CALL_KEY_RETURN_DELAYS_MS) handler.postDelayed({
            if (!destroyed && controller === active && !resumed) bringToFront()
        }, delay)
    }
    /** Ordinary Android media keys (other firmwares, USB/Bluetooth remotes). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val active = controller
        if (active != null) {
            val button = CarPlayMediaButton.forKeyCode(event.keyCode)
            val siri = CarPlayMediaButton.opensSiri(event.keyCode)
            if (button != null || siri) {
                if (event.action == KeyEvent.ACTION_UP) {
                    val sent = if (siri) active.requestSiri() else active.sendMediaButton(button!!)
                    log("KEY ${KeyEvent.keyCodeToString(event.keyCode)} -> CarPlay sent=$sent", display = false)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // The controller owns attach/re-enumeration events during an active connection.
            // USB wins over a wireless attempt that has not shown CarPlay yet.
            if (controller != null && sessionWireless && !videoActive) {
                log("AUTO USB iPhone attached; dropping the wireless attempt", display = false)
                stopSession()
            }
            if (controller == null) connect(false, manual = false)
        }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) moveToBackgroundForAutostart()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    /** Always fitted to the screen at full load: the resolution switches were removed (agreed 2026-10-13). */
    private fun displaySelection(): CarPlayResolution.Selection {
        val fallback = android.graphics.Point()
        windowManager.defaultDisplay.getSize(fallback)
        val width = content.width.takeIf { it > 0 } ?: fallback.x
        val height = content.height.takeIf { it > 0 } ?: fallback.y
        return CarPlayResolution.select(width, height, automatic = true, reduced = false)
    }
    private fun updateVideoBounds(width: Int = content.width, height: Int = content.height) {
        if (width <= 0 || height <= 0) return
        val video = sessionCanvas ?: displaySelection().video
        val fitted = CarPlayResolution.fit(CarPlayResolution.Size(width, height), video)
        val params = screen.layoutParams as FrameLayout.LayoutParams
        if (params.width != fitted.width || params.height != fitted.height) {
            params.width = fitted.width; params.height = fitted.height; screen.layoutParams = params
        }
    }
    private fun connect(wireless: Boolean, manual: Boolean = true) {
        // A card pressed by the driver ends the automatic attempts until coorlay starts again.
        if (manual) stopAuto("manual ${if (wireless) "wireless" else "USB"} connection")
        if (menuOverCarPlay && videoActive && controller != null) {
            // The lit card is «Вернуться в CarPlay»; the other one first asks before the running CarPlay goes.
            if (wireless == sessionWireless) showCarPlayView() else confirmSwitch(wireless)
            return
        }
        // A press during an automatic wireless attempt follows that attempt instead of restarting it: on
        // 2026-10-11 a restart came 0.3 s after the iPhone had set up the picture stream.
        val running = lastWirelessStatus.let { it !is CarPlayStatus.Failed && it != CarPlayStatus.ControlEnded }
        if (wireless && manual && !wirelessFlow && !videoActive && controller != null && sessionWireless && running) {
            wirelessFlow = true
            wirelessRetries = 0
            failedAttemptId = -1
            startWirelessProgress()
            lastWirelessStatus?.let(::wirelessProgress)
            log("WIRELESS press joined the running automatic attempt", display = false)
            return
        }
        cancelUsbRecovery()
        usbRecovery.reset()
        wiredRequested = !wireless
        cancelPreflight()
        if (wireless && wifiDirectProbe) {
            // A few seconds at most; the hotspot is being put back.
            afterProbe = { connect(true, manual = false) }
            if (manual) {
                endUsbFlow()
                wirelessFlow = true
                wirelessRetries = 0
                startWirelessProgress()
                progress.note(tr("Проверка Wi-Fi магнитолы, подождите несколько секунд…", "Checking the head-unit Wi-Fi, a few seconds…"))
            }
            log("Wireless connection waits for the Wi-Fi Direct check", display = false)
            return
        }
        if (wireless) {
            endUsbFlow()
            if (manual) {
                // The driver pressed «Wireless»: show the steps, retry quietly, ask only what coorlay cannot decide.
                wirelessFlow = true
                wirelessRetries = 0
                startWirelessProgress()
            }
            wirelessAttempt()
            return
        } else {
            endWirelessFlow()
            // Also for an iPhone plugged in without a press: the card shows coorlay is on it (agreed 2026-10-13).
            startUsbProgress()
            recordUsbDiagnostics()
            try {
                val consent = CarPlayVpnService.prepare(this)
                log("VPN: ${if (consent == null) "permission already granted" else "waiting for permission"}", display = false)
                if (consent != null) {
                    pendingVpn = true
                    vpnWindowDeparted = false
                    log("VPN: asking the driver for the system permission", display = false)
                    usbStep(0, tr("Разрешите VPN в окне Android — без этого CarPlay по USB не работает",
                        "Allow VPN in the Android window — USB CarPlay does not work without it"))
                    startActivityForResult(consent, VPN_CONSENT_REQUEST)
                    return
                }
            } catch (error: Exception) {
                pendingVpn = false
                log(tr("Ошибка разрешения VPN", "VPN permission error") + ": ${error.javaClass.simpleName}: ${error.message}")
                usbFailed(UsbFailure.VPN, "VPN prepare: ${error.javaClass.simpleName}")
                return
            }
        }
        startSession(wireless)
    }
    /** The other card over a running CarPlay: switching ends it, so the driver confirms first (agreed 2026-10-13). */
    private fun confirmSwitch(wireless: Boolean) {
        val panel = CoorlayPanel(this, tr("Смена подключения", "Switch connection"))
        panel.message(if (wireless) tr("CarPlay уже работает по USB. Отключить его и подключиться без провода?",
                "CarPlay is already running over USB. Disconnect it and connect wirelessly?")
            else tr("CarPlay уже работает без провода. Отключить его и подключиться по USB?",
                "CarPlay is already running wirelessly. Disconnect it and connect over USB?"))
        panel.action(if (wireless) tr("Подключить без провода", "Connect wirelessly") else tr("Подключить по USB", "Connect over USB"),
            primary = true) {
            log("Driver switched the running CarPlay to ${if (wireless) "wireless" else "USB"}", display = false)
            stopSession(releaseHotspot = true)
            connect(wireless)
        }
        panel.show()
    }
    /** «Выход»: with CarPlay running the driver confirms first; otherwise coorlay closes at once. */
    private fun exitPressed() {
        fun exit() { stopAuto("exit"); stopSession(releaseHotspot = true); finish() }
        if (controller == null || !videoActive) { exit(); return }
        val panel = CoorlayPanel(this, tr("Выход", "Exit"))
        panel.message(tr("CarPlay будет отключён. Выйти из coorlay?", "CarPlay will be disconnected. Exit coorlay?"))
        panel.action(tr("Выйти", "Exit"), danger = true) { exit() }
        panel.show()
    }
    /**
     * One press of «Wi-Fi» runs the whole wireless chain: OEM Bluetooth/ANW, iPhone selection and the
     * CarPlay session, whose controller brings up Wi-Fi Direct itself. In hotspot mode the
     * saved coorlay SoftAP is switched on first if it is off.
     */
    /** One wireless try: the network is made up when missing, so nothing is asked before Bluetooth. */
    private fun wirelessAttempt() {
        wirelessAttemptId++
        wifiDirectWorkedBefore = WifiDirectReadiness.ready(applicationContext, prefs)
        if (hotspotMode(prefs)) HeadUnitNetwork.ensure(prefs) {
            log("Wi-Fi network created automatically: SSID=${it.ssid}", display = false)
        }
        showWirelessMode()
        beginWireless()
    }
    /** The channel the firmware chose for the Wi-Fi Direct group (the controller logs "unknownMHz" on API 18). */
    private fun logWifiDirectChannel(iface: String) {
        Thread({
            // logTool.sh writes KC2Log a moment after wpa_supplicant prints the line.
            Thread.sleep(2000)
            val mhz = runCatching { DiagnosticLogExporter.p2pGroupFrequency(iface) }.getOrNull()
            log(if (mhz == null) "Wi-Fi Direct channel: iface=$iface not found in KC2Log"
                else "Wi-Fi Direct channel: iface=$iface freq=${mhz}MHz channel=${DiagnosticLogExporter.wifiChannel(mhz)}", display = false)
        }, "coolray-p2p-channel").apply { isDaemon = true; start() }
    }
    /** The way the iPhone joins in this attempt, under the progress title. */
    private fun showWirelessMode() {
        val hotspot = hotspotMode(prefs)
        val ssid = HeadUnitNetwork.saved(prefs).ssid
        val text = if (hotspot) tr("Режим: точка доступа", "Mode: hotspot") + if (ssid.isNotEmpty()) " $ssid" else ""
            else tr("Режим: Wi-Fi Direct", "Mode: Wi-Fi Direct")
        runOnUiThread { if (!destroyed && wirelessFlow) progress.mode(text) }
    }
    private fun beginWireless() {
        val settings = HeadUnitNetwork.saved(prefs)
        // Restarted also when it runs with other values (a test network "iPhone" was left on, 2026-10-09).
        val needsSoftAp = hotspotMode(prefs) && Build.VERSION.SDK_INT <= 18 &&
            settings.isValid() && (hotspotStopping || LegacySoftAp.runsWith(applicationContext, settings) == false)
        if (!needsSoftAp) {
            // Wi-Fi Direct makes its network later, after Bluetooth: its step must not be passed yet.
            if (hotspotMode(prefs))
                progressStep(WirelessStep.WIFI, tr("Точка доступа магнитолы готова", "The head-unit hotspot is ready"))
            beginBluetooth(forCarPlay = true)
            return
        }
        hotspotSettings = settings
        val request = bluetoothRequest
        progressStep(WirelessStep.WIFI, tr("Включение точки доступа магнитолы…", "Turning on the head-unit hotspot…"))
        log(tr("Точка ${settings.ssid} выключена; включение…", "Hotspot ${settings.ssid} is off; turning it on…"))
        hotspotAction.start(25000) { result ->
            if (destroyed || request != bluetoothRequest) return@start
            log(result.describe(), display = false)
            if (!result.ready) {
                log(tr("Не удалось включить точку", "Could not turn on the hotspot") + ": ${result.error}")
                wirelessFailed(WirelessFailure.WIFI, "hotspot: ${result.error}")
                return@start
            }
            log(tr("Точка ${settings.ssid} включена", "Hotspot ${settings.ssid} is on"))
            beginBluetooth(forCarPlay = true)
        }
    }
    private fun HotspotSettings.isValid() = try { validate(); true } catch (_: Exception) { false }

    // ---- The driver's wireless flow: progress, quiet retries, and questions only when unavoidable ----

    private enum class WirelessStep { WIFI, BLUETOOTH, IPHONE, CARPLAY }
    private enum class WirelessFailure { WIFI, WIFI_DIRECT, BLUETOOTH, IPHONE }

    /** A new wireless card: the steps in the order of this mode, «Отмена» only. */
    private fun startWirelessProgress() {
        val direct = !hotspotMode(prefs)
        wirelessOrder = if (direct) listOf(WirelessStep.BLUETOOTH, WirelessStep.WIFI, WirelessStep.IPHONE, WirelessStep.CARPLAY)
            else WirelessStep.values().toList()
        val names = wirelessOrder.map {
            when (it) {
                WirelessStep.WIFI -> if (direct) "Wi-Fi Direct" else tr("Точка доступа магнитолы", "Head-unit hotspot")
                WirelessStep.BLUETOOTH -> tr("Bluetooth магнитолы", "Head-unit Bluetooth")
                WirelessStep.IPHONE -> tr("Связь с iPhone", "Reaching the iPhone")
                WirelessStep.CARPLAY -> tr("Запуск CarPlay", "Starting CarPlay")
            }
        }
        progress.start(tr("Беспроводное подключение", "Wireless connection"), names, ::cancelWireless)
        showWirelessMode()
    }

    private fun progressStep(step: WirelessStep, now: String) { if (wirelessFlow) progress.step(wirelessOrder.indexOf(step), now) }

    private fun endWirelessFlow() {
        wirelessFlow = false
        wirelessRetryToken++
        resumeWirelessAfterPairing = false
        progress.hide()
    }

    private fun cancelWireless() {
        endWirelessFlow()
        stopSession(releaseHotspot = true)
        log(tr("Подключение отменено", "Connection cancelled"))
    }

    /**
     * A failed step of the driver's connection. Most failures pass on their own (the iPhone joins the new network
     * late, Bluetooth answers slowly; the second press worked for the tester), so coorlay tries again quietly and
     * shows the problem only when the retries are spent. Several signals of one attempt count once. Wi-Fi Direct
     * that never worked here (stock firmware) fails the same way every time: shown at once.
     */
    private fun wirelessFailed(kind: WirelessFailure, reason: String) {
        if (!wirelessFlow || destroyed || failedAttemptId == wirelessAttemptId) return
        failedAttemptId = wirelessAttemptId
        log("WIRELESS attempt $wirelessAttemptId failed ($kind, retry $wirelessRetries/$WIRELESS_RETRIES): $reason", display = false)
        val retryable = kind != WirelessFailure.WIFI_DIRECT || wifiDirectWorkedBefore
        if (retryable && wirelessRetries < WIRELESS_RETRIES) {
            wirelessRetries++
            progress.note(tr("Попытка ${wirelessRetries + 1} из ${WIRELESS_RETRIES + 1}…", "Attempt ${wirelessRetries + 1} of ${WIRELESS_RETRIES + 1}…"))
            val token = ++wirelessRetryToken
            handler.postDelayed({
                if (!destroyed && wirelessFlow && token == wirelessRetryToken) { stopSession(); wirelessAttempt() }
            }, WIRELESS_RETRY_DELAY_MS)
            return
        }
        val close = tr("Закрыть", "Close") to { endWirelessFlow(); stopSession(releaseHotspot = true) }
        if (kind == WirelessFailure.WIFI_DIRECT) {
            progress.failed(tr("Wi-Fi Direct не включился на этой магнитоле. Выключите его, чтобы подключаться через точку доступа.",
                "Wi-Fi Direct did not turn on on this head unit. Turn it off to connect through the hotspot."),
                close, tr("Выключить Wi-Fi Direct", "Turn off Wi-Fi Direct") to {
                    prefs.edit().putBoolean(WIFI_DIRECT, false).apply()
                    log("Driver turned Wi-Fi Direct off after its failure; retrying over the hotspot", display = false)
                    retryWireless()
                })
            return
        }
        val message = when (kind) {
            WirelessFailure.WIFI -> tr("Сеть Wi-Fi магнитолы не включилась. Повторите; если не поможет — выключите и снова включите магнитолу.",
                "The head-unit Wi-Fi network did not turn on. Try again; if that does not help, turn the head unit off and on.")
            WirelessFailure.BLUETOOTH -> tr("Bluetooth магнитолы не включился. Повторите; если не поможет — выключите и снова включите магнитолу.",
                "The head-unit Bluetooth did not turn on. Try again; if that does not help, turn the head unit off and on.")
            else -> tr("iPhone не ответил. Проверьте на iPhone:\n• Bluetooth и Wi-Fi включены\n• телефон разблокирован\n" +
                    "• CarPlay разрешён для этой машины: Настройки → Основные → CarPlay",
                "The iPhone did not answer. Check on the iPhone:\n• Bluetooth and Wi-Fi are on\n• the phone is unlocked\n" +
                    "• CarPlay is allowed for this car: Settings → General → CarPlay")
        }
        // With several iPhones the remembered one may simply not be in the car today.
        val iphones = bluetoothSnapshot?.phones.orEmpty().filter(::looksLikeIphone)
        val other = if (kind == WirelessFailure.IPHONE && iphones.size > 1) tr("Другой iPhone", "Another iPhone") to {
            prefs.edit().remove(CHOSEN_PHONE).apply(); retryWireless()
        } else null
        progress.failed(message, *listOfNotNull(close, other, tr("Повторить", "Try again") to ::retryWireless).toTypedArray())
    }

    private fun retryWireless() {
        wirelessFlow = true
        wirelessRetries = 0
        startWirelessProgress()
        stopSession()
        wirelessAttempt()
    }

    private fun looksLikeIphone(phone: PairedPhone) = phone.label.contains("iphone", ignoreCase = true)

    /** Status lines of the session as progress steps; failures go to [wirelessFailed]. */
    private fun wirelessProgress(status: CarPlayStatus) {
        if (!wirelessFlow) return
        when (status) {
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.MfiReady, CarPlayStatus.StartingHotspot ->
                if (hotspotMode(prefs)) progressStep(WirelessStep.IPHONE, tr("Подготовка…", "Preparing…"))
                else progressStep(WirelessStep.WIFI, tr("Создание сети Wi-Fi Direct…", "Creating the Wi-Fi Direct network…"))
            CarPlayStatus.ConnectingBluetooth, CarPlayStatus.WaitingForPairedIphone ->
                progressStep(WirelessStep.IPHONE, tr("Связь с iPhone по Bluetooth… Разблокируйте iPhone; если появится вопрос о CarPlay, нажмите «Использовать CarPlay».",
                    "Reaching the iPhone over Bluetooth… Unlock the iPhone; if it asks about CarPlay, tap «Use CarPlay»."))
            CarPlayStatus.RunningWireless, CarPlayStatus.RunningControl, CarPlayStatus.WirelessActive ->
                progressStep(WirelessStep.CARPLAY, tr("iPhone подключается к Wi-Fi магнитолы и запускает CarPlay…",
                    "The iPhone is joining the head-unit Wi-Fi and starting CarPlay…"))
            // The controller wraps a Wi-Fi Direct start failure as "Could not establish WIFI_P2P hotspot: …".
            is CarPlayStatus.Failed -> wirelessFailed(
                if (!hotspotMode(prefs) && "WIFI_P2P" in status.message) WirelessFailure.WIFI_DIRECT else WirelessFailure.IPHONE,
                status.message)
            else -> Unit
        }
    }

    // ---- The USB connection on the same card (agreed 2026-10-13) ----

    private enum class UsbFailure { NOT_FOUND, VPN, START }

    private fun startUsbProgress() {
        usbFlow = true
        lastUsbStatus = null
        handler.removeCallbacks(usbNotFound)
        progress.start(tr("Подключение по USB", "USB connection"),
            listOf(tr("iPhone по USB", "iPhone over USB"), tr("Подготовка CarPlay", "Preparing CarPlay"), tr("Запуск CarPlay", "Starting CarPlay")),
            onCancel = { endUsbFlow(); stopSession(); log(tr("Подключение отменено", "Connection cancelled")) })
    }

    private fun usbStep(index: Int, now: String) { if (usbFlow) progress.step(index, now) }

    private fun endUsbFlow() {
        if (!usbFlow) return
        usbFlow = false
        handler.removeCallbacks(usbNotFound)
        progress.hide()
    }

    private fun usbFailed(kind: UsbFailure, reason: String) {
        if (!usbFlow || destroyed) return
        handler.removeCallbacks(usbNotFound)
        log("USB connection failed ($kind): $reason", display = false)
        val message = when (kind) {
            UsbFailure.NOT_FOUND -> tr("iPhone не найден на USB. Проверьте кабель и подключите iPhone заново.",
                "No iPhone found on USB. Check the cable and plug the iPhone in again.")
            UsbFailure.VPN -> tr("Без разрешения VPN CarPlay по USB не работает. Нажмите «Повторить» и разрешите VPN.",
                "USB CarPlay does not work without the VPN permission. Press «Try again» and allow VPN.")
            UsbFailure.START -> tr("CarPlay не запустился. Повторите; если не поможет — сохраните лог: Настройки → Диагностика.",
                "CarPlay did not start. Try again; if that does not help, save the log: Settings → Diagnostics.")
        }
        progress.failed(message, tr("Закрыть", "Close") to { endUsbFlow(); stopSession() },
            tr("Повторить", "Try again") to { stopSession(); connect(false) })
    }

    /** Status lines of a USB session as the three steps; the order never goes back (Pairing comes late, as a note). */
    private fun usbProgress(status: CarPlayStatus) {
        lastUsbStatus = status
        if (!usbFlow) return
        if (status != CarPlayStatus.WaitingForIphone) handler.removeCallbacks(usbNotFound)
        when (status) {
            CarPlayStatus.DiscoveringMfi, CarPlayStatus.MfiReady, CarPlayStatus.DiscoveringIphone -> usbStep(0, tr("Подготовка…", "Preparing…"))
            CarPlayStatus.WaitingForIphone -> {
                usbStep(0, tr("Ожидание iPhone… Проверьте кабель", "Waiting for the iPhone… Check the cable"))
                handler.removeCallbacks(usbNotFound); handler.postDelayed(usbNotFound, USB_NOT_FOUND_MS)
            }
            CarPlayStatus.RequestingIphonePermission -> usbStep(0, tr("Отметьте «Использовать по умолчанию» и нажмите «OK»",
                "Tick «Use by default» and press «OK»"))
            CarPlayStatus.WaitingForReenumeration -> usbStep(0, tr("iPhone переключается в режим CarPlay…", "The iPhone is switching to CarPlay…"))
            CarPlayStatus.Pairing -> progress.note(tr("На iPhone нажмите «Доверять» и введите код-пароль", "On the iPhone, tap «Trust» and enter the passcode"))
            CarPlayStatus.SelectingConfiguration, CarPlayStatus.OpeningDataPaths -> usbStep(1, tr("Подготовка USB-подключения…", "Preparing the USB connection…"))
            CarPlayStatus.ConnectingControl, CarPlayStatus.AttachingNetwork -> usbStep(1, tr("Подготовка CarPlay…", "Preparing CarPlay…"))
            CarPlayStatus.RunningControl -> usbStep(2, tr("Ожидание изображения CarPlay…", "Waiting for the CarPlay picture…"))
            else -> Unit
        }
    }

    /** A running CarPlay picture went away without the driver asking: say so on the card until it is closed. */
    private fun connectionLost(wireless: Boolean, byIphone: Boolean = false) {
        if (destroyed) return
        endWirelessFlow(); endUsbFlow()
        val close = tr("Закрыть", "Close") to { progress.hide() }
        val text = when {
            byIphone -> tr("iPhone завершил CarPlay", "The iPhone ended CarPlay")
            wireless -> tr("Связь с iPhone потеряна.", "Connection to the iPhone lost.")
            else -> tr("CarPlay по USB отключён. Проверьте кабель: когда iPhone снова подключён, CarPlay запустится сам.",
                "USB CarPlay disconnected. Check the cable: once the iPhone is connected again, CarPlay starts by itself.")
        }
        log("CarPlay lost (${if (wireless) "wireless" else "USB"}${if (byIphone) ", ended by the iPhone" else ""})", display = false)
        // USB comes back by itself when the iPhone is plugged in again; wireless needs a press.
        if (wireless) progress.message(tr("CarPlay отключён", "CarPlay disconnected"), text, close,
            tr("Подключить снова", "Connect again") to { progress.hide(); connect(true) })
        else progress.message(tr("CarPlay отключён", "CarPlay disconnected"), text, close)
    }

    private fun beginBluetooth(forCarPlay: Boolean) {
        if (forCarPlay) progressStep(WirelessStep.BLUETOOTH, tr("Включение Bluetooth магнитолы…", "Turning on the head-unit Bluetooth…"))
        cancelPreflight()
        if (OemBluetoothControl.isAvailable(applicationContext)) {
            beginOemBluetooth(bluetoothRequest, forCarPlay)
            return
        }
        log(tr("Проверка и включение Bluetooth Android…", "Checking and turning on Android Bluetooth…"))
        checkBluetooth(bluetoothRequest, SystemClock.uptimeMillis() + 15000, forCarPlay, enableRequested = false)
    }
    private fun beginOemBluetooth(request: Int, forCarPlay: Boolean) {
        log(tr("Проверка и включение штатного Bluetooth…", "Checking and turning on the built-in Bluetooth…"))
        oemBluetoothEnabler.start(timeoutMillis = 15000) oemResult@{ result ->
            if (destroyed || request != bluetoothRequest) return@oemResult
            log(result.describe(), display = false)
            if (result.enabled != true || result.state != OemBluetoothControl.STATE_ON || result.error != null) {
                log(tr("Штатный Bluetooth не подтвердил включение: ${result.error ?: "состояние ${result.state}"}. Сохраните лог.",
                    "The built-in Bluetooth did not confirm it is on: ${result.error ?: "state ${result.state}"}. Save the log."))
                if (forCarPlay) wirelessFailed(WirelessFailure.BLUETOOTH, "OEM enable: ${result.error ?: result.state}")
                return@oemResult
            }
            if (!forCarPlay) {
                log(tr("Штатный Bluetooth включён. Для сопряжения iPhone откройте «Открыть сопряжение Bluetooth».",
                    "The built-in Bluetooth is on. To pair the iPhone, open «Open Bluetooth pairing»."))
                return@oemResult
            }
            // ANW is a separate real byte transport; an OEM ON state alone is insufficient.
            bluetoothProbe.start probeResult@{ snapshot ->
                if (destroyed || request != bluetoothRequest) return@probeResult
                bluetoothSnapshot = snapshot
                log(snapshot.describe(), display = false)
                if (snapshot.state == BluetoothAdapter.STATE_ON) {
                    onAndroidBluetoothReady(snapshot, forCarPlay = true)
                } else {
                    log(tr("Беспроводное подключение", "Wireless connection") + ": ${snapshot.describe()}. " + tr("Сохраните лог.", "Save the log."))
                    wirelessFailed(WirelessFailure.BLUETOOTH, snapshot.describe())
                }
            }
        }
    }
    /** The OEM Bluetooth address for a wired session, or null if ANW does not answer within 3 s. */
    private fun wiredBluetoothAddress(): String? {
        if (!AnwBluetoothTransport.available(applicationContext)) return null
        return try {
            bluetoothTasks.submit(java.util.concurrent.Callable { readBluetooth() })
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
                .also { bluetoothSnapshot = it }
                .localAddress?.takeIf { it != NO_BLUETOOTH_ADDRESS }
        } catch (error: Exception) {
            log("Bluetooth address for wired CarPlay unavailable: ${error.javaClass.simpleName}", display = false)
            null
        }
    }
    private fun readBluetooth(): BluetoothSnapshot {
        if (!AnwBluetoothTransport.available(applicationContext)) return BluetoothSnapshot.read()
        return try {
            AnwBluetoothTransport.open(applicationContext) { log(it, display = false) }.use { client ->
                val address = client.localAddress()
                val peers = client.pairedDevices()
                val connected = runCatching { client.connectedAddresses() }.getOrDefault(emptySet())
                    .filter { address -> peers.any { it.address.equals(address, true) } }
                    .map { address -> peers.first { it.address.equals(address, true) }.address }.toSet()
                BluetoothSnapshot(client.androidState(), peers.map { PairedPhone(it.address, it.name) }, address,
                    backend = ANW_BACKEND, connected = connected)
            }
        } catch (error: Exception) {
            log("ANW probe failed: ${error.javaClass.simpleName}: ${error.message}", display = false)
            BluetoothSnapshot(null, error = error.message ?: error.javaClass.simpleName, backend = ANW_BACKEND)
        }
    }
    private fun onAndroidBluetoothReady(snapshot: BluetoothSnapshot, forCarPlay: Boolean) {
        if (!forCarPlay) {
            log(tr("Bluetooth Android включён; сопряжённых устройств: ${snapshot.phones.size}. Выберите iPhone в настройках.",
                "Android Bluetooth is on; paired devices: ${snapshot.phones.size}. Choose the iPhone in settings."))
        } else if (snapshot.phones.isEmpty()) {
            log(tr("Bluetooth готов, но нет сопряжённых телефонов: сопрягите iPhone в штатном меню Bluetooth",
                "Bluetooth is ready but no phones are paired: pair the iPhone in the head unit's Bluetooth menu"))
            // Only the driver can pair; automatic attempts in the background do not pop up screens.
            if (wirelessFlow) {
                progress.hide()
                screens.pairingStep(snapshot, opened = { resumeWirelessAfterPairing = true }, cancel = ::cancelWireless)
            }
        } else {
            // The phone in the car changes (user, 2026-10-12): a phone chosen in Settings wins; otherwise the one the head
            // unit is linked to right now when it is the only one; with several, the driver gets 10 s to pick and then
            // the phone that connected last is tried first.
            val chosen = prefs.getString(CHOSEN_PHONE, null)?.takeIf { address -> snapshot.phones.any { it.address == address } }
            val connected = snapshot.phones.filter { it.address in snapshot.connected }
            sessionPhone = null; sessionPreferredPhone = null
            when {
                chosen != null -> log("WIRELESS phone: chosen in settings", display = false)
                snapshot.phones.size == 1 -> log("WIRELESS phone: the only paired one", display = false)
                connected.size == 1 -> {
                    sessionPhone = connected.single().address
                    log("WIRELESS phone: the only connected one (${connected.single().label})", display = false)
                }
                wirelessFlow -> {
                    val last = prefs.getString(LAST_PHONE, null)?.takeIf { address -> snapshot.phones.any { it.address.equals(address, true) } }
                    val fallback = last ?: connected.firstOrNull()?.address
                        ?: snapshot.phones.firstOrNull(::looksLikeIphone)?.address ?: snapshot.phones.first().address
                    val order = snapshot.phones.sortedWith(compareByDescending<PairedPhone> { it.address in snapshot.connected }
                        .thenByDescending(::looksLikeIphone))
                    log("WIRELESS phone: asking; connected=${connected.size} paired=${snapshot.phones.size}", display = false)
                    progress.hide()
                    screens.phoneChoice(order, snapshot.connected, fallback, PHONE_CHOICE_SECONDS,
                        picked = { phone ->
                            sessionPhone = phone.address
                            log("WIRELESS phone: picked ${phone.label}", display = false)
                            progress.show()
                            continueWithPhone()
                        },
                        timedOut = {
                            sessionPreferredPhone = fallback
                            log("WIRELESS phone: no answer in ${PHONE_CHOICE_SECONDS} s; trying the last connected first", display = false)
                            progress.show()
                            continueWithPhone()
                        },
                        cancel = ::cancelWireless)
                    return
                }
                else -> sessionPreferredPhone = prefs.getString(LAST_PHONE, null)
            }
            continueWithPhone()
        }
    }
    private fun continueWithPhone() {
        progressStep(WirelessStep.IPHONE, tr("Поиск iPhone…", "Looking for the iPhone…"))
        startSession(true)
    }
    private fun checkBluetooth(request: Int, deadline: Long, forCarPlay: Boolean, enableRequested: Boolean) {
        if (destroyed || request != bluetoothRequest) return
        if (SystemClock.uptimeMillis() >= deadline) {
            log(tr("Bluetooth Android не включился за 15 с. Нажмите «Сохранить лог».", "Android Bluetooth did not turn on within 15 s. Press «Save log»."))
            if (forCarPlay) wirelessFailed(WirelessFailure.BLUETOOTH, "Android Bluetooth not ON in 15 s")
            return
        }
        bluetoothProbe.start(timeoutMillis = (deadline - SystemClock.uptimeMillis()).coerceIn(1, 5000)) { snapshot ->
            if (destroyed || request != bluetoothRequest) return@start
            bluetoothSnapshot = snapshot
            log(snapshot.describe(), display = false)
            when (snapshot.state) {
                BluetoothAdapter.STATE_ON -> onAndroidBluetoothReady(snapshot, forCarPlay)
                BluetoothAdapter.STATE_OFF, null -> {
                    if (!enableRequested) {
                        log(tr("Прямое включение Bluetooth из coorlay…", "Turning on Bluetooth directly from coorlay…"))
                        bluetoothEnabler.start(timeoutMillis = (deadline - SystemClock.uptimeMillis()).coerceIn(1, 5000)) { result ->
                            if (destroyed || request != bluetoothRequest) return@start
                            log(result.describe(), display = false)
                            if (result.accepted == true) {
                                log(tr("Запрос включения принят; ожидание Bluetooth ON…", "Request accepted; waiting for Bluetooth ON…"))
                                checkBluetooth(request, deadline, forCarPlay, enableRequested = true)
                            } else {
                                log(tr("Не удалось включить Bluetooth: ${result.error ?: "запрос отклонён"}. Нажмите «Сохранить лог».",
                                    "Could not turn on Bluetooth: ${result.error ?: "request rejected"}. Press «Save log»."))
                                if (forCarPlay) wirelessFailed(WirelessFailure.BLUETOOTH, "enable: ${result.error}")
                            }
                        }
                    } else if (snapshot.state == null) {
                        log(tr("Не удалось проверить Bluetooth после включения: ${snapshot.error}. Нажмите «Сохранить лог».",
                            "Could not check Bluetooth after turning it on: ${snapshot.error}. Press «Save log»."))
                        if (forCarPlay) wirelessFailed(WirelessFailure.BLUETOOTH, "check: ${snapshot.error}")
                    } else {
                        waitForBluetooth(request, deadline, forCarPlay, enableRequested)
                    }
                }
                BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF ->
                    waitForBluetooth(request, deadline, forCarPlay, enableRequested)
                else -> log(snapshot.describe() + ". " + tr("Откройте журнал.", "Open the journal."))
            }
        }
    }
    private fun waitForBluetooth(request: Int, deadline: Long, forCarPlay: Boolean, enableRequested: Boolean) {
        log(tr("Ожидание Bluetooth Android ON (не более 15 с)…", "Waiting for Android Bluetooth ON (up to 15 s)…"))
        handler.postDelayed({ checkBluetooth(request, deadline, forCarPlay, enableRequested) }, 1000)
    }
    private fun cancelPreflight() {
        bluetoothRequest++
        bluetoothProbe.cancel()
        bluetoothEnabler.cancel()
        oemBluetoothEnabler.cancel()
        hotspotAction.cancel()
        pendingVpn = false
        vpnWindowDeparted = false
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("pendingVpn", pendingVpn)
        super.onSaveInstanceState(state)
    }
    /** Coming back to coorlay after it was fully hidden means the driver chose CarPlay again. */
    override fun onRestart() {
        super.onRestart()
        if (sink?.musicYielded == true) returnToCarPlay("back to coorlay", bringToFront = false)
    }
    private fun returnToCarPlay(reason: String, bringToFront: Boolean) {
        if (destroyed) return
        val active = controller ?: return
        val activeSink = sink ?: return
        if (bringToFront) bringToFront()
        if (videoActive && menuOverCarPlay) showCarPlayView()
        log("AUDIO before return: ${oemAudioState()}", display = false)
        if (activeSink.resumeMusic(reason)) {
            // Untaking the main audio routes the iPhone's music back over CarPlay (not the Bluetooth
            // A2DP link the OEM player opened). If no stream arrives, resume with the Play/Pause
            // toggle: the iPhone ignores the HID Play usage (0xB0) and coorlay had paused it.
            val untaken = active.changeMainAudio(take = false)
            log("CarPlay sound returned ($reason), changeModes untake=$untaken", display = false)
            handler.postDelayed({
                if (!destroyed && controller === active && sink === activeSink && !activeSink.musicStreaming) {
                    val sent = active.sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
                    log("CarPlay music did not start after the return; Play/Pause sent=$sent", display = false)
                }
            }, RESUME_CHECK_DELAY_MS)
        } else log("CarPlay sound not returned ($reason): focus not gained or never given away", display = false)
        handler.postDelayed({ if (!destroyed) log("AUDIO after return: ${oemAudioState()}", display = false) }, AUDIO_STATE_DELAY_MS)
    }
    /**
     * Shows coorlay over the OEM radio/Bluetooth screen. Android 4.3 ignores a plain startActivity
     * from a background task, so the task is moved to front explicitly (REORDER_TASKS).
     */
    private fun bringToFront() {
        val moved = try {
            (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).moveTaskToFront(taskId, 0)
            true
        } catch (error: Exception) {
            log("moveTaskToFront: ${error.javaClass.simpleName}: ${error.message}", display = false)
            false
        }
        if (!moved) try {
            startActivity(Intent(this, CoolrayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (error: Exception) { log("Could not bring coorlay to the screen: ${error.message}", display = false) }
        log("coorlay to front: moveTaskToFront=$moved", display = false)
    }
    /** OEM source bookkeeping and the customized focus owner, to see what keeps the speakers. */
    private fun oemAudioState(): String {
        val resolver = contentResolver
        fun setting(name: String) = try { android.provider.Settings.System.getString(resolver, name) } catch (_: Exception) { "?" }
        val audio = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val focusType = try { audio.javaClass.getMethod("getActiveAudioFocusType").invoke(audio) } catch (_: Exception) { "n/a" }
        return "AllSource=${setting("AllSource")} HK_Source=${setting("HK_Source")} " +
            "focusType=$focusType musicActive=${audio.isMusicActive} carPlayYielded=${sink?.musicYielded}"
    }
    override fun onPause() {
        resumed = false
        if (pendingVpn) vpnWindowDeparted = true
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        resumed = true
        // After the USB-attach and VPN paths have had their turn; the root installer opens coorlay when it is done.
        handler.removeCallbacks(probeTick)
        handler.postDelayed(probeTick, WIFI_DIRECT_PROBE_DELAY_MS)
        // Some factory permission activities omit the result callback. Confirm the actual grant.
        if (pendingVpn && vpnWindowDeparted) {
            resumeAfterVpn()
            if (pendingVpn) {
                pendingVpn = false
                log(tr("Разрешение VPN не выдано; нажмите USB для повторной попытки", "VPN permission not granted; press USB to try again"))
                usbFailed(UsbFailure.VPN, "not granted (no result)")
            }
        }
        // Back from pairing in the head unit's Bluetooth: go on without another press.
        if (resumeWirelessAfterPairing) {
            resumeWirelessAfterPairing = false
            log("WIRELESS back from Bluetooth pairing; continuing", display = false)
            retryWireless()
        }
    }
    private val probeTick = Runnable { probeWifiDirect() }
    /** The silent Wi-Fi Direct check, only while nothing uses Wi-Fi (it switches the head-unit hotspot off briefly). */
    private fun probeWifiDirect() {
        if (destroyed || wifiDirectProbe || controller != null || wirelessFlow || videoActive || pendingVpn ||
            !WifiDirectReadiness.needsProbe(applicationContext, prefs)) return
        wifiDirectProbe = true
        log("WIFI-DIRECT silent check started", display = false)
        hotspotTasks.execute {
            val ready = try { WifiDirectReadiness.probe(applicationContext, prefs) { log(it, display = false) } }
                catch (error: Exception) { log("WIFI-DIRECT probe: ${error.javaClass.simpleName}: ${error.message}", display = false); false }
            runOnUiThread {
                wifiDirectProbe = false
                if (destroyed) return@runOnUiThread
                log("WIFI-DIRECT silent check finished ready=$ready", display = false)
                afterProbe?.also { afterProbe = null }?.invoke()
            }
        }
    }
    private fun resumeAfterVpn() {
        if (!pendingVpn) return
        try {
            if (CarPlayVpnService.prepare(this) == null) {
                pendingVpn = false
                log(tr("VPN: разрешение подтверждено", "VPN: permission confirmed"))
                startSession(false)
            }
        } catch (error: Exception) {
            pendingVpn = false
            log(tr("Не удалось проверить разрешение VPN", "Could not check the VPN permission") + ": ${error.message}")
            usbFailed(UsbFailure.VPN, "check: ${error.message}")
        }
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != VPN_CONSENT_REQUEST || !pendingVpn) return
        resumeAfterVpn()
        if (pendingVpn) {
            pendingVpn = false
            log(if (result == RESULT_OK) tr("Android не подтвердил разрешение VPN; повторите USB", "Android did not confirm the VPN permission; press USB again")
                else tr("Разрешение VPN не выдано", "VPN permission not granted"))
            usbFailed(UsbFailure.VPN, "result=$result")
        }
    }
    private fun startSession(wireless: Boolean, recoveringUsb: Boolean = false) {
        sessionWireless = wireless
        lastWirelessStatus = null
        // A USB attach intent can arrive during onCreate, before fullscreen layout.
        // Wait for the actual viewport instead of negotiating display.getSize().
        if (content.width <= 0 || content.height <= 0) {
            val pendingGeneration = generation
            content.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (destroyed || generation != pendingGeneration || (content.width > 0 && content.height > 0)) {
                        content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (!destroyed && generation == pendingGeneration) startSession(wireless, recoveringUsb)
                    }
                }
            })
            return
        }
        cancelUsbRecovery()
        if (recoveringUsb) usbRecovery.started() else usbRecovery.reset()
        wiredRequested = !wireless
        val run = ++generation
        // Snapshot on the UI thread once. Settings and subsequent window changes
        // must not change the negotiated canvas or touch coordinates mid-session.
        val display = displaySelection()
        sessionCanvas = display.video
        updateVideoBounds()
        log(display.diagnostic(), display = false)
        showHome()
        log(tr("Подготовка ${if (wireless) "Wi-Fi" else "USB"} CarPlay", "Preparing ${if (wireless) "Wi-Fi" else "USB"} CarPlay"))
        handler.postDelayed({
            // A start that already failed has said why; this hint is only for one still hanging.
            if (!destroyed && generation == run && controller == null && failedRun != run) {
                log(tr("Запуск CarPlay не завершён за 15 с. Сохраните лог для проверки последнего этапа.",
                    "CarPlay did not start within 15 s. Save the log to check the last step."))
                if (!wireless) usbFailed(UsbFailure.START, "start not finished in 15 s")
            }
        }, 15000)
        tasks.execute {
            var newSink: AndroidMediaSink? = null
            var newController: CarPlayController? = null
            try {
                val old = controller; controller = null; old?.close()
                clusterNavigation?.close(); clusterNavigation = null
                clusterMedia?.close(); clusterMedia = null
                if (old != null && !old.awaitClosed(5000)) {
                    log(tr("Завершение предыдущей сессии…", "Finishing the previous session…"))
                    if (!old.awaitClosed(15000)) throw StartRefused(tr("Предыдущая сессия не завершилась за 20 с. Сохраните лог.",
                        "The previous session did not end within 20 s. Save the log."))
                }
                sink?.close(); sink = null
                if (generation != run) return@execute
                // Verifies the actual JNI library and its errno convention without touching a device.
                log("START: JNI and MFi keys", display = false)
                check(LegacyUsbNative.transfer(-1, 0x81, ByteArray(1), 0, 1, 1) == -9)
                CoorlayBootstrap.ensure(this)
                if (!wireless) log("START: ${LegacyUsbTls.install()}", display = false)
                val identity = AirPlayPersistence.loadIdentity(this)
                val pairings = AirPlayPersistence.loadPairings(this) { id, key -> AirPlayPersistence.savePairing(this, id, key) }
                val size = display.video
                // The iPhone links the CarPlay accessory to the car's Bluetooth by this address; with
                // zeros it treats the OEM Bluetooth as a separate A2DP speaker and moves music there
                // when the OEM Bluetooth player starts (MODE). Wired CarPlay must not depend on the
                // OEM binder, so the read is bounded and zeros remain the fallback.
                val mac = bluetoothSnapshot?.localAddress?.takeIf { it != NO_BLUETOOTH_ADDRESS }
                    ?: wiredBluetoothAddress()
                    ?: NO_BLUETOOTH_ADDRESS
                log("CarPlay bluetoothIDs=${if (mac == NO_BLUETOOTH_ADDRESS) "none" else "OEM adapter"}", display = false)
                // Always on since the switch was removed (agreed 2026-10-13).
                val microphone = true
                val appName = getString(R.string.app_name)
                val airplay = AirPlayConfig(appName, CoorlayBootstrap.deviceId(identity), mac, "220.68",
                    AirPlayDisplayConfig(size.width, size.height, fps = 30), hevc = false, microphone = microphone,
                    manufacturer = appName, model = "Coolray Neusoft", oemLabel = "GEELY",
                    // CarPlay's "back to the car" icon; without it iOS shows a blank tile with no label.
                    icons = listOf(AirPlayIcon(256, 256, resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() })),
                    // No AAC next to PCM in the realtime streams: with it the iPhone ended the wireless session
                    // (tester, 2026-10-11) or played music on its own speaker (user, 2026-10-12); test switch removed.
                    detailedDiagnostics = detailedLogs,
                    mainBufferedAudio = wireless, opusAudio = wireless)
                // Checked again: the fields can be cleared in Settings while Bluetooth is prepared (2026-10-09 log).
                if (wireless && hotspotMode(prefs)) HeadUnitNetwork.ensure(prefs) {
                    log("Wi-Fi network created automatically at start: SSID=${it.ssid}", display = false)
                }
                val manual = hotspotMode(prefs)
                if (wireless) {
                    showWirelessMode()
                    log("WIRELESS mode=${if (manual) "hotspot" else "Wi-Fi Direct"}", display = false)
                }
                val passphrase = prefs.getString("password", "").orEmpty()
                val runtime = CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                    identification = Iap2IdentificationConfig(appName, "Coolray-Neusoft", appName,
                        identity.pairingId, BuildConfig.VERSION_NAME, "C3ALFUS", 1),
                    label = appName, hostName = "coorlay-coolray",
                    transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
                    wirelessHotspotMode = if (manual) WirelessHotspotMode.MANUAL else WirelessHotspotMode.WIFI_P2P,
                    manualHotspotSsid = prefs.getString("ssid", null), manualHotspotPassphrase = passphrase,
                    manualHotspotSecurity = if (passphrase.isEmpty()) ManualHotspotSecurity.OPEN else ManualHotspotSecurity.WPA2,
                    wirelessBluetoothDeviceAddress = if (wireless) sessionPhone ?: prefs.getString(CHOSEN_PHONE, null) else null,
                    wirelessBluetoothPreferredAddress = sessionPreferredPhone ?: prefs.getString(LAST_PHONE, null),
                    wiredUsbConfiguration = null,
                    detailedTransportLogs = detailedLogs)
                if (!wireless) log("USB configuration preference=${runtime.wiredUsbConfiguration ?: "auto"}", display = false)
                val mediaSink = AndroidMediaSink(this, size.width, size.height, report = { log(it) },
                    onVideoActive = { runOnUiThread { if (generation == run && !destroyed) {
                        if (wireless && !videoActive) handler.postDelayed({ if (generation == run && !destroyed) bluetoothOffForCarPlay(run, 1) }, BT_OFF_DELAY_MS)
                        videoActive = true
                        cancelUsbRecovery()
                        usbRecovery.reset()
                        // Connected: the card goes (also a «CarPlay отключён» one after USB came back by itself),
                        // CarPlay itself is the confirmation.
                        if (wireless && wirelessFlow) log("WIRELESS connected after ${wirelessRetries} quiet retries", display = false)
                        endWirelessFlow(); endUsbFlow(); progress.hide()
                        if (!menuOverCarPlay) showCarPlayView()
                        // Started in the background (autostart): CarPlay is ready, show it.
                        if (!resumed) bringToFront()
                    } } },
                    onMusicFocusLost = lost@{
                        if (!driverChoseSource() && spuriousTakeoverAllowed()) {
                            // Not a MODE press: e.g. the stock Bluetooth player when A2DP to the CarPlay iPhone comes up.
                            log("Audio focus taken without MODE (${oemAudioState()}); CarPlay music keeps playing", display = false)
                            runOnUiThread { if (!destroyed) sink?.resumeMusic("focus taken without MODE") }
                            return@lost
                        }
                        // Another head-unit source (MODE: radio, USB, Bluetooth) took the speakers.
                        val taken = controller?.changeMainAudio(take = true)
                        val sent = controller?.sendMediaButton(CarPlayMediaButton.PAUSE)
                        log("Another sound source: CarPlay music muted, changeModes take=${taken == true}, pause sent=${sent == true}", display = false)
                        runOnUiThread { if (!destroyed) log("AUDIO after loss: ${oemAudioState()}", display = false) }
                    },
                    onMusicFocusRegained = { runOnUiThread { modeCycle.carPlayActive() } })
                newSink = mediaSink
                if (screen.holder.surface.isValid) mediaSink.setSurface(screen.holder.surface)
                val media = CarPlayMediaEngine(mediaSink, microphoneEnabled = microphone,
                    videoDecryptorFactory = { key -> LegacyVideoCrypto.create(key) { log(it, display = false) } })
                log("START: creating the USB/Wi-Fi controller", display = false)
                val listener = object : AirPlaySessionListener {
                    override fun onDebugLog(message: String) {
                        if (generation == run && message.startsWith(RFCOMM_CONNECTED)) {
                            prefs.edit().putString(LAST_PHONE, message.removePrefix(RFCOMM_CONNECTED).trim()).apply()
                        }
                        if (generation == run && message.startsWith("Wi-Fi Direct group ready interface=")) {
                            val iface = message.substringAfter("interface=").substringBefore(';').trim()
                            logWifiDirectChannel(iface)
                        }
                        // Teardown timing arrives after the next start has already begun; it explains a slow start.
                        if (generation == run || " teardown " in message) log(message, display = false)
                    }
                    override fun onTransportError(message: String) {
                        runOnUiThread { if (generation == run && !destroyed) {
                            val pictureShown = videoActive
                            showHome(); log(tr("Ошибка транспорта", "Transport error") + ": $message")
                            val retrying = recoverUsb(message, run)
                            if (pictureShown) connectionLost(wireless)
                            else if (wireless) wirelessFailed(WirelessFailure.IPHONE, "transport: $message")
                            else if (!retrying) usbFailed(UsbFailure.START, "transport: $message")
                        } }
                    }
                    override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                        // Answered by turning the head-unit Bluetooth off once the picture is up ([bluetoothOffForCarPlay]).
                        if (type.equals("disableBluetooth", ignoreCase = true))
                            log("CarPlay disableBluetooth received", display = false)
                    }
                    override fun onSessionEnded(session: AirPlaySession) {
                        runOnUiThread {
                            if (generation == run) {
                                val pictureShown = videoActive
                                showHome()
                                log(tr("Сессия CarPlay завершена", "CarPlay session ended"))
                                restoreBluetooth("session ended")
                                if (pictureShown && !destroyed) connectionLost(wireless)
                                if (wireless && pictureShown && !destroyed) releaseHotspot()
                            }
                        }
                    }
                }
                val receiver = CarPlayController(this, runtime, airplay, identity, pairings, listener, media,
                    reportStatus = { current -> if (generation == run) {
                        if (current == CarPlayStatus.OpeningDataPaths || current == CarPlayStatus.RunningControl) {
                            cancelUsbRecovery()
                            usbRecovery.started()
                        }
                        log(statusLabel(current))
                        if (current is CarPlayStatus.Failed) runOnUiThread {
                            val pictureShown = videoActive
                            if (generation == run && !destroyed) showHome()
                            val retrying = recoverUsb(current.message, run)
                            if (generation == run && !destroyed) {
                                // «iAP2 link ended» is the iPhone closing the session, not a coorlay crash (tester read it as one).
                                if (pictureShown) connectionLost(wireless, byIphone = "iAP2 link ended" in current.message)
                                else if (!wireless && !retrying) usbFailed(UsbFailure.START, current.message)
                            }
                        }
                        // A failed wireless session does not recover by itself. Its teardown (tunnel, Bluetooth,
                        // Bonjour, hotspot) starts now instead of on the next press, which then found it still
                        // closing («The previous session did not end within 5 s», 2026-10-09 video).
                        if (current is CarPlayStatus.Failed && wireless) tasks.execute {
                            if (controller === newController) newController?.close()
                        }
                        if (current == CarPlayStatus.WaitingForIphone) recordUsbDiagnostics()
                        if (!wireless) runOnUiThread { if (generation == run && !destroyed) usbProgress(current) }
                        // The controller reports on the main thread.
                        if (wireless) { lastWirelessStatus = current; wirelessProgress(current) }
                    } },
                    loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
                    savePairRecord = { AirPlayPersistence.saveLockdownRecord(this, it) },
                    clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
                    wirelessBluetoothFactory = if (wireless && AnwBluetoothTransport.available(applicationContext)) {
                        { AnwBluetoothTransport.open(applicationContext, sdkLog = detailedLogs) { log(it, display = false) } }
                    } else null)
                newController = receiver
                if (generation != run) { receiver.close(); mediaSink.close(); return@execute }
                sink = mediaSink; controller = receiver
                // Cluster navigation and track are always on (switches removed, agreed 2026-10-13).
                run {
                    val navigation = com.coorlay.hud.CoolrayClusterNavigation(this) { log(it, display = false) }
                    clusterNavigation = navigation
                    receiver.routeListener = { frame -> navigation.accept(frame) }
                }
                run {
                    // While another head-unit source owns the speakers (MODE), the cluster shows that source.
                    val media = com.coorlay.hud.CoolrayClusterMedia(this, { mediaSink.musicYielded }) { log(it, display = false) }
                    clusterMedia = media
                    receiver.nowPlayingListener = { nowPlaying -> media.update(nowPlaying) }
                }
                receiver.callListener = { phase -> log("CALL CarPlay phase=${phase ?: "none"}", display = false) }
                if (clusterNavigation != null || clusterMedia != null) {
                    runOnUiThread { handler.removeCallbacks(clusterTick); handler.postDelayed(clusterTick, 1000) }
                }
                // Always sampled in wireless sessions (the silence check needs it); journaled only with detailed logs.
                if (wireless) {
                    wlanStatsLogged = detailedLogs
                    wlanStats = WlanStats(groupOnly = !manual)
                    runOnUiThread { handler.removeCallbacks(wlanTick); handler.postDelayed(wlanTick, WLAN_STATS_MS) }
                }
                log("START: starting the controller", display = false)
                receiver.start()
            } catch (error: Throwable) {
                newController?.close(); newSink?.close()
                if (controller === newController) controller = null
                if (sink === newSink) sink = null
                if (generation == run) {
                    failedRun = run
                    runOnUiThread { if (generation == run && !destroyed) {
                        showHome()
                        if (wireless) wirelessFailed(WirelessFailure.IPHONE, "start: ${error.javaClass.simpleName}: ${error.message}")
                        else usbFailed(UsbFailure.START, "start: ${error.javaClass.simpleName}: ${error.message}")
                    } }
                    log(if (error is StartRefused) error.message.orEmpty()
                        else tr("Запуск не удался", "Start failed") + ": ${error.javaClass.simpleName}: ${error.message}")
                    log(Log.getStackTraceString(error), display = false)
                }
            }
        }
    }
    /** When the driver last pressed MODE (uptime ms). */
    private var lastModePressAt = 0L
    private val spuriousTakeovers = ArrayDeque<Long>()
    private fun driverChoseSource() = SystemClock.uptimeMillis() - lastModePressAt < MODE_CHOICE_WINDOW_MS
    /** At most a few take-backs a minute, so CarPlay and a stubborn player cannot bounce the speakers forever. */
    private fun spuriousTakeoverAllowed(): Boolean = synchronized(spuriousTakeovers) {
        val now = SystemClock.uptimeMillis()
        while (spuriousTakeovers.isNotEmpty() && now - spuriousTakeovers.first() > 60_000L) spuriousTakeovers.removeFirst()
        if (spuriousTakeovers.size >= 3) { log("Take-back limit reached; leaving the speakers to the other source", display = false); return false }
        spuriousTakeovers.addLast(now); true
    }
    /**
     * Wireless CarPlay runs over Wi-Fi once its picture is up; the head unit's Bluetooth is then turned off until the
     * session ends (user's choice, 2026-10-13). With it on, the firmware's auto-connect paged an absent paired phone for
     * 10 s every 30 s, and on the shared 2.4 GHz chip every page stalled CarPlay (2026-10-12); the iPhone also asks the
     * car to let Bluetooth go (disableBluetooth). The firmware refuses during a call: two more tries, 10 s apart.
     */
    private fun bluetoothOffForCarPlay(run: Int, attempt: Int) {
        if (!OemBluetoothControl.isAvailable(applicationContext)) return
        // Stored first: a crash before the restore must not leave Bluetooth off for good.
        prefs.edit().putBoolean(BT_OFF_BY_COORLAY, true).commit()
        // The same single worker as the Bluetooth enable before a connection: the two never overlap.
        bluetoothEnableTasks.execute {
            val result = OemBluetoothControl.request(applicationContext, enable = false) { log(it, display = false) }
            val off = result.error == null && (result.enabled == false || result.state == OemBluetoothControl.STATE_OFF)
            runOnUiThread {
                val sessionGone = destroyed || generation != run || !videoActive
                when {
                    off && sessionGone -> { log("Bluetooth off came after the session ended; turning it on again", display = false); restoreBluetooth("session already over") }
                    off -> log("Bluetooth: head-unit Bluetooth off for wireless CarPlay (back on when CarPlay ends)", display = false)
                    else -> {
                        prefs.edit().putBoolean(BT_OFF_BY_COORLAY, false).apply()
                        log("Bluetooth: could not turn the head-unit Bluetooth off (attempt $attempt): ${result.error ?: result.describe()}", display = false)
                        if (attempt < 3 && !sessionGone) handler.postDelayed({ if (generation == run && !destroyed) bluetoothOffForCarPlay(run, attempt + 1) }, 10_000L)
                    }
                }
            }
        }
    }
    /** Turns the head-unit Bluetooth back on if coorlay turned it off; also on the next start after a crash. */
    private fun restoreBluetooth(reason: String) {
        if (!prefs.getBoolean(BT_OFF_BY_COORLAY, false)) return
        prefs.edit().putBoolean(BT_OFF_BY_COORLAY, false).commit()
        log("Bluetooth: turning the head-unit Bluetooth on again ($reason)", display = false)
        val restore = Runnable {
            val result = OemBluetoothControl.request(applicationContext, enable = true) { log(it, display = false) }
            log("Bluetooth: back on=${result.error == null && result.state == OemBluetoothControl.STATE_ON} ${result.error ?: ""}".trim(), display = false)
            if (result.error != null) prefs.edit().putBoolean(BT_OFF_BY_COORLAY, true).apply() // tried again next time
        }
        // onDestroy shuts the worker down right after stopSession: a closing coorlay restores on its own thread.
        if (destroyed) Thread(restore, "coorlay-bt-on").apply { isDaemon = true; start() } else bluetoothEnableTasks.execute(restore)
    }
    private fun sendTouch(view: View, event: MotionEvent) {
        // Every pointer goes along: the session keeps each finger in its slot (a pinch whose first finger
        // lifted moved the second into slot 0 and released slot 1 at the corner, DiPlay #912).
        controller?.sendTouch(CarPlayTouchMapper.contacts(event, view.width, view.height))
    }
    private fun statusLabel(value: CarPlayStatus): String = when (value) {
        CarPlayStatus.WaitingForIphone -> tr("Android не видит USB-устройство Apple. Переподключите USB и откройте журнал.",
            "Android does not see an Apple USB device. Reconnect USB and open the journal.")
        CarPlayStatus.RequestingIphonePermission -> tr("USB: отметьте «Использовать по умолчанию» и разрешите доступ",
            "USB: tick «Use by default» and allow access")
        CarPlayStatus.Pairing -> tr("Сопряжение с iPhone; подтвердите доверие на телефоне", "Pairing with the iPhone; tap «Trust» on the phone")
        CarPlayStatus.WaitingForReenumeration -> tr("Ожидание переключения iPhone в CarPlay", "Waiting for the iPhone to switch to CarPlay")
        CarPlayStatus.SelectingConfiguration, CarPlayStatus.OpeningDataPaths -> tr("Подготовка USB-подключения", "Preparing the USB connection")
        CarPlayStatus.DiscoveringMfi, CarPlayStatus.MfiReady -> tr("Подготовка CarPlay", "Preparing CarPlay")
        CarPlayStatus.StartingHotspot -> tr("Подготовка Wi-Fi", "Preparing Wi-Fi")
        CarPlayStatus.WaitingForPairedIphone -> tr("Выберите сопряжённый iPhone в настройках", "Choose a paired iPhone in settings")
        CarPlayStatus.ConnectingBluetooth -> tr("Подключение к iPhone через Bluetooth", "Connecting to the iPhone over Bluetooth")
        CarPlayStatus.RunningWireless, CarPlayStatus.RunningControl -> tr("Ожидание изображения CarPlay", "Waiting for the CarPlay picture")
        CarPlayStatus.WirelessActive -> tr("CarPlay подключён", "CarPlay connected")
        CarPlayStatus.ControlEnded -> tr("Соединение завершено", "Connection ended")
        // «iAP2 link ended» is the iPhone closing the session, not a coorlay crash (tester read it as one).
        is CarPlayStatus.Failed -> if (value.message.contains("iAP2 link ended")) tr("iPhone завершил CarPlay", "The iPhone ended CarPlay")
            else tr("Ошибка CarPlay", "CarPlay error") + ": ${value.message}"
        else -> tr("Подключение CarPlay…", "Connecting CarPlay…")
    }
    override fun surfaceCreated(holder: SurfaceHolder) { sink?.setSurface(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { sink?.setSurface(holder.surface) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { sink?.setSurface(null) }
    override fun onBackPressed() {
        if (progress.showing) {
            // The card's first button: «Отмена» while connecting, «Закрыть» after a failure or a lost CarPlay.
            progress.back()
        } else if (menuOverCarPlay && videoActive) {
            showCarPlayView()
        } else if (videoActive) {
            toggleSessionBar()
        } else if (controller != null || pendingVpn) {
            stopSession(releaseHotspot = true); log(tr("Отключено", "Disconnected"))
        } else super.onBackPressed()
    }
    private fun showHome() {
        videoActive = false
        menuOverCarPlay = false
        handler.removeCallbacks(hideSessionBar)
        home.visibility = View.VISIBLE; sessionBar.visibility = View.GONE
        home.carPlayRunning(null)
    }
    /** CarPlay full screen; Back opens the two buttons. */
    private fun showCarPlayView() {
        menuOverCarPlay = false
        home.visibility = View.GONE; sessionBar.visibility = View.GONE
    }
    /** «Назад»: the coorlay home screen while CarPlay keeps running underneath. */
    private fun showAppMenu() {
        menuOverCarPlay = true
        handler.removeCallbacks(hideSessionBar)
        sessionBar.visibility = View.GONE
        home.visibility = View.VISIBLE
        home.carPlayRunning(sessionWireless)
        log("CarPlay keeps running under the coorlay menu", display = false)
    }
    /**
     * Automatic connection: a USB iPhone is handled by its attach intent; otherwise a wireless attempt through the
     * hotspot every [AUTO_ATTEMPT_MS] while no CarPlay picture is shown. USB sessions are never interrupted.
     */
    private fun autoStep() {
        if (!autoConnect || destroyed || videoActive || pendingVpn || wifiDirectProbe) return
        // A USB session (or its reconnect polling) keeps the phone only while an iPhone is on the USB bus.
        val usbSession = wiredRequested || (controller != null && !sessionWireless)
        if (usbSession && appleUsbPresent()) return
        val now = SystemClock.uptimeMillis()
        if (autoAttemptAt != 0L && now - autoAttemptAt < AUTO_ATTEMPT_MS) return
        // Wi-Fi Direct needs the root fix of p2p_supplicant.conf; without it every attempt would switch the
        // head-unit hotspot off and on again. Automatic attempts start once Wi-Fi Direct was seen working.
        if (!hotspotMode(prefs) && !WifiDirectReadiness.ready(applicationContext, prefs)) {
            if (!autoModeWarned) { autoModeWarned = true; log("AUTO wireless skipped: Wi-Fi Direct has not worked on this head unit yet", display = false) }
            return
        }
        autoAttemptAt = now
        autoAttempts++
        log("AUTO wireless attempt $autoAttempts", display = false)
        if (controller != null) stopSession()
        connect(true, manual = false)
    }
    private fun appleUsbPresent(): Boolean = try {
        (getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.any { it.vendorId == 0x05ac }
    } catch (_: Exception) { false }
    private fun stopAuto(reason: String) {
        if (!autoConnect) return
        autoConnect = false
        handler.removeCallbacks(autoTick)
        log("AUTO connect stopped: $reason", display = false)
    }
    /** Autostart with the head unit: stay behind the OEM screens until CarPlay shows a picture. */
    private fun moveToBackgroundForAutostart() {
        handler.postDelayed({
            if (!destroyed && !videoActive) {
                val moved = moveTaskToBack(true)
                log("AUTO started with the head unit; coorlay waits in the background moved=$moved", display = false)
            }
        }, AUTOSTART_BACKGROUND_DELAY_MS)
    }
    private fun toggleSessionBar() {
        handler.removeCallbacks(hideSessionBar)
        val show = sessionBar.visibility != View.VISIBLE
        sessionBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) handler.postDelayed(hideSessionBar, SESSION_BAR_HIDE_MS)
    }
    /** The iPhone went silent on Wi-Fi with the picture shown: end the frozen session; automatic attempts go on. */
    private fun wirelessSilent(stats: WlanStats) {
        if (destroyed || wlanStats !== stats || !sessionWireless || !videoActive) return
        wirelessGone("WLAN: nothing received for ${stats.silentSamples * WLAN_STATS_MS / 1000} s")
    }
    /** The Wi-Fi Direct session ([WlanStats] instance) in which the group last had a client. */
    private var p2pClientSeen: WlanStats? = null
    /**
     * Wi-Fi Direct reports every client that joins or leaves the group. The iPhone switched its Wi-Fi off at 07:39:21
     * (tester, 2026-10-14; wpa_supplicant AP-STA-DISCONNECTED) and the picture stayed frozen until a force stop.
     * Only a group that had a client in this session counts, so a report without the client list ends nothing.
     */
    private fun p2pConnectionChanged(intent: Intent) {
        val stats = wlanStats ?: return
        if (!stats.groupOnly || !sessionWireless) return
        val group = intent.getParcelableExtra<android.net.wifi.p2p.WifiP2pGroup>(
            android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
        val connected = intent.getParcelableExtra<android.net.NetworkInfo>(
            android.net.wifi.p2p.WifiP2pManager.EXTRA_NETWORK_INFO)?.isConnected
        val clients = group?.clientList?.size ?: 0
        log("WIFI-DIRECT group ${group?.`interface`} clients=$clients connected=$connected video=$videoActive", display = false)
        if (clients > 0) { p2pClientSeen = stats; return }
        if (p2pClientSeen === stats && videoActive && !destroyed) wirelessGone("WIFI-DIRECT: the iPhone left the group")
    }
    private fun wirelessGone(reason: String) {
        log("$reason; ending the wireless session", display = false)
        stopSession(releaseHotspot = true)
        log(tr("iPhone перестал отвечать по Wi-Fi; CarPlay отключён", "The iPhone stopped answering over Wi-Fi; CarPlay disconnected"))
        connectionLost(wireless = true)
    }
    /** [releaseHotspot]: the driver or the iPhone ended the wireless session (not a restart or retry). */
    private fun stopSession(releaseHotspot: Boolean = false) {
        restoreBluetooth("session stopped")
        if (releaseHotspot && sessionWireless) releaseHotspot()
        wiredRequested = false
        modeReturn.cancel()
        cancelUsbRecovery()
        usbRecovery.reset()
        cancelPreflight()
        generation++
        sessionCanvas = null
        if (!destroyed) updateVideoBounds()
        handler.removeCallbacks(clusterTick)
        handler.removeCallbacks(wlanTick); wlanStats = null
        val navigation = clusterNavigation; clusterNavigation = null
        val media = clusterMedia; clusterMedia = null
        tasks.execute {
            val old = controller; controller = null; old?.close(); old?.awaitClosed(5000)
            sink?.close(); sink = null
            navigation?.close()
            media?.close()
        }
        showHome()
    }
    /** The coorlay SoftAP goes off between wireless sessions; see [LegacySoftAp.stop]. */
    private fun releaseHotspot() {
        // Always (the switch was removed, agreed 2026-10-13): otherwise the iPhone may join it as ordinary Wi-Fi without internet.
        if (!hotspotMode(prefs) || Build.VERSION.SDK_INT > 18) return
        val settings = HeadUnitNetwork.saved(prefs)
        if (!settings.isValid() || hotspotStopping) return
        hotspotStopping = true
        try {
            hotspotTasks.execute {
                try { LegacySoftAp.stop(applicationContext, settings) { log(it, display = false) } }
                catch (error: Exception) { log("HOTSPOT stop failed ${error.javaClass.simpleName}: ${error.message}", display = false) }
                finally { handler.post { hotspotStopping = false } }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { hotspotStopping = false }
    }
    private fun cancelUsbRecovery() {
        usbRecoveryTask?.let(handler::removeCallbacks)
        usbRecoveryTask = null
    }
    /** True when a quiet reconnect was scheduled (the driver is not asked then). */
    private fun recoverUsb(message: String, run: Int): Boolean {
        if (destroyed || !wiredRequested || generation != run) return false
        val delay = usbRecovery.request(message) ?: return false
        log("USB: transient failure; reconnecting in ${delay / 1000} s", display = false)
        val task = Runnable {
            usbRecoveryTask = null
            if (!destroyed && wiredRequested && generation == run) {
                log(tr("USB: автоматическое восстановление подключения", "USB: restoring the connection automatically"))
                startSession(false, recoveringUsb = true)
            }
        }
        usbRecoveryTask = task
        handler.postDelayed(task, delay)
        return true
    }
    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(autoTick); handler.removeCallbacks(probeTick)
        stopSession(); diagnosticBluetoothProbe.cancel()
        hardKeys?.close(); hardKeys = null
        try { unregisterReceiver(bluetoothEvents) } catch (_: Exception) { }
        try { unregisterReceiver(p2pEvents) } catch (_: Exception) { }
        screens.close(); journal.close()
        tasks.shutdown(); bluetoothTasks.shutdownNow(); bluetoothEnableTasks.shutdownNow(); hotspotTasks.shutdown(); super.onDestroy()
    }
    private fun recordUsbDiagnostics() {
        if (!detailedLogs) return
        journal.execute { ConnectionDiagnostics.usb(applicationContext).forEach { log(it, display = false) } }
    }
    /**
     * [display] marks a line written for the driver. The status line that showed them was removed (agreed 2026-10-13);
     * the driver now sees the progress card, and every line goes to the journal.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun log(message: String, display: Boolean = true) {
        if (!com.coorlay.orchestration.TransportLogPolicy.keep(message, detailedLogs)) return
        val safe = DiagnosticLogExporter.sanitize(message)
        journal.add(safe)
        Log.i("coorlay", safe)
    }
}

/** Preference of the «Detailed journal» switch (named when it covered USB only; kept for existing settings). */
internal const val DETAILED_LOGS = "usbDetailedLogs"
/** The paired phone whose wireless RFCOMM connected last; tried first when none is chosen. */
internal const val LAST_PHONE = "lastWirelessPhone"
/** How long the phone choice waits before the phone that connected last is used. */
private const val PHONE_CHOICE_SECONDS = 10
/** The controller's line once a phone's iAP2 RFCOMM link is up (raw address, before the journal sanitizes it). */
private const val RFCOMM_CONNECTED = "wireless RFCOMM connected address="

/** Neusoft broadcast sent whenever radio, USB, Bluetooth or another head-unit source becomes active. */
private const val OEM_SOURCE_CHANGED = "com.neusoft.source_medium_changed"
/** Neusoft broadcast of the head unit's own phone (HFP) call state: 1 in a call, 0 idle. */
private const val OEM_CALL_STATE = "com.neusoft.geely.callstatechanged"

/** A start stopped for a reason the driver can read as is (no exception class in the status line). */
private class StartRefused(message: String) : Exception(message)

private const val VPN_CONSENT_REQUEST = 1
/** [BluetoothSnapshot.backend] of the Neusoft ANW stack; settings open the OEM pairing screen for it. */
private const val ANW_BACKEND = "ANW (OEM)"
/** bluetoothIDs value when the OEM adapter address is unknown; the iPhone then treats OEM Bluetooth as separate. */
private const val NO_BLUETOOTH_ADDRESS = "00:00:00:00:00:00"
/** After MODE wraps to CarPlay: let the OEM player take focus first, then take it back so it stops. */
private const val MODE_RETURN_DELAY_MS = 1200L
private const val WLAN_STATS_MS = 5000L
/** Quiet retries of the driver's wireless connection before the problem is shown, and the pause between them. */
private const val WIRELESS_RETRIES = 2
private const val WIRELESS_RETRY_DELAY_MS = 3000L
/** A USB connection still waiting for an Apple device this long tells the driver to check the cable. */
private const val USB_NOT_FOUND_MS = 15_000L
/** Two silent [WLAN_STATS_MS] samples (10 s) end a wireless session whose picture is shown. */
private const val WIRELESS_SILENT_SAMPLES = 2
/** Automatic connection: first wireless check after start (OEM Bluetooth needs time after boot), check period, retry spacing. */
private const val AUTO_FIRST_DELAY_MS = 15_000L
private const val WIFI_DIRECT_PROBE_DELAY_MS = 3000L
private const val AUTO_TICK_MS = 30_000L
private const val AUTO_ATTEMPT_MS = 60_000L
private const val AUTOSTART_BACKGROUND_DELAY_MS = 500L
/** The two buttons over CarPlay hide themselves after this long. */
private const val SESSION_BAR_HIDE_MS = 6000L
/** After the wireless CarPlay picture is up, the head-unit Bluetooth is turned off this much later. */
private const val BT_OFF_DELAY_MS = 3000L
/** Set while coorlay holds the head-unit Bluetooth off for a wireless session; cleared when it is back on. */
internal const val BT_OFF_BY_COORLAY = "bluetoothOffByCoorlay"
/** A source change or focus loss this soon after a MODE press is the driver's own choice. */
private const val MODE_CHOICE_WINDOW_MS = 10_000L
/** After the wheel's phone key in a CarPlay call: when to check that the stock phone screen did not stay on top. */
private val CALL_KEY_RETURN_DELAYS_MS = longArrayOf(700L, 2000L)
/** After returning to CarPlay: no music stream by then means the iPhone stayed paused. */
private const val RESUME_CHECK_DELAY_MS = 2500L
/** Whether the OEM player actually yielded only shows a moment after the return. */
private const val AUDIO_STATE_DELAY_MS = 2000L

