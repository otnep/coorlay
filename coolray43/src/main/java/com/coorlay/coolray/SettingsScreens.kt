package com.coorlay.coolray

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.coorlay.UiText.tr
import java.io.File
import java.util.concurrent.Executors

/**
 * Settings, journal and log-export screens of coorlay. The session itself stays in
 * [CoolrayActivity]; these panels reach it only through [Host].
 */
internal class SettingsScreens(private val activity: Activity, private val host: Host, private val journal: ConnectionJournal) {
    /** What the screens need from the running activity. */
    interface Host {
        val prefs: SharedPreferences
        val destroyed: Boolean
        val videoActive: Boolean
        var bluetoothSnapshot: BluetoothSnapshot?
        fun log(message: String, display: Boolean = true)
        /** Cancels Bluetooth/hotspot preparation still running for a connection. */
        fun cancelPreflight()
        fun stopSession()
        /** Switches Bluetooth on without starting CarPlay. */
        fun enableBluetooth()
        fun probeBluetooth(done: (BluetoothSnapshot) -> Unit)
        fun cancelBluetoothProbe()
        /** A separate probe, so a journal refresh or log export does not cancel a connection's probe. */
        fun probeBluetoothForDiagnostics(done: (BluetoothSnapshot) -> Unit)
        fun displaySelection(): CarPlayResolution.Selection
        fun recordUsbDiagnostics()
        /** The background picture changed; redraw the home screen. */
        fun wallpaperChanged()
    }

    // Off the journal worker: a 3 MB export must not delay journal writes or the folder picker.
    private val exportTasks = Executors.newSingleThreadExecutor()
    private val storageTasks = Executors.newSingleThreadExecutor()
    @Volatile private var exportRunning = false

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    fun close() {
        exportTasks.shutdown(); storageTasks.shutdownNow()
    }

    private fun openBluetoothPairing(snapshot: BluetoothSnapshot) {
        if (snapshot.backend.startsWith("ANW")) try {
            val native = activity.packageManager.getLaunchIntentForPackage(OemBluetoothDiagnostics.UI_PACKAGE)
                ?: activity.packageManager.getLaunchIntentForPackage(OemBluetoothDiagnostics.PACKAGE)
                ?: throw IllegalStateException(tr("Штатный Bluetooth не предоставляет отдельный экран приложению", "The built-in Bluetooth has no separate screen for apps"))
            host.log("OEM Bluetooth opening ${native.component}", display = false)
            activity.startActivity(native)
        } catch (error: Exception) { host.log(tr("Штатный Bluetooth: ${error.message}. Сохраните лог.", "Built-in Bluetooth: ${error.message}. Save the log.")) }
        else if (snapshot.state != BluetoothAdapter.STATE_ON) host.enableBluetooth()
        else try {
            host.log(tr("Открытие списка Bluetooth Android для сопряжения iPhone", "Opening the Android Bluetooth list to pair the iPhone"), display = false)
            activity.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (error: Exception) { host.log(tr("Не удалось открыть Bluetooth", "Could not open Bluetooth") + ": ${error.javaClass.simpleName}: ${error.message}") }
    }
    /** The settings sections, in menu order (2 × 2, agreed 2026-10-13). */
    private enum class Section(private val ruTitle: String, private val enTitle: String,
        private val ruDescription: String, private val enDescription: String, val symbol: Int) {
        CONNECTION("Подключение", "Connection", "Автозапуск, автоподключение и сеть Wi-Fi",
            "Autostart, automatic connection and Wi-Fi network", 2),
        PHONE("Bluetooth", "Bluetooth", "Выбор телефона и сопряжение", "Phone choice and pairing", 3),
        BACKGROUND("Фон", "Background", "Картинка главного экрана и меню", "Picture for the home screen and menus", 0),
        DIAGNOSTICS("Диагностика", "Diagnostics", "Журнал, сохранение лога и проверки", "Journal, log saving and checks", 4);

        val title get() = tr(ruTitle, enTitle)
        val description get() = tr(ruDescription, enDescription)
    }

    fun settingsMenu() {
        val panel = CoorlayPanel(activity, tr("Настройки", "Settings"), tr("Выберите раздел", "Choose a section"))
        Section.values().toList().chunked(2).forEach { sections ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            sections.forEachIndexed { index, section ->
                val card = panel.card(section.title, section.description, section.symbol) { open(section) }
                row.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index > 0) leftMargin = dp(14) })
            }
            panel.body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
        panel.action(tr("Сохранить лог", "Save log")) { chooseLogDirectory() }
        // Only while there is something to disconnect.
        if (host.videoActive) panel.action(tr("Отключить CarPlay", "Disconnect CarPlay"), danger = true) {
            host.stopSession(); host.log(tr("Отключено", "Disconnected"))
        }
        panel.show()
    }

    private fun open(section: Section) {
        val panel = CoorlayPanel(activity, section.title, section.description)
        panel.backTo { settingsMenu() }
        when (section) {
            Section.CONNECTION -> connectionSettings(panel)
            Section.PHONE -> phoneSettings(panel)
            Section.BACKGROUND -> backgroundSettings(panel)
            Section.DIAGNOSTICS -> diagnosticsSettings(panel)
        }
        panel.show()
    }

    /**
     * Wireless step the driver must do: no phone is paired. [opened] runs when the head unit's Bluetooth screen
     * opens, so the connection goes on by itself when the driver comes back to coorlay.
     */
    fun pairingStep(snapshot: BluetoothSnapshot, opened: () -> Unit, cancel: () -> Unit) {
        val panel = CoorlayPanel(activity, tr("Сопряжение iPhone", "Pair the iPhone"),
            tr("Один раз: после этого iPhone подключается сам", "Once: after that the iPhone connects by itself"))
        panel.backTo(cancel)
        val steps = panel.section(tr("Что сделать", "What to do"))
        panel.note(steps, tr("1. На iPhone включите Bluetooth и Wi-Fi.", "1. On the iPhone, turn on Bluetooth and Wi-Fi."))
        panel.note(steps, tr("2. Нажмите «Открыть Bluetooth» и найдите свой iPhone.", "2. Press «Open Bluetooth» and find your iPhone."))
        panel.note(steps, tr("3. Подтвердите одинаковый код на обоих экранах и вернитесь в coorlay.",
            "3. Confirm the same code on both screens and come back to coorlay."))
        panel.action(tr("Открыть Bluetooth", "Open Bluetooth"), primary = true) { opened(); openBluetoothPairing(snapshot) }
        panel.show()
    }

    /**
     * Several phones could be meant: the driver picks one for this connection within [seconds], otherwise [fallback]
     * (the phone that connected last) is used. Phones linked to the head unit right now come first and say so.
     */
    fun phoneChoice(phones: List<PairedPhone>, connected: Set<String>, fallback: String, seconds: Int,
        picked: (PairedPhone) -> Unit, timedOut: () -> Unit, cancel: () -> Unit) {
        val fallbackLabel = phones.firstOrNull { it.address.equals(fallback, true) }?.label.orEmpty()
        val panel = CoorlayPanel(activity, tr("Какой телефон подключить?", "Which phone to connect?"), "")
        var done = false
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        fun finish(action: () -> Unit) { if (!done) { done = true; handler.removeCallbacksAndMessages(null); panel.close(); action() } }
        panel.backTo { finish(cancel) }
        val list = panel.section(tr("Сопряжённые телефоны", "Paired phones"))
        val countdown = panel.label("", 17f)
        list.addView(countdown)
        phones.forEach { phone ->
            val state = if (phone.address in connected) tr("подключён к магнитоле", "connected to the head unit") else ""
            panel.choice(list, phone.label, state, phone.address.equals(fallback, true), markText = tr("последний", "last")) {
                finish { picked(phone) }
            }
        }
        var left = seconds
        val tick = object : Runnable {
            override fun run() {
                if (done) return
                if (left <= 0) { finish(timedOut); return }
                countdown.text = tr("Через $left с подключится $fallbackLabel", "In $left s $fallbackLabel connects")
                left--
                handler.postDelayed(this, 1000)
            }
        }
        panel.dialog.setOnDismissListener { if (!done) { done = true; handler.removeCallbacksAndMessages(null); cancel() } }
        panel.show()
        tick.run()
    }

    /** A switch stored in preferences as soon as it changes. */
    private fun setting(panel: CoorlayPanel, card: LinearLayout, key: String, default: Boolean, title: String,
        description: String, changed: (Boolean) -> Unit = {}) =
        panel.toggle(card, title, description, host.prefs.getBoolean(key, default)).apply {
            setOnCheckedChangeListener { _, checked ->
                host.prefs.edit().putBoolean(key, checked).apply()
                host.log(tr("Настройка «$title»: ${if (checked) "включено" else "выключено"}", "Setting «$title»: ${if (checked) "on" else "off"}"), display = false)
                changed(checked)
            }
        }

    private fun backgroundSettings(panel: CoorlayPanel) {
        val background = panel.section(tr("Фон главного экрана", "Home screen background"))
        // Only a chosen picture is mentioned; the standard background says nothing (user, 2026-10-13).
        if (CoorlayWallpaper.isCustom(activity)) panel.note(background, tr("Сейчас: своя картинка", "Now: your own picture"))
        if (CoorlayWallpaper.isCustom(activity)) panel.buttons(background,
            tr("Выбрать картинку", "Choose a picture") to { panel.close(); chooseWallpaper() },
            tr("Стандартный фон", "Standard background") to { panel.close(); resetWallpaper(); open(Section.BACKGROUND) })
        else panel.buttons(background, tr("Выбрать картинку", "Choose a picture") to { panel.close(); chooseWallpaper() })
    }

    /** Bluetooth and the paired phones are read first; the section is shown at once and filled in when they arrive. */
    private fun phoneSettings(panel: CoorlayPanel) {
        host.cancelPreflight()
        val radio = panel.section(tr("Bluetooth магнитолы", "Head-unit Bluetooth"))
        val state = panel.label(tr("Чтение состояния…", "Reading the state…"), 16f).apply { setLineSpacing(0f, 1.1f) }
        radio.addView(state, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) })
        val phone = panel.section(tr("iPhone для беспроводного CarPlay", "iPhone for wireless CarPlay"))
        var ready = false
        panel.dialog.setOnDismissListener { if (!ready) host.cancelBluetoothProbe() }
        host.log(tr("Чтение состояния Bluetooth и сопряжённых устройств…", "Reading Bluetooth state and paired devices…"), display = false)
        host.probeBluetooth { snapshot ->
            if (host.destroyed || !panel.dialog.isShowing) return@probeBluetooth
            ready = true
            host.bluetoothSnapshot = snapshot; host.log(snapshot.describe(), display = false)
            fillPhoneSettings(panel, radio, state, phone, snapshot)
        }
    }

    private fun fillPhoneSettings(panel: CoorlayPanel, radio: LinearLayout, state: android.widget.TextView, phone: LinearLayout,
        snapshot: BluetoothSnapshot) {
        state.text = when (snapshot.state) {
            BluetoothAdapter.STATE_ON -> tr("Bluetooth включён · сопряжённых устройств: ${snapshot.phones.size}", "Bluetooth is on · paired devices: ${snapshot.phones.size}")
            BluetoothAdapter.STATE_OFF -> tr("Bluetooth выключен", "Bluetooth is off")
            else -> tr("Состояние Bluetooth недоступно", "Bluetooth state unavailable")
        }
        // One button for the state Bluetooth is in (agreed 2026-10-13).
        if (snapshot.state == BluetoothAdapter.STATE_OFF) panel.buttons(radio,
            tr("Включить Bluetooth", "Turn on Bluetooth") to { panel.close(); host.enableBluetooth() })
        else panel.buttons(radio, tr("Открыть Bluetooth", "Open Bluetooth") to { panel.close(); openBluetoothPairing(snapshot) })

        val phones = snapshot.phones
        if (phones.isEmpty()) {
            panel.note(phone, tr("Нет сопряжённых телефонов. Нажмите «Открыть Bluetooth» и сопрягите iPhone.",
                "No paired phones. Press «Open Bluetooth» and pair the iPhone."))
            return
        }
        val chosen = host.prefs.getString(CHOSEN_PHONE, null)?.takeIf { address -> phones.any { it.address == address } }
        // null is «Автоматически»; the rows are marked in place, so the list keeps its scroll position.
        val rows = mutableMapOf<String?, View>()
        fun choose(address: String?, label: String) {
            host.prefs.edit().putString(CHOSEN_PHONE, address).apply()
            host.log(tr("Телефон для беспроводного CarPlay", "Phone for wireless CarPlay") + ": $label")
            rows.forEach { (option, row) -> panel.chosen(row, option == address) }
        }
        rows[null] = panel.choice(phone, tr("Автоматически", "Automatic"),
            tr("Если телефонов несколько, coorlay спросит при подключении", "With several phones coorlay asks when connecting"),
            chosen == null) { choose(null, tr("автоматически", "automatic")) }
        phones.forEach { candidate ->
            rows[candidate.address] = panel.choice(phone, candidate.label, "", chosen == candidate.address) { choose(candidate.address, candidate.label) }
        }
    }

    private fun connectionSettings(panel: CoorlayPanel) {
        // Firmware Wi-Fi diagnostics go to the journal; the form shows only the settings.
        journal.execute { ConnectionDiagnostics.wifi(activity.applicationContext).forEach { host.log(it, display = false) } }
        val auto = panel.section(tr("Автозапуск", "Autostart"))
        setting(panel, auto, "autoConnect", true, tr("Автозапуск", "Autostart"),
            tr("Автоматически запускать coorlay в фоне, при включении",
                "Start coorlay automatically in the background when the head unit turns on"))
        // Off by default: the tester wanted to choose when CarPlay takes the phone (2026-10-11).
        setting(panel, auto, AUTO_WIRELESS, false, tr("Подключаться автоматически", "Connect automatically"),
            tr("Автоматически запускать беспроводной CarPlay при старте программы",
                "Start wireless CarPlay automatically when the app starts"))

        // The hotspot name and password are made up by coorlay and never shown (user, 2026-10-13).
        val network = panel.section(tr("Сеть Wi-Fi", "Wi-Fi network"))
        setting(panel, network, WIFI_DIRECT, true, "Wi-Fi Direct",
            tr("Использовать Wi-Fi Direct для беспроводного подключения (если отключено - используется подключение через точку доступа)",
                "Use Wi-Fi Direct for the wireless connection (when off, the connection goes through the hotspot)"))
    }

    private fun diagnosticsSettings(panel: CoorlayPanel) {
        val logs = panel.section(tr("Журнал", "Journal"))
        panel.buttons(logs,
            tr("Открыть журнал", "Open journal") to { panel.close(); showJournal() },
            tr("Сохранить лог на флешку", "Save log to USB stick") to { panel.close(); chooseLogDirectory() },
            tr("Очистить журнал", "Clear journal") to { panel.close(); confirmClearPreviousJournal() }, primary = 1)
        val details = panel.section(tr("Подробности", "Details"))
        setting(panel, details, DETAILED_LOGS, BuildConfig.DETAILED_LOGS_DEFAULT, tr("Подробный журнал", "Detailed journal"),
            tr("Пакеты, FPS, счётчики Wi-Fi и журнал штатного Bluetooth. Включайте только для поиска сбоя; действует со следующего подключения",
                "Packets, FPS, Wi-Fi counters and the built-in Bluetooth log. Turn on only to find a failure; applies from the next connection"))
        val selected = host.displaySelection()
        panel.note(details, tr("Сейчас", "Now") + ": CarPlay ${selected.video.width} × ${selected.video.height}, " +
            tr("30 кадров/с (экран ${selected.viewport.width} × ${selected.viewport.height})",
                "30 fps (screen ${selected.viewport.width} × ${selected.viewport.height})"))
    }
    /** Folders with pictures: the remembered one and the storage roots (USB sticks first). */
    private fun chooseWallpaper() {
        storageTasks.execute {
            val roots = DiagnosticLogExporter.storageRoots()
            activity.runOnUiThread {
                if (host.destroyed) return@runOnUiThread
                val remembered = host.prefs.getString("wallpaperDirectory", null)?.let(::File)
                val locations = (listOfNotNull(remembered) + roots).distinctBy { it.absolutePath }
                val panel = CoorlayPanel(activity, tr("Фоновая картинка", "Background picture"),
                    tr("Картинка для главного экрана и меню: JPG, PNG, WEBP, BMP", "Picture for the home screen and menus: JPG, PNG, WEBP, BMP"))
                panel.backTo { open(Section.BACKGROUND) }
                locations.forEach { location -> panel.item(location.absolutePath, tr("Открыть папку", "Open folder")) { browseWallpapers(location) } }
                panel.item(tr("Выбрать другую папку", "Choose another folder"), tr("Обзор памяти устройства", "Browse device storage")) { browseWallpapers(File("/mnt")) }
                if (CoorlayWallpaper.isCustom(activity)) panel.action(tr("Стандартный фон", "Standard background")) { resetWallpaper() }
                panel.show()
            }
        }
    }
    private fun browseWallpapers(directory: File) {
        storageTasks.execute {
            val entries = directory.listFiles().orEmpty()
            val images = entries.filter(CoorlayWallpaper::isImage).sortedBy { it.name.lowercase() }
            val folders = entries.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            activity.runOnUiThread {
                if (host.destroyed) return@runOnUiThread
                val parent = directory.parentFile
                val panel = CoorlayPanel(activity, tr("Фоновая картинка", "Background picture"), directory.absolutePath)
                panel.backTo { chooseWallpaper() }
                if (parent != null) panel.item(tr("‹  На уровень выше", "‹  Up one level")) { browseWallpapers(parent) }
                images.forEach { image -> panel.item(image.name, tr("${image.length() / 1024} КБ · сделать фоном", "${image.length() / 1024} KB · set as background")) { applyWallpaper(image) } }
                folders.forEach { folder -> panel.item(folder.name, tr("Открыть папку", "Open folder")) { browseWallpapers(folder) } }
                if (images.isEmpty() && folders.isEmpty()) panel.message(tr("Здесь нет картинок JPG, PNG, WEBP, BMP и вложенных папок.", "No JPG, PNG, WEBP or BMP pictures or subfolders here."))
                panel.action(tr("Ввести путь", "Enter path")) {
                    val entry = CoorlayPanel(activity, tr("Папка с картинками", "Picture folder"))
                    entry.backTo { browseWallpapers(directory) }
                    val card = entry.section(tr("Путь к папке", "Folder path"))
                    val path = entry.field(card, tr("Расположение", "Location"), directory.absolutePath)
                    entry.action(tr("Открыть", "Open"), primary = true) { browseWallpapers(File(path.text.toString())) }
                    entry.show()
                }
                panel.show()
            }
        }
    }
    /** On success the picker is already closed: the driver sees the new background on the home screen. */
    private fun applyWallpaper(image: File) {
        host.log(tr("Фон: установка ${image.name}…", "Background: installing ${image.name}…"))
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels; val height = metrics.heightPixels
        storageTasks.execute {
            val error = try {
                CoorlayWallpaper.install(activity.applicationContext, image, width, height)
                null
            } catch (failure: Exception) { failure.message ?: failure.javaClass.simpleName }
            // A huge photo can still exhaust the head unit's memory; the old background stays.
            catch (_: OutOfMemoryError) { tr("картинка слишком большая для памяти магнитолы", "the picture is too large for the head unit's memory") }
            activity.runOnUiThread {
                if (error == null) {
                    host.prefs.edit().putString("wallpaperDirectory", image.parent).apply()
                    CoorlayWallpaper.changed()
                    host.wallpaperChanged()
                    host.log(tr("Фон изменён", "Background changed") + ": ${image.name}")
                } else {
                    host.log(tr("Не удалось установить фон ${image.name}: $error", "Could not set background ${image.name}: $error"))
                    if (!host.destroyed) CoorlayPanel(activity, tr("Фоновая картинка", "Background picture"), tr("Фон не изменён", "Background not changed")).apply {
                        message("${image.absolutePath}\n$error\n" + tr("Выберите другую картинку JPG или PNG.", "Choose another JPG or PNG picture."))
                        backTo { image.parentFile?.let(::browseWallpapers) }
                        action(tr("Понятно", "OK"), primary = true) { }; show()
                    }
                }
            }
        }
    }
    private fun resetWallpaper() {
        CoorlayWallpaper.reset(activity)
        host.wallpaperChanged()
        host.log(tr("Восстановлен стандартный фон", "Standard background restored"))
    }
    fun showJournal() {
        val text = journal.text()
        val panel = CoorlayPanel(activity, tr("Журнал подключения", "Connection journal"), tr("Последние события coorlay", "Recent coorlay events"))
        panel.backTo { open(Section.DIAGNOSTICS) }
        val view = panel.label(text, 14f).apply {
            setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE
            background = CoorlayStyle.surface(activity); setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        panel.body.addView(view)
        panel.action(tr("Обновить", "Refresh"), close = false) {
            host.recordUsbDiagnostics()
            host.probeBluetoothForDiagnostics { snapshot -> if (!host.destroyed && panel.dialog.isShowing) {
                host.bluetoothSnapshot = snapshot; host.log(snapshot.describe())
                view.text = journal.text()
            } }
        }
        panel.action(tr("Сохранить лог", "Save log"), primary = true) { chooseLogDirectory() }
        panel.scrollToEnd()
        panel.show()
    }
    /** Deletes only the rotated journal; the current run's journal stays for the next saved report. */
    private fun confirmClearPreviousJournal() {
        journal.execute {
            val previous = journal.previousFile
            val size = if (previous.exists()) previous.length() else 0L
            activity.runOnUiThread {
                if (host.destroyed) return@runOnUiThread
                val panel = CoorlayPanel(activity, tr("Очистить журнал", "Clear journal"), tr("Записи прежних запусков coorlay", "Entries of earlier coorlay runs"))
                panel.backTo { open(Section.DIAGNOSTICS) }
                if (size == 0L) {
                    panel.message(tr("Прошлый журнал пуст.", "The previous journal is empty."))
                    panel.action(tr("Готово", "Done"), primary = true) { }
                } else {
                    panel.message(tr("Будет удалено ${size / 1024} КБ записей прежних запусков. " +
                        "Журнал текущего запуска сохранится. Отменить удаление нельзя.",
                        "${size / 1024} KB of entries from earlier runs will be deleted. " +
                        "The current run's journal is kept. This cannot be undone."))
                    panel.action(tr("Очистить", "Clear"), danger = true) {
                        // On the journal worker, so a concurrent rotation cannot recreate the file midway.
                        journal.execute {
                            val deleted = !previous.exists() || previous.delete()
                            host.log(if (deleted) tr("Прошлый журнал очищен (${size / 1024} КБ)", "Previous journal cleared (${size / 1024} KB)")
                                else tr("Не удалось очистить прошлый журнал", "Could not clear the previous journal"))
                        }
                    }
                }
                panel.show()
            }
        }
    }
    private fun chooseLogDirectory() {
        host.log(tr("Выбор папки для сохранения лога", "Choosing a folder for the log"))
        if (exportRunning) {
            CoorlayPanel(activity, tr("Сохранить лог", "Save log"), tr("Сохранение уже идёт", "Saving is already in progress")).apply {
                message(tr("Предыдущий лог ещё записывается на флешку. Дождитесь сообщения «Лог сохранён» и повторите.",
                    "The previous log is still being written to the USB stick. Wait for «Log saved» and try again."))
                action(tr("Понятно", "OK"), primary = true) { }; show()
            }
            return
        }
        storageTasks.execute {
            val roots = DiagnosticLogExporter.storageRoots()
            activity.runOnUiThread {
                if (host.destroyed) return@runOnUiThread
                val remembered = host.prefs.getString("logDirectory", null)?.let(::File)
                val locations = (listOfNotNull(remembered) + roots).distinctBy { it.absolutePath }
                val panel = CoorlayPanel(activity, tr("Сохранить лог", "Save log"), tr("Выберите флешку или внутреннюю память", "Choose a USB stick or internal storage"))
                panel.backTo { settingsMenu() }
                locations.forEach { location -> panel.item(location.absolutePath, tr("Открыть папку", "Open folder")) { browseLogDirectory(location) } }
                panel.item(tr("Выбрать другую папку", "Choose another folder"), tr("Обзор памяти устройства", "Browse device storage")) { browseLogDirectory(File("/mnt")) }
                panel.show()
            }
        }
    }
    private fun browseLogDirectory(directory: File) {
        storageTasks.execute {
            val folders = directory.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
            activity.runOnUiThread {
                if (host.destroyed) return@runOnUiThread
                val parent = directory.parentFile
                val panel = CoorlayPanel(activity, tr("Папка для лога", "Log folder"), directory.absolutePath)
                panel.backTo { chooseLogDirectory() }
                if (parent != null) panel.item(tr("‹  На уровень выше", "‹  Up one level")) { browseLogDirectory(parent) }
                folders.forEach { folder -> panel.item(folder.name, tr("Открыть папку", "Open folder")) { browseLogDirectory(folder) } }
                if (folders.isEmpty()) panel.message(tr("В этой папке нет вложенных каталогов. Можно сохранить лог сюда.", "This folder has no subfolders. The log can be saved here."))
                panel.action(tr("Ввести путь", "Enter path")) {
                    val entry = CoorlayPanel(activity, tr("Папка флешки или памяти", "USB stick or storage folder"))
                    entry.backTo { browseLogDirectory(directory) }
                    val card = entry.section(tr("Путь к папке", "Folder path"))
                    val path = entry.field(card, tr("Расположение", "Location"), directory.absolutePath)
                    entry.action(tr("Открыть", "Open"), primary = true) { browseLogDirectory(File(path.text.toString())) }
                    entry.show()
                }
                panel.action(tr("Сохранить сюда", "Save here"), primary = true) { saveLog(directory) }
                panel.show()
            }
        }
    }
    /** The firmware's own KC2Log next to the report; a failure here never fails the report itself. */
    private fun copyHeadUnitLogs(report: File): List<File> {
        val sources = try { DiagnosticLogExporter.headUnitLogs() } catch (error: Exception) {
            host.log("KC2Log listing failed: ${error.javaClass.simpleName}: ${error.message}", display = false); return emptyList()
        }
        if (sources.isEmpty()) host.log("KC2Log: no *_KC2Log_*.txt in ${DiagnosticLogExporter.HEAD_UNIT_LOG_DIR}", display = false)
        return sources.mapIndexedNotNull { index, source ->
            try {
                val copied = DiagnosticLogExporter.copyHeadUnitLog(source, report, index + 1)
                host.log("KC2Log copied: ${source.name} (${copied.sourceBytes} bytes) -> ${copied.output.absolutePath} " +
                    "(${copied.output.length()} bytes)" + (if (copied.skipped > 0) "; first ${copied.skipped} bytes skipped" else ""),
                    display = false)
                copied.output
            } catch (error: Exception) {
                host.log("KC2Log copy failed: ${source.name} (${source.length()} bytes): ${error.javaClass.simpleName}: ${error.message}",
                    display = false)
                null
            }
        }
    }
    /** While the stick is written: a turning mark and «do not remove the stick»; Back closes it, the writing goes on. */
    private fun savingPanel(): CoorlayPanel = CoorlayPanel(activity, tr("Сохранение лога…", "Saving the log…")).apply {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(BusySpinner(activity), LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(16) })
        row.addView(label(tr("Не вынимайте флешку", "Do not remove the USB stick"), 20f, true))
        body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        show()
    }
    private fun saveLog(directory: File, quiet: Boolean = false) {
        host.log(tr("Сохранение лога: ${directory.absolutePath}… Не вынимайте флешку до сообщения «Лог сохранён».",
            "Saving log: ${directory.absolutePath}… Do not remove the USB stick until «Log saved»."), display = !quiet)
        val progress = if (quiet) null else savingPanel()
        fun finished(show: () -> Unit) = activity.runOnUiThread {
            progress?.close()
            if (!quiet && !host.destroyed) show()
        }
        host.probeBluetoothForDiagnostics { snapshot ->
            if (host.destroyed) return@probeBluetoothForDiagnostics
            host.bluetoothSnapshot = snapshot
            host.log(snapshot.describe(), display = false)
            exportRunning = true
            exportTasks.execute {
                try {
                    // The exporter reads the journal file: write out lines the writer still buffers.
                    journal.flush(3000)
                    val started = SystemClock.uptimeMillis()
                    val file = DiagnosticLogExporter.save(activity.applicationContext, directory, snapshot)
                    host.prefs.edit().putString("logDirectory", directory.absolutePath).apply()
                    val reportMs = SystemClock.uptimeMillis() - started
                    host.log("Log report written: ${file.absolutePath} (${file.length()} bytes) tookMs=$reportMs", display = false)
                    val headUnit = copyHeadUnitLogs(file)
                    // Only now: «Лог сохранён» used to appear before the KC2Log copies, while the stick was still written.
                    host.log(tr("Лог сохранён: ${file.absolutePath} (${file.length()} байт)", "Log saved: ${file.absolutePath} (${file.length()} bytes)") +
                        " reportMs=$reportMs totalMs=${SystemClock.uptimeMillis() - started}", display = !quiet || !host.videoActive)
                    finished {
                        CoorlayPanel(activity, tr("Лог сохранён", "Log saved"), tr("Файлы готовы к переносу", "The files are ready to copy")).apply {
                            message((listOf(file.absolutePath) + headUnit.map { it.absolutePath }).joinToString("\n"))
                            action(tr("Готово", "Done"), primary = true) { }; show()
                        }
                    }
                } catch (error: Exception) {
                    host.log(tr("Не удалось сохранить лог", "Could not save the log") + ": ${error.javaClass.simpleName}: ${error.message}")
                    finished {
                        CoorlayPanel(activity, tr("Не удалось сохранить лог", "Could not save the log")).apply {
                            message("${directory.absolutePath}\n${error.message}\n" + tr("Выберите доступную папку флешки или внутреннюю память.", "Choose a writable folder on the USB stick or internal storage."))
                            action(tr("Понятно", "OK"), primary = true) { }; show()
                        }
                    }
                } finally {
                    exportRunning = false
                }
            }
        }
    }
}

/** A phone the driver picked in settings; the old "phone" key also held automatic picks and is ignored. */
internal const val CHOSEN_PHONE = "chosenPhone"
/** Wireless attempts without a press; separate from autostart and off by default since 2026-10-11. */
internal const val AUTO_WIRELESS = "autoWireless"
/**
 * Wireless CarPlay over Wi-Fi Direct (on by default since 2026-10-13) or, when off, over the head-unit hotspot. A new key:
 * the old "manual" switch (hotspot, on by default) is no longer read.
 */
internal const val WIFI_DIRECT = "wifiDirect"
internal fun hotspotMode(prefs: SharedPreferences) = !prefs.getBoolean(WIFI_DIRECT, true)
