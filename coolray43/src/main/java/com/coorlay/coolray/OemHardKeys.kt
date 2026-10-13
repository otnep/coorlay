package com.coorlay.coolray

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.coorlay.airplay.CarPlayMediaButton
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Steering-wheel keys of the Neusoft head unit.
 *
 * The wheel does not produce Android KeyEvents. KC2_popview receives the keys from the MCU and
 * hands every one of them to all IEcarxkeyInterface listeners registered through its
 * "com.neusoft.HardKeyAidlInterface" service (IRequestInterface.requestEcarxkeyCallBack, code 10).
 * Unlike the per-source audio slot, this listener list does not displace the stock media apps.
 */
internal class OemHardKeys(
    context: Context,
    private val report: (String) -> Unit,
    private val onKey: (keyCode: Int, action: Int) -> Unit,
) : Closeable {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "coorlay-hardkey-binder").apply { isDaemon = true }
    }
    @Volatile private var remote: IBinder? = null
    @Volatile private var bound = false

    private val callback = object : Binder() {
        init { attachInterface(null, KEY_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_HARD_KEY) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(KEY_DESCRIPTOR)
            val keyCode = data.readInt()
            val action = data.readInt()
            reply?.writeNoException()
            onKey(keyCode, action)
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = binder
            if (binder != null) worker.execute { request(binder, TRANSACTION_REQUEST, "register") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            report("HARDKEY service disconnected; Android rebinds it automatically")
        }
    }

    /** Binds the stock key service; false when this firmware does not provide it. */
    fun start(): Boolean {
        bound = try {
            app.bindService(Intent(ACTION), connection, Context.BIND_AUTO_CREATE)
        } catch (error: Exception) {
            report("HARDKEY bind failed: ${error.javaClass.simpleName}: ${error.message}")
            false
        }
        report("HARDKEY bind ${if (bound) "requested" else "unavailable"}")
        return bound
    }

    private fun request(binder: IBinder, code: Int, label: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(REQUEST_DESCRIPTOR)
            data.writeStrongBinder(callback)
            binder.transact(code, data, reply, 0)
            reply.readException()
            report("HARDKEY $label ok")
        } catch (error: Exception) {
            report("HARDKEY $label failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    override fun close() {
        val binder = remote
        remote = null
        if (binder != null) worker.execute { request(binder, TRANSACTION_UNREQUEST, "unregister") }
        worker.shutdown()
        if (bound) {
            bound = false
            try { app.unbindService(connection) } catch (_: Exception) { }
        }
    }

    /**
     * Turns the popview press sequence into one CarPlay press. popview reports 0 on press,
     * 2 when a press becomes a long press (the stock player seeks) and 1 on release.
     */
    class Decoder {
        private var longPressed = -1

        /** The CarPlay media index for this event, or null when nothing should be sent. */
        fun mediaButton(keyCode: Int, action: Int): Int? {
            val button = when (keyCode) {
                KEY_PREVIOUS -> CarPlayMediaButton.PREVIOUS
                KEY_NEXT -> CarPlayMediaButton.NEXT
                KEY_PLAY_PAUSE -> CarPlayMediaButton.PLAY_PAUSE
                else -> return null
            }
            return when (action) {
                ACTION_LONG_PRESS -> { longPressed = keyCode; null }
                ACTION_UP -> if (longPressed == keyCode) { longPressed = -1; null } else button
                else -> null
            }
        }
    }

    companion object {
        const val ACTION = "com.neusoft.HardKeyAidlInterface"
        const val REQUEST_DESCRIPTOR = "com.neusoft.optimus.wheeljack.popview.AIDL.IRequestInterface"
        const val KEY_DESCRIPTOR = "com.neusoft.optimus.wheeljack.popview.AIDL.IEcarxkeyInterface"
        const val TRANSACTION_REQUEST = 10
        const val TRANSACTION_UNREQUEST = 11
        const val TRANSACTION_HARD_KEY = 1
        const val KEY_PREVIOUS = 0x108
        const val KEY_NEXT = 0x109
        /** Middle wheel key (seen in KC2Log as keyCode 267 while CarPlay played, 2026-10-09). */
        const val KEY_PLAY_PAUSE = 0x10b
        /** Steering-wheel MODE: the firmware switches to its next audio source. */
        const val KEY_MODE = 0xef
        const val ACTION_UP = 1
        const val ACTION_LONG_PRESS = 2
    }
}
