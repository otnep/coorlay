package com.coorlay.coolray

import android.bluetooth.BluetoothAdapter
import android.os.Handler
import android.os.SystemClock
import com.coorlay.UiText.tr
import java.util.concurrent.Executor

data class PairedPhone(val address: String, val label: String)

data class BluetoothSnapshot(
    val state: Int?,
    val phones: List<PairedPhone> = emptyList(),
    val localAddress: String? = null,
    val error: String? = null,
    val backend: String = "Android",
    /** Paired phones the head unit is linked to right now (calls or music), when the backend can tell. */
    val connected: Set<String> = emptySet(),
) {
    fun describe(): String = "Bluetooth $backend: " + when (state) {
        BluetoothAdapter.STATE_OFF -> "OFF (10)"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON (11)"
        BluetoothAdapter.STATE_ON -> "ON (12); " + tr("сопряжённых устройств", "paired devices") + ": ${phones.size}" +
            (if (connected.isNotEmpty()) "; " + tr("подключено сейчас", "connected now") + ": ${connected.size}" else "")
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF (13)"
        null -> tr("недоступен", "unavailable")
        else -> tr("неизвестное состояние ($state)", "unknown state ($state)")
    } + (error?.let { "; $it" } ?: "")

    companion object {
        // OEM Bluetooth binder calls must never block the Activity's main thread.
        @android.annotation.SuppressLint("MissingPermission")
        fun read(): BluetoothSnapshot = try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) BluetoothSnapshot(null, error = tr("нет стандартного адаптера", "no standard adapter"))
            else {
                val state = adapter.state
                if (state != BluetoothAdapter.STATE_ON) BluetoothSnapshot(state)
                else BluetoothSnapshot(state, adapter.bondedDevices.map {
                    PairedPhone(it.address, it.name ?: tr("Bluetooth-устройство", "Bluetooth device"))
                }.sortedBy { it.label }, adapter.address)
            }
        } catch (error: Exception) {
            BluetoothSnapshot(null, error = "${error.javaClass.simpleName}: ${error.message}")
        }
    }
}

/** Cancels stale results and bounds even an OEM binder that never returns. Main-thread API. */
class BluetoothProbe(
    private val handler: Handler,
    private val executor: Executor,
    private val read: () -> BluetoothSnapshot = BluetoothSnapshot.Companion::read,
    private val timeoutMillis: Long = 5000,
) {
    private var generation = 0
    private var timeout: Runnable? = null

    fun cancel() {
        generation++
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    fun start(timeoutMillis: Long = this.timeoutMillis, callback: (BluetoothSnapshot) -> Unit) {
        cancel()
        val run = generation
        val expires = SystemClock.uptimeMillis() + timeoutMillis
        fun finish(snapshot: BluetoothSnapshot) {
            if (run != generation) return
            cancel()
            callback(snapshot)
        }
        val timedOut = Runnable {
            finish(BluetoothSnapshot(null, error = tr("служба Android не ответила за ${timeoutMillis / 1000} с", "the Android service did not answer within ${timeoutMillis / 1000} s")))
        }
        timeout = timedOut
        handler.postDelayed(timedOut, timeoutMillis)
        executor.execute {
            val snapshot = try { read() } catch (error: Exception) {
                BluetoothSnapshot(null, error = error.javaClass.simpleName)
            }
            handler.post {
                // A late binder reply must not start a session after Stop or after the deadline.
                if (SystemClock.uptimeMillis() >= expires) timedOut.run() else finish(snapshot)
            }
        }
    }
}
