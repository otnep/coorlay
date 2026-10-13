package com.coorlay.coolray

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.coorlay.UiText.tr
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Minimal API-18 client for the verified Neusoft 0.6800 Binder contract. */
object OemBluetoothControl {
    internal const val MANAGER = "com.neusoft.geely.btphone.service.IBtPhoneManager"
    internal const val SETTINGS = "com.ecarx.xui.adaptapi.bt1.settings.IBtSettings"
    internal const val CALLBACK = "com.ecarx.xui.adaptapi.bt1.settings.IBtSettingsCallback"
    private const val PACKAGE = "com.neusoft.geely.btphone"
    private const val SERVICE = "$PACKAGE.service.BtManagerService"
    private const val ACTION = "$PACKAGE.service.BtPhoneManagerService"
    private const val DEADLINE_MS = 12_000L
    /** IBtSettings.getState (transaction 19) values of the ECARX/Neusoft Bluetooth service. */
    const val STATE_OFF = 300
    const val STATE_ON = 302
    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L
    private val busy = AtomicBoolean()
    // A stuck native Binder call cannot be interrupted. Keep one worker and retain the guard
    // until its finally block actually completes, including callback unregistration.
    private val binderWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "coorlay-oem-bt-binder").apply { isDaemon = true }
    }

    data class Result(
        val ready: Boolean? = null,
        val enabled: Boolean? = null,
        val state: Int? = null,
        val enableAccepted: Boolean? = null,
        val sppAvailable: Boolean? = null,
        val error: String? = null,
    ) {
        fun describe(): String = "Bluetooth Neusoft: ready=$ready enabled=$enabled state=$state " +
            "enableAccepted=$enableAccepted SPP=$sppAvailable" +
            (error?.let { "; $it" } ?: "") + "; Android RFCOMM not confirmed by this"
    }

    fun isAvailable(context: Context): Boolean = try {
        val info = context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
        info.enabled && info.applicationInfo.enabled && info.exported &&
            (info.permission == null || context.packageManager.checkPermission(
                info.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
    } catch (_: Exception) { false }

    /**
     * Call on a background worker. Binding/callback delivery/unbinding use the main Looper. [enable] false turns the
     * head unit's Bluetooth off (IBtSettings.setBtEnable(false)); the firmware refuses that during a call.
     */
    fun request(context: Context, cancelled: () -> Boolean = { false }, enable: Boolean = true, report: (String) -> Unit): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "OEM Bluetooth request requires a worker" }
        val app = context.applicationContext ?: context
        return execute(DEADLINE_MS, cancelled, report, enable) { call ->
            val handler = Handler(Looper.getMainLooper())
            val connected = CountDownLatch(1)
            val remote = AtomicReference<IBinder?>()
            val bindFailure = AtomicReference<Throwable?>()
            var bound = false // Accessed only on the main Looper.
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (!call.closed.get()) remote.set(service)
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) {
                    call.disconnected.set(true)
                    connected.countDown()
                }
            }
            call.cleanup = {
                handler.post {
                    if (bound) {
                        bound = false
                        try { app.unbindService(connection); report("OEM BT unbind complete") }
                        catch (error: Exception) { reportFailure(report, "unbind", error) }
                    }
                }
            }
            call.stage.set("bind")
            handler.post {
                if (!call.closed.get()) {
                    try {
                        bound = app.bindService(Intent(ACTION).setComponent(ComponentName(PACKAGE, SERVICE)),
                            connection, Context.BIND_AUTO_CREATE)
                        report("OEM BT bind accepted=$bound")
                        if (!bound) {
                            bindFailure.set(IllegalStateException("bind rejected"))
                            connected.countDown()
                        }
                    } catch (error: Exception) { bindFailure.set(error); connected.countDown() }
                }
            }
            while (!connected.await(50, TimeUnit.MILLISECONDS)) call.check()
            call.check()
            bindFailure.get()?.let { throw it }
            remote.get() ?: throw IllegalStateException("manager unavailable")
        }
    }

    /** Exercise the same bounded Binder path without binding a physical radio service. */
    internal fun requestForTest(manager: IBinder, timeoutMs: Long = 1000,
        cancelled: () -> Boolean = { false }, enable: Boolean = true, report: (String) -> Unit = {}): Result =
        execute(timeoutMs, cancelled, report, enable) { manager }

    private class Call(val deadline: Long, val cancelled: () -> Boolean) {
        val closed = AtomicBoolean()
        val disconnected = AtomicBoolean()
        val stage = AtomicReference("start")
        val result = AtomicReference(Result())
        var cleanup: () -> Unit = {}
        fun check() {
            if (closed.get() || cancelled() || Thread.currentThread().isInterrupted)
                throw InterruptedException("cancelled")
            if (disconnected.get()) throw IllegalStateException("service disconnected")
            if (nowMillis() >= deadline) throw java.util.concurrent.TimeoutException()
        }
    }

    private fun execute(timeoutMs: Long, cancelled: () -> Boolean, report: (String) -> Unit, enable: Boolean = true,
        obtainManager: (Call) -> IBinder): Result {
        if (!busy.compareAndSet(false, true)) return Result(error = tr("Предыдущий OEM Binder-вызов ещё выполняется", "The previous OEM Binder call is still running"))
        val call = Call(nowMillis() + timeoutMs, cancelled)
        var submitted = false
        try {
            call.check()
            val manager = obtainManager(call)
            call.check()
            val future: Future<*> = binderWorker.submit {
                try { runManager(manager, call, report, enable) }
                catch (error: Exception) { fail(call, report, error) }
                finally { busy.set(false) }
            }
            submitted = true
            while (!future.isDone) {
                call.check()
                Thread.sleep(25)
            }
            future.get()
            return call.result.get()
        } catch (error: Exception) {
            val result = fail(call, report, error)
            if (error is InterruptedException && !cancelled()) Thread.currentThread().interrupt()
            return result
        } finally {
            call.closed.set(true)
            call.cleanup()
            // Do not cancel a queued Future: its finally block must release the shared guard.
            if (!submitted) busy.set(false)
        }
    }

    private fun fail(call: Call, report: (String) -> Unit, error: Exception): Result {
        val stage = call.stage.get()
        reportFailure(report, stage, error)
        val message = when (error) {
            is java.util.concurrent.TimeoutException -> tr("Таймаут OEM Bluetooth на этапе $stage", "OEM Bluetooth timeout at step $stage")
            is InterruptedException -> tr("Запрос OEM Bluetooth отменён", "OEM Bluetooth request cancelled")
            else -> tr("Ошибка OEM Bluetooth на этапе $stage", "OEM Bluetooth error at step $stage") + ": ${error.javaClass.simpleName}"
        }
        return call.result.get().copy(error = message).also { call.result.set(it) }
    }

    private fun runManager(manager: IBinder, call: Call, report: (String) -> Unit, enable: Boolean = true) {
        var settings: IBinder? = null
        var registrationAttempted = false
        val callback = SettingsCallback(call.closed, report)
        fun <T> step(name: String, action: () -> T): T {
            call.check()
            call.stage.set(name)
            return action()
        }
        try {
            step("manager descriptor") { check(manager.interfaceDescriptor == MANAGER) { "Unexpected manager" } }
            settings = step("settings binder") { transact(manager, MANAGER, 8) { it.readStrongBinder() } }
                ?: throw IllegalStateException("No settings Binder")
            val remote = settings
            step("settings descriptor") { check(remote.interfaceDescriptor == SETTINGS) { "Unexpected settings" } }
            step("register callback") {
                registrationAttempted = true
                val accepted = transact(remote, SETTINGS, 2, { it.writeStrongBinder(callback) }) { it.readInt() != 0 }
                report("OEM BT callback registration accepted=$accepted")
                check(accepted) { "Callback registration rejected" }
            }
            val spp = step("SPP availability") { transact(manager, MANAGER, 9) { it.readStrongBinder() != null } }
            call.result.set(call.result.get().copy(sppAvailable = spp))
            report("OEM BT SPP binder present=$spp; transport not tested")
            var previous: Result? = null
            while (true) {
                val ready = step("ready") { transact(remote, SETTINGS, 1) { it.readInt() != 0 } }
                val enabled = step("enabled") { transact(remote, SETTINGS, 18) { it.readInt() != 0 } }
                val state = step("state") { transact(remote, SETTINGS, 19) { it.readInt() } }
                val current = call.result.get().copy(ready = ready, enabled = enabled, state = state)
                call.result.set(current)
                if (current != previous) { report(current.describe()); previous = current }
                if (enable && ready && enabled && state == STATE_ON) return
                if (!enable && ready && (!enabled || state == STATE_OFF)) return
                if (ready && current.enableAccepted == null) {
                    val accepted = step(if (enable) "enable" else "disable") {
                        transact(remote, SETTINGS, 5, { it.writeInt(if (enable) 1 else 0) }) { it.readInt() != 0 }
                    }
                    call.result.set(call.result.get().copy(enableAccepted = accepted))
                    report("OEM BT ${if (enable) "enable" else "disable"} accepted=$accepted; waiting for state=${if (enable) STATE_ON else STATE_OFF}")
                    if (!accepted) {
                        call.result.set(call.result.get().copy(error = if (enable)
                            tr("Штатный сервис отклонил включение Bluetooth", "The built-in service refused to turn Bluetooth on")
                            else tr("Штатный сервис отклонил выключение Bluetooth (звонок?)", "The built-in service refused to turn Bluetooth off (a call?)")))
                        return
                    }
                }
                step(if (enable) "wait for OEM ON" else "wait for OEM OFF") { Thread.sleep(200) }
            }
        } finally {
            callback.active.set(false)
            if (registrationAttempted && settings != null) {
                call.stage.set("unregister callback")
                try {
                    val accepted = transact(settings, SETTINGS, 3, { it.writeStrongBinder(callback) }) { it.readInt() != 0 }
                    report("OEM BT callback unregistration accepted=$accepted")
                } catch (error: Exception) { reportFailure(report, "unregister callback", error) }
            }
        }
    }

    private fun <T> transact(remote: IBinder, descriptor: String, code: Int,
        write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            check(remote.transact(code, data, reply, 0)) { "Transaction rejected" }
            reply.readException()
            return read(reply)
        } finally { reply.recycle(); data.recycle() }
    }

    internal class SettingsCallback(private val closed: AtomicBoolean, private val report: (String) -> Unit) : Binder() {
        val active = AtomicBoolean(true)
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code !in 1..11) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            if (active.get() && !closed.get()) {
                when (code) {
                    1 -> report("OEM BT callback ready")
                    2 -> report("OEM BT callback state=${data.readInt()} -> ${data.readInt()}")
                    // Remaining verified callbacks carry peer identities/pairing requests.
                    // Acknowledge their void delivery without reading/logging IDs or accepting pairing.
                    else -> report("OEM BT callback code=$code (payload omitted)")
                }
            }
            reply?.writeNoException()
            return true
        }
    }

    private fun reportFailure(report: (String) -> Unit, stage: String, error: Throwable) {
        // Remote exception messages may contain peer identifiers. Preserve types and stack locations only.
        report("OEM BT failure stage=$stage type=${error.javaClass.name}\n" +
            error.stackTrace.joinToString("\n") { "  at $it" })
    }
}
