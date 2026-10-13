package com.coorlay.coolray

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.coorlay.UiText.tr
import com.coorlay.transport.BlockingDuplexByteStream
import com.coorlay.transport.WirelessBluetoothPeer
import com.coorlay.transport.WirelessBluetoothTransport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

/** Binder layouts come from AnwSdkService and the actual Neusoft 0.6800 client. */
internal class AnwBluetoothTransport private constructor(
    private val remote: IBinder,
    private val unbind: () -> Unit,
    private val report: (String) -> Unit,
    private val context: Context? = null,
    private val sdkLog: Boolean = false,
) : WirelessBluetoothTransport {
    private val closed = AtomicBoolean()
    private val stream = AtomicReference<AnwSocketStream?>()
    private val earlyLock = Any()
    private val early = ArrayDeque<Pair<Int, ByteArray>>()
    private val earlyLost = mutableSetOf<Int>()
    private var earlyBytes = 0
    private val callback = DataCallback { index, bytes, count ->
        synchronized(earlyLock) {
            if (!closed.get()) {
                val active = stream.get()
                if (active != null) active.receive(index, bytes, count)
                else if (index in 0..29 && bytes != null && count in 1..bytes.size) {
                    if (earlyBytes + count <= 65_536) { early.addLast(index to bytes.copyOf(count)); earlyBytes += count }
                    else earlyLost.add(index)
                }
            }
        }
    }
    private val callbackAttempted = AtomicBoolean()
    @Volatile private var ownedIndex = -1
    private val nativeAddresses = mutableMapOf<String, String>()

    fun androidState(): Int = call("power status") { if (transact(3) { it.readInt() } == 1) 12 else 10 }

    /** ANWBT_DisconnectMobile (IAnwPhoneLink 28): drops HFP/A2DP/AVRCP to the connected phone; 1 = accepted. */
    fun disconnectMobile(): Int = call("DisconnectMobile") { transact(DISCONNECT_MOBILE) { it.readInt() } }

    /**
     * Phones the OEM stack holds a link to right now: ANWBT_GetConnectedDeviceInfo (IAnwPhoneLink 24) for hands-free
     * (0), A2DP (2) and AVRCP (3), the profiles the head unit's own phone app checks. Unknown or failing profiles are
     * skipped.
     */
    fun connectedAddresses(): Set<String> {
        val result = linkedSetOf<String>()
        for (profile in CONNECTED_PROFILES) {
            val address = runCatching {
                call("connected device") {
                    transact(GET_CONNECTED_DEVICE_INFO, { p -> p.writeInt(profile); p.writeInt(1); p.writeInt(1); p.writeInt(0) }) { p ->
                        val connected = p.readInt() == 1
                        val addresses = p.createStringArray()
                        if (connected) addresses?.firstOrNull() else null
                    }
                }
            }.getOrNull()?.let(::normalizeAddress)
            if (address != null && MAC.matches(address) && address != "00:00:00:00:00:00") result += address
        }
        report("ANW connected phones count=${result.size}")
        return result
    }

    override fun localAddress(): String = call("local address") {
        val address = normalizeAddress(transact(7) { it.readString().orEmpty() })
        check(MAC.matches(address) && address != "00:00:00:00:00:00") { "ANW local Bluetooth address unavailable" }
        address
    }

    override fun pairedDevices(): List<WirelessBluetoothPeer> = call("paired devices") {
        val result = transact(16, { p -> p.writeInt(1); repeat(3) { p.writeInt(64) } }) { p ->
            check(p.readInt() == 1) { "ANW GetPairedList rejected" }
            val count = p.createIntArray() ?: throw IOException("ANW missing pair count")
            val names = p.createStringArray() ?: throw IOException("ANW missing pair names")
            val addresses = p.createStringArray() ?: throw IOException("ANW missing pair addresses")
            p.createIntArray() ?: throw IOException("ANW missing device classes")
            check(count.size == 1 && count[0] in 0..minOf(names.size, addresses.size, 64)) { "ANW invalid pair count" }
            (0 until count[0]).mapNotNull { i ->
                val rawAddress = addresses[i] ?: return@mapNotNull null
                val address = normalizeAddress(rawAddress)
                if (!MAC.matches(address)) null else {
                    nativeAddresses[address.uppercase(java.util.Locale.US)] = rawAddress
                    WirelessBluetoothPeer(address, names[i] ?: tr("Bluetooth-устройство", "Bluetooth device"))
                }
            }
        }
        report("ANW paired devices count=${result.size}")
        result
    }

    override fun connect(address: String, uuid: UUID, timeoutMillis: Long): BlockingDuplexByteStream {
        val deadline = System.nanoTime() + timeoutMillis.coerceIn(1, 60_000) * 1_000_000L
        check(MAC.matches(address))
        check(stream.get() == null) { "ANW socket already open" }
        call("descriptor") { check(remote.interfaceDescriptor == DESCRIPTOR) { "Unexpected ANW descriptor" } }
        val power = call("power status") { transact(3) { it.readInt() } }
        check(power == 1) { tr("Штатный Bluetooth ANW выключен (power=$power)", "The built-in ANW Bluetooth is off (power=$power)") }
        val ready = call("socket status") { transact(233) { it.readInt() != 0 } }
        if (!ready) {
            val result = call("SocketInit") { transact(222) { it.readInt() } }
            report("ANW SocketInit result=$result")
            check(result == 1) { "ANW SocketInit rejected ($result)" }
        }
        check(call("socket status") { transact(233) { it.readInt() != 0 } }) { "ANW socket subsystem not ready" }
        call("register data callback") {
            callbackAttempted.set(true)
            transact(231, { it.writeStrongBinder(callback) }) { Unit }
        }
        // The ANW SDK debug log (native libAnwManagerS -> logcat/KC2Log) shows why SocketConnect stays silent.
        // ANWBT_EnableSDKLog is IAnwPhoneLink transaction 126 (void) and stays as set after close, so a quiet
        // start switches off what an earlier detailed one turned on.
        val sdkLogFlag = if (sdkLog) 1 else 0
        try {
            call("EnableSDKLog") { transact(ENABLE_SDK_LOG, { it.writeInt(sdkLogFlag) }) { Unit } }
            report("ANW EnableSDKLog($sdkLogFlag) result=ok")
        } catch (error: Exception) {
            report("ANW EnableSDKLog($sdkLogFlag) result=failed ${error.javaClass.simpleName}: ${error.message}")
        }
        // 2026-10-08 ANW SDK log: with big-endian UUID bytes the SDP search completed in ~0.5 s but ended in
        // "Open Device... scn = 0 Fail" + CONNECT_STATUS(17, Value=0). BT_Service prints 128-bit UUIDs in the
        // Windows GUID layout (first three fields little-endian). Confirmed on the unit 2026-10-08 20:35: GUID bytes
        // connect in 0.5 s. A first attempt cut at half the timeout dropped a GUID connect that paged for ~8 s,
        // so only the GUID layout is used, with the whole timeout.
        val layouts = listOf("GUID" to guidBytes(uuid))
        val status = ConnectStatusWatcher(context, report)
        try {
            for ((attempt, layout) in layouts.withIndex()) {
                val now = System.nanoTime()
                if (closed.get() || now >= deadline) break
                val last = attempt == layouts.lastIndex
                val attemptDeadline = if (last) deadline else now + (deadline - now) / 2
                if (connectAttempt(address, uuid, layout.first, layout.second, timeoutMillis, attemptDeadline, last, status)) {
                    return stream.get() ?: throw IOException("ANW connection closed")
                }
            }
        } finally { status.close() }
        close()
        throw IOException("ANW RFCOMM connection timed out")
    }

    /** One SocketConnect with [uuidBytes]; true once connected, false after a reported failure or [attemptDeadline]. */
    private fun connectAttempt(address: String, uuid: UUID, layout: String, uuidBytes: ByteArray, timeoutMillis: Long,
            attemptDeadline: Long, last: Boolean, status: ConnectStatusWatcher): Boolean {
        // libAnwManagerS MyOpenDevice: scn 0 (or 0xff) runs an SDP lookup of the UUID first; any other
        // value outside 1..30 (-1 arrives as 0xffff) fails silently without a status callback.
        // libAnwPLS str2ba only parses the colon form of the address.
        report("ANW SocketConnect type=RFCOMM uuid=$uuid layout=$layout bytes=${uuidBytes.joinToString("") { "%02x".format(it.toInt() and 255) }} " +
            "channel=$SDP_LOOKUP_CHANNEL (SDP lookup) mtu=0 flags=0")
        status.reset()
        call("SocketConnect", timeoutMillis) {
            val index = transact(225, { p ->
                p.writeInt(1); p.writeString(normalizeAddress(nativeAddresses[address.uppercase(java.util.Locale.US)] ?: address)); p.writeByteArray(uuidBytes)
                p.writeInt(SDP_LOOKUP_CHANNEL); p.writeLong(0); p.writeLong(0); p.writeInt(1)
            }) { p ->
                val result = p.readInt()
                val indices = p.createIntArray()
                val id = indices?.singleOrNull() ?: -1
                report("ANW SocketConnect result=$result index=$id")
                if (id in 0..29) ownedIndex = id
                check(result == 1 && id in 0..29) { "ANW SocketConnect rejected ($result, index=$id)" }
                id
            }
            if (closed.get()) {
                // A timed-out Binder can return later; dispose only the handle it created.
                transact(226, { it.writeInt(index) }) { it.readInt() }
                ownedIndex = -1
                throw IOException("ANW connection cancelled")
            }
            val created = AnwSocketStream(index,
                write = { data, timeout -> writeSocket(index, data, timeout) },
                connected = { isConnected(index) }, disconnect = ::close)
            synchronized(earlyLock) {
                stream.set(created)
                check(index !in earlyLost) { "ANW early receive queue overflow" }
                early.forEach { (socket, bytes) -> created.receive(socket, bytes, bytes.size) }
                early.clear(); earlyLost.clear(); earlyBytes = 0
            }
        }
        val index = ownedIndex
        while (!closed.get() && System.nanoTime() < attemptDeadline) {
            if (isConnected(index)) {
                report("ANW RFCOMM connected index=$index layout=$layout; starting iAP2")
                return true
            }
            val value = status.failure(index)
            if (value != null) {
                report("ANW CONNECT_STATUS index=$index layout=$layout $value: RFCOMM not opened")
                break
            }
            Thread.sleep(100)
        }
        if (closed.get()) return false
        if (status.failure(index) == null) report("ANW RFCOMM layout=$layout: no connection by the attempt deadline")
        if (last) return false
        // Release only this handle; AnwSocketStream.close() would close the whole transport.
        synchronized(earlyLock) { stream.set(null); early.clear(); earlyLost.clear(); earlyBytes = 0 }
        ownedIndex = -1
        try { report("ANW SocketDisconnect index=$index result=" + call("SocketDisconnect") { transact(226, { it.writeInt(index) }) { it.readInt() } }) }
        catch (error: Exception) { report("ANW SocketDisconnect index=$index failed ${error.javaClass.simpleName}: ${error.message}") }
        return false
    }

    /** android.bluetooth.anw.action.CONNECT_STATUS from AnwSdkService.BroadcastConnectStatus (Profile 17 = SPP socket). */
    private class ConnectStatusWatcher(private val context: Context?, private val report: (String) -> Unit) {
        private val failures = java.util.concurrent.ConcurrentHashMap<Int, String>()
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getIntExtra("Profile", -1) != SPP_PROFILE) return
                val index = intent.getIntExtra("Index", -1)
                val value = intent.getIntExtra("Value", -1)
                val text = "Value=$value Linklost=${intent.getIntExtra("Linklost", 0)} PageTimeout=${intent.getIntExtra("PageTimeout", 0)}"
                report("ANW CONNECT_STATUS broadcast index=$index $text")
                if (value == 0) failures[index] = text
            }
        }
        private val registered = try {
            context?.registerReceiver(receiver, IntentFilter(CONNECT_STATUS_ACTION)); context != null
        } catch (error: Exception) { report("ANW CONNECT_STATUS receiver unavailable: ${error.javaClass.simpleName}"); false }
        fun reset() = failures.clear()
        fun failure(index: Int): String? = failures[index]
        fun close() { if (registered) try { context?.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    private fun isConnected(index: Int): Boolean = if (closed.get()) false else call("connection status") {
        transact(23, { it.writeInt(17); it.writeInt(index) }) { it.readInt() == 1 }
    }

    private fun writeSocket(index: Int, bytes: ByteArray, timeout: Long): Int = call("SocketWrite", timeout) {
        transact(227, { p -> p.writeInt(index); p.writeByteArray(bytes); p.writeInt(bytes.size); p.writeInt(1) }) { p ->
            val result = p.readInt()
            val count = p.createIntArray()?.singleOrNull() ?: -1
            check(result == 1 && count in 1..bytes.size) { "ANW SocketWrite rejected ($result, count=$count)" }
            count
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stream.getAndSet(null)?.close()
        synchronized(earlyLock) { early.clear(); earlyLost.clear(); earlyBytes = 0 }
        // Cleanup may wait behind one stuck Binder, but the UI/unbind and recv are released now.
        CALL_PENDING.incrementAndGet()
        CALLS.execute {
            try {
                val index = ownedIndex; ownedIndex = -1
                try {
                    if (index >= 0) report("ANW SocketDisconnect result=" + transact(226, { it.writeInt(index) }) { it.readInt() })
                } finally {
                    if (callbackAttempted.get()) transact(232, { it.writeStrongBinder(callback) }) { Unit }
                }
                report("ANW callback removed; global SocketDenit/BT power not changed")
            } catch (error: Exception) { report("ANW cleanup failed type=${error.javaClass.simpleName}") }
            finally { CALL_PENDING.decrementAndGet(); unbind() }
        }
        unbind()
    }

    private fun <T> call(stage: String, timeout: Long = 5000, action: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ANW calls require a worker" }
        val deadline = System.nanoTime() + timeout.coerceIn(1, 60_000) * 1_000_000L
        if (closed.get()) throw IOException("ANW client closed")
        // Probes/settings can overlap the iAP2 pump. Serialize them instead of failing a live stream.
        // A native call that outlives its timeout still owns the single worker; no new call is queued.
        while (!CALL_PENDING.compareAndSet(0, 1)) {
            if (closed.get()) throw IOException("ANW client closed")
            if (System.nanoTime() >= deadline) throw IOException("ANW busy timeout at $stage")
            try { Thread.sleep(5) }
            catch (error: InterruptedException) {
                Thread.currentThread().interrupt(); throw IOException("ANW cancelled waiting for $stage", error)
            }
        }
        if (closed.get()) { CALL_PENDING.decrementAndGet(); throw IOException("ANW client closed") }
        val future = CALLS.submit<T> {
            try { action() } finally { CALL_PENDING.decrementAndGet() }
        }
        try { return future.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
        catch (error: TimeoutException) { close(); throw IOException("ANW timeout at $stage", error) }
        catch (error: InterruptedException) { close(); Thread.currentThread().interrupt(); throw IOException("ANW cancelled at $stage", error) }
        catch (error: ExecutionException) { throw IOException("ANW failure at $stage: ${error.cause?.javaClass?.simpleName}", error.cause) }
    }

    private fun <T> transact(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); write(data)
            check(remote.transact(code, data, reply, 0)) { "ANW transaction $code unavailable" }
            reply.readException()
            return read(reply)
        } finally { reply.recycle(); data.recycle() }
    }

    internal class DataCallback(private val deliver: (Int, ByteArray?, Int) -> Unit) : Binder() {
        init { attachInterface(null, DATA_CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(DATA_CALLBACK); return true }
            if (code != 1) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DATA_CALLBACK)
            deliver(data.readInt(), data.createByteArray(), data.readInt())
            reply?.writeNoException()
            return true
        }
    }

    companion object {
        internal const val DESCRIPTOR = "com.anwsdk.service.IAnwPhoneLink"
        internal const val DATA_CALLBACK = "com.anwsdk.service.IAnwSocketDataCallBack"
        internal const val SDP_LOOKUP_CHANNEL = 0
        internal const val ENABLE_SDK_LOG = 126
        internal const val DISCONNECT_MOBILE = 28
        const val GET_CONNECTED_DEVICE_INFO = 24
        /** ANW profile ids: 0 hands-free, 2 A2DP, 3 AVRCP. */
        val CONNECTED_PROFILES = listOf(0, 2, 3)
        internal const val SPP_PROFILE = 17
        internal const val CONNECT_STATUS_ACTION = "android.bluetooth.anw.action.CONNECT_STATUS"
        internal fun bigEndianBytes(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        /** Windows GUID layout: Data1/Data2/Data3 little-endian, the last eight bytes as written. */
        internal fun guidBytes(uuid: UUID): ByteArray = bigEndianBytes(uuid).also { b ->
            fun swap(i: Int, j: Int) { val t = b[i]; b[i] = b[j]; b[j] = t }
            swap(0, 3); swap(1, 2); swap(4, 5); swap(6, 7)
        }
        private const val PACKAGE = "com.anwsdk.service"
        private const val SERVICE = "$PACKAGE.AnwSdkService"
        private val MAC = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
        internal fun normalizeAddress(value: String): String = if (value.matches(Regex("(?i)[0-9a-f]{12}")))
            value.chunked(2).joinToString(":").uppercase(java.util.Locale.US) else value.uppercase(java.util.Locale.US)
        private val CALL_PENDING = AtomicInteger()
        private val CALLS = Executors.newSingleThreadExecutor { r -> Thread(r, "coorlay-anw-binder").apply { isDaemon = true } }

        fun available(context: Context): Boolean = try {
            val info = context.packageManager.getServiceInfo(ComponentName(PACKAGE, SERVICE), 0)
            info.enabled && info.applicationInfo.enabled && info.exported && (info.permission == null ||
                context.packageManager.checkPermission(info.permission, context.packageName) == PackageManager.PERMISSION_GRANTED)
        } catch (_: Exception) { false }

        fun open(context: Context, sdkLog: Boolean = false, report: (String) -> Unit): AnwBluetoothTransport {
            check(Looper.myLooper() != Looper.getMainLooper())
            awaitIdle()
            val app = context.applicationContext ?: context
            val main = Handler(Looper.getMainLooper())
            val latch = CountDownLatch(1)
            val binder = AtomicReference<IBinder?>()
            val failure = AtomicReference<Exception?>()
            val disposed = AtomicBoolean()
            var bound = false // Main Looper only.
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { if (!disposed.get()) binder.set(service); latch.countDown() }
                override fun onServiceDisconnected(name: ComponentName) { binder.set(null); latch.countDown() }
            }
            val cleanup = {
                disposed.set(true)
                main.post { if (bound) { bound = false; app.unbindService(connection); report("ANW unbound") } }
                Unit
            }
            main.post {
                if (!disposed.get()) try {
                    bound = app.bindService(Intent("$PACKAGE.AnwPhoneLink").setComponent(ComponentName(PACKAGE, SERVICE)), connection, Context.BIND_AUTO_CREATE)
                    report("ANW bind accepted=$bound")
                    if (!bound) latch.countDown()
                } catch (error: Exception) { failure.set(error); latch.countDown() }
            }
            try {
                if (!latch.await(5000, TimeUnit.MILLISECONDS)) throw IOException("ANW bind timed out")
                failure.get()?.let { throw it }
                val remote = binder.get() ?: throw IOException("ANW service unavailable")
                return AnwBluetoothTransport(remote, cleanup, report, app, sdkLog).also { client ->
                    try { client.call("descriptor") { check(remote.interfaceDescriptor == DESCRIPTOR) } }
                    catch (error: Exception) { client.close(); throw error }
                }
            } catch (error: Exception) { cleanup(); throw error }
        }

        internal fun awaitIdle() {
            val deadline = System.nanoTime() + 1_000_000_000L
            while (CALL_PENDING.get() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
            check(CALL_PENDING.get() == 0) { tr("Предыдущий вызов ANW ещё выполняется; повторите подключение позже", "The previous ANW call is still running; connect again later") }
        }

        internal fun forTest(remote: IBinder, report: (String) -> Unit = {}, sdkLog: Boolean = false): AnwBluetoothTransport {
            awaitIdle()
            return AnwBluetoothTransport(remote, {}, report, sdkLog = sdkLog)
        }
    }
}
