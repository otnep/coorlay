package com.coorlay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class IphoneUsbMatcher private constructor(private val identities: Set<UsbDeviceId>?) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(allowedDevices.toSet() as Set<UsbDeviceId>?)
    fun matches(vendorId: Int, productId: Int) = vendorId == 0x05ac &&
        (identities == null || UsbDeviceId(vendorId, productId) in identities)
    companion object { fun appleVendor() = IphoneUsbMatcher(null) }
}

/** API-18 USB host. Public Android handles permission; USBfs handles interfaces by numeric ID. */
class IphoneUsbHost(context: Context, private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
    private val configurationId: Int? = null,
    private val onDiagnostic: (String) -> Unit = {}) {
    private val context = context.applicationContext
    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }
    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }
    sealed class TransitionResult {
        data object ReenumerationRequested : TransitionResult()
        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }
    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }
    fun discover() = usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }
    fun hasPermission(device: UsbDevice) = usbManager.hasPermission(device)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)
        usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0,
            Intent(permissionAction).setPackage(context.packageName), PendingIntent.FLAG_UPDATE_CURRENT))
        return PermissionRequest.Requested(device)
    }
    fun refreshConfigurations(device: UsbDevice) {
        val connection = open(device)
        try {
            IphoneCarPlayConfiguration.refresh(device, connection)
            for (config in IphoneCarPlayConfiguration.configurations(device)) {
                onDiagnostic("USB descriptor configuration=${config.id} interfaces=" + config.interfaces.joinToString { "${it.id}/${it.alternateSetting}:${it.interfaceClass}/${it.interfaceSubclass}/${it.interfaceProtocol}" })
            }
        } finally { connection.close() }
    }
    /** Only standard descriptor reads: never repeat the mode switch or reset the device. */
    fun probeCarPlayConfiguration(device: UsbDevice): Boolean {
        val connection = open(device)
        try {
            IphoneCarPlayConfiguration.refresh(device, connection, 500, 2000L)
            return IphoneCarPlayConfiguration.find(device, configurationId) != null
        } finally { connection.close() }
    }
    fun registerPermissionReceiver(callback: (PermissionResult) -> Unit): Closeable =
        receiver(permissionAction) { intent ->
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return@receiver
            if (!matcher.matches(device.vendorId, device.productId)) return@receiver
            callback(if (usbManager.hasPermission(device)) PermissionResult.Granted(device) else PermissionResult.Denied(device))
        }
    fun registerAttachReceiver(callback: (UsbDevice) -> Unit): Closeable =
        receiver(UsbManager.ACTION_USB_DEVICE_ATTACHED) { intent ->
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return@receiver
            if (matcher.matches(device.vendorId, device.productId)) callback(device)
        }
    private fun receiver(action: String, callback: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = callback(intent) }
        context.registerReceiver(receiver, IntentFilter(action))
        val registered = AtomicBoolean(true)
        return Closeable { if (registered.compareAndSet(true, false)) context.unregisterReceiver(receiver) }
    }
    private fun open(device: UsbDevice): UsbDeviceConnection {
        if (!usbManager.hasPermission(device)) throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        return usbManager.openDevice(device) ?: throw IphoneUsbException.DeviceUnavailable("Could not open Apple USB device")
    }
    fun requestCarPlayReenumerationAsync(device: UsbDevice, executor: Executor, callback: (TransitionResult) -> Unit) {
        executor.execute {
            val result = try {
                val connection = open(device)
                try {
                    val data = ByteArray(1)
                    val count = connection.controlTransfer(0xc0, 0x52, 0, 4, data, 1, 1000)
                    onDiagnostic("USB mode request c0/52 value=0 index=4 transferred=$count response=${if (count == 1) data[0].toInt() and 255 else "unavailable"}")
                    if (count != 1) throw IphoneUsbException.Protocol("CarPlay mode request transferred $count of 1 bytes")
                    TransitionResult.ReenumerationRequested
                } finally { connection.close() }
            } catch (error: Exception) { TransitionResult.Failed(failure(error)) }
            callback(result)
        }
    }
    fun openIap2UsbSessionAsync(device: UsbDevice, executor: Executor, callback: (Iap2SessionResult) -> Unit) {
        executor.execute {
            val result = try {
                val connection = open(device)
                try {
                    val config = IphoneCarPlayConfiguration.find(device, configurationId)
                        ?: throw IphoneUsbException.Protocol("No USBMUX/NCM CarPlay configuration (requested=${configurationId ?: "auto"})")
                    val current = ByteArray(1)
                    val count = connection.controlTransfer(0x80, 8, 0, 0, current, 1, 1000)
                    if (count != 1) throw IphoneUsbException.Protocol("GET_CONFIGURATION transferred $count of 1 bytes")
                    val currentId = current[0].toInt() and 255
                    val active = IphoneCarPlayConfiguration.configurations(device).firstOrNull { it.id == currentId }
                    val fd = connection.fileDescriptor
                    LegacyUsbConfigurationSelector.select(currentId, config.id,
                        if (currentId == 0) emptyList() else active?.interfaces?.map { it.id },
                        object : LegacyUsbConfigurationSelector.Operations {
                            override fun select(configuration: Int) = LegacyUsbNative.configuration(fd, configuration)
                            override fun driver(iface: Int) = LegacyUsbNative.driver(fd, iface)
                            override fun detach(iface: Int) = LegacyUsbNative.detach(fd, iface)
                            override fun attach(iface: Int) = LegacyUsbNative.attach(fd, iface)
                        }, onDiagnostic)
                    val verified = connection.controlTransfer(0x80, 8, 0, 0, current, 1, 1000)
                    onDiagnostic("USB selected configuration=${if (verified == 1) current[0].toInt() and 255 else "unavailable"} transferred=$verified")
                    if (verified != 1 || current[0].toInt() and 255 != config.id)
                        throw IphoneUsbException.Protocol("USB configuration ${config.id} was not confirmed by GET_CONFIGURATION")
                    val iface = IphoneCarPlayConfiguration.usbMuxInterface(config)!!
                    val output = iface.endpoints.singleOrNull { it.type == 2 && it.direction == 0 }
                        ?: throw IphoneUsbException.Protocol("No USBMUX bulk OUT endpoint")
                    val input = iface.endpoints.singleOrNull { it.type == 2 && it.direction == 128 }
                        ?: throw IphoneUsbException.Protocol("No USBMUX bulk IN endpoint")
                    val pipe = LegacyUsbPipe(connection, listOf(iface.id))
                    Iap2SessionResult.Connected(Iap2UsbSession(pipe, output.address, input.address))
                } catch (error: Throwable) { connection.close(); throw error }
            } catch (error: Exception) { Iap2SessionResult.Failed(failure(error)) }
            try { callback(result) } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) result.session.close()
                throw error
            }
        }
    }
    private fun failure(error: Exception): IphoneUsbException = when (error) {
        is IphoneUsbException -> error
        is SecurityException -> IphoneUsbException.PermissionDenied("USB permission denied", error)
        else -> IphoneUsbException.DeviceUnavailable(error.message ?: "USB operation failed", error)
    }
}

internal class LegacyUsbPipe(val connection: UsbDeviceConnection, interfaces: List<Int>) : Closeable {
    private val closed = AtomicBoolean(false)
    private val claimed = mutableListOf<Pair<Int, Boolean>>()
    private val lifetime = ReentrantReadWriteLock()
    init {
        try {
            for (id in interfaces.distinct()) {
                val result = LegacyUsbNative.claim(connection.fileDescriptor, id)
                if (result < 0) throw IphoneUsbException.DeviceUnavailable("Could not claim USB interface $id: errno=${-result}")
                claimed.add(id to (result == 1))
            }
        } catch (error: Throwable) { close(); throw error }
    }
    fun transfer(endpoint: Int, data: ByteArray, timeout: Int): Int = transfer(endpoint, data, 0, data.size, timeout)
    fun transfer(endpoint: Int, data: ByteArray, offset: Int, length: Int, timeout: Int): Int = lifetime.read {
        if (closed.get()) throw IphoneUsbException.DeviceUnavailable("USB pipe is closed")
        val result = LegacyUsbNative.transfer(connection.fileDescriptor, endpoint, data, offset, length, timeout)
        if (result < 0 && result != -110 && result != -11) throw IphoneUsbException.DeviceUnavailable("USB endpoint ${endpoint.toString(16)} failed: errno=${-result}")
        result
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.write {
            for ((id, detached) in claimed.asReversed()) LegacyUsbNative.release(connection.fileDescriptor, id, detached)
            connection.close()
        }
    }
}
class Iap2UsbSession internal constructor(private val pipe: LegacyUsbPipe, private val output: Int, private val input: Int) : Closeable {
    private val readLock = Any()
    private val writeLock = Any()
    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        if (data.isNotEmpty()) {
            val count = pipe.transfer(output, data, timeoutMillis)
            if (count != data.size) throw IphoneUsbException.DeviceUnavailable("USBMUX write transferred $count of ${data.size} bytes")
        }
    }
    // Reused under readLock: a fresh 16 KiB array per read fed Dalvik GC_FOR_ALLOC pauses.
    private val readBuffer = ByteArray(16384)
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        val count = pipe.transfer(input, readBuffer, timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
        if (count <= 0) null else readBuffer.copyOf(count)
    }
    override fun close() = pipe.close()
}
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
}
