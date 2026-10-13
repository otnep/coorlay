package com.coorlay.coolray

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.coorlay.UiText.tr
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

data class BluetoothEnableResult(val accepted: Boolean?, val error: String? = null) {
    fun describe(): String = "Bluetooth enable(): accepted=$accepted" + (error?.let { "; $it" } ?: "")
}

/** An OEM binder may block even enable(). Bound the UI wait and discard cancelled/late replies. */
class BoundedAction<T>(
    private val handler: Handler,
    private val executor: Executor,
    private val request: (cancelled: () -> Boolean) -> T,
    private val failure: (String) -> T,
    private val describe: (T) -> String,
    private val label: String,
    private val report: (String) -> Unit = {},
) {
    @Volatile private var generation = 0
    private val inFlight = AtomicBoolean(false)
    private var timeout: Runnable? = null

    fun cancel() {
        generation++
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    fun start(timeoutMillis: Long = 5000, callback: (T) -> Unit) {
        require(timeoutMillis > 0)
        cancel()
        if (!inFlight.compareAndSet(false, true)) {
            callback(failure(tr("предыдущий запрос ещё выполняется", "the previous request is still running")))
            return
        }
        val run = generation
        val expires = SystemClock.uptimeMillis() + timeoutMillis
        fun finish(result: T) {
            if (run != generation) return
            cancel()
            callback(result)
        }
        val timedOut = Runnable {
            emit("$label attempt=$run timed out after ${timeoutMillis}ms; late reply ignored")
            finish(failure(tr("служба не ответила за ${timeoutMillis} мс", "the service did not answer within ${timeoutMillis} ms")))
        }
        timeout = timedOut
        handler.postDelayed(timedOut, timeoutMillis)
        try {
            executor.execute {
                if (run != generation || SystemClock.uptimeMillis() >= expires) {
                    inFlight.set(false)
                    return@execute
                }
                emit("$label attempt=$run worker started")
                val result = try { request { run != generation || SystemClock.uptimeMillis() >= expires } } catch (error: Exception) {
                    emit("$label attempt=$run failed: ${Log.getStackTraceString(error)}")
                    failure("${error.javaClass.simpleName}: ${error.message}")
                } finally { inFlight.set(false) }
                emit("$label attempt=$run worker returned: ${describe(result)}")
                handler.post {
                    if (run != generation) emit("$label attempt=$run stale reply ignored")
                    else if (SystemClock.uptimeMillis() >= expires) timedOut.run()
                    else finish(result)
                }
            }
        } catch (error: Exception) {
            inFlight.set(false)
            finish(failure("${error.javaClass.simpleName}: ${error.message}"))
        }
    }
    private fun emit(message: String) { try { report(message) } catch (_: Exception) { } }
}

class BluetoothEnabler(handler: Handler, executor: Executor, request: () -> BluetoothEnableResult,
    report: (String) -> Unit = {}) {
    private val action = BoundedAction(handler, executor, { request() },
        { BluetoothEnableResult(null, it) }, BluetoothEnableResult::describe, "BT ENABLE", report)
    fun cancel() = action.cancel()
    fun start(timeoutMillis: Long = 5000, callback: (BluetoothEnableResult) -> Unit) = action.start(timeoutMillis, callback)
}

/** Runs only on the activation worker, after an explicit Bluetooth or Wi-Fi button press. */
object BluetoothActivation {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun request(context: Context, report: (String) -> Unit): BluetoothEnableResult {
        report("BT ENABLE API=${Build.VERSION.SDK_INT}; direct Android request beginning")
        for (permission in listOf("BLUETOOTH", "BLUETOOTH_ADMIN")) {
            report("BT ENABLE permission $permission=" + (context.packageManager.checkPermission(
                "android.permission.$permission", context.packageName) == PackageManager.PERMISSION_GRANTED))
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return BluetoothEnableResult(false, tr("стандартный адаптер Android отсутствует", "there is no standard Android adapter"))
        describeBinders(adapter, report)
        val state = adapter.state
        report("BT ENABLE state before request=$state")
        if (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON) {
            report("BT ENABLE skipped: Android is already ON or TURNING_ON")
            return BluetoothEnableResult(true)
        }
        val begin = SystemClock.uptimeMillis()
        // On API 18 this requests BluetoothManager to power up the stack without a consent Activity.
        // true only means the request was accepted; the caller must still observe STATE_ON.
        val accepted = adapter.enable()
        report("BT ENABLE Android enable returned=$accepted elapsed=${SystemClock.uptimeMillis() - begin}ms")
        describeBinders(adapter, report)
        describeServices(context, report)
        return BluetoothEnableResult(accepted, if (accepted) null else tr("Android отклонил прямое включение Bluetooth", "Android rejected turning Bluetooth on directly"))
    }

    private fun describeBinders(adapter: BluetoothAdapter, report: (String) -> Unit) {
        if (Build.VERSION.SDK_INT > 18) return
        for (name in listOf("mManagerService", "mService")) {
            try {
                val value = BluetoothAdapter::class.java.getDeclaredField(name).apply { isAccessible = true }.get(adapter)
                report("BT ENABLE binder $name=${value?.javaClass?.name ?: "null"}")
            } catch (error: Exception) { report("BT ENABLE binder $name unavailable=${error.javaClass.simpleName}") }
        }
    }

    @Suppress("DEPRECATION")
    private fun describeServices(context: Context, report: (String) -> Unit) {
        for (name in listOf("com.android.bluetooth", "com.neusoft.geely.btphone")) {
            try {
                val info = context.packageManager.getPackageInfo(name, PackageManager.GET_SERVICES)
                report("BT ENABLE package=$name version=${info.versionName} enabled=${info.applicationInfo?.enabled}")
                info.services.orEmpty().filter { it.name.endsWith("AdapterService") || it.name.endsWith("BtManagerService") }
                    .forEach { report("BT ENABLE declared service=${it.name} enabled=${it.enabled} exported=${it.exported} permission=${it.permission}") }
            } catch (error: Exception) { report("BT ENABLE package=$name unavailable=${error.javaClass.simpleName}") }
        }
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val running = manager.getRunningServices(100).filter {
                it.service.packageName in setOf("com.android.bluetooth", "com.neusoft.geely.btphone")
            }
            report("BT ENABLE visible Bluetooth services=${running.size}")
            running.forEach { report("BT ENABLE running=${it.service.flattenToShortString()} pid=${it.pid} started=${it.started} crashing=${it.crashCount}") }
        } catch (error: Exception) { report("BT ENABLE running services unavailable=${error.javaClass.simpleName}") }
    }
}
