package com.coorlay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.util.concurrent.ConcurrentHashMap

data class LegacyUsbEndpoint(val address: Int, val attributes: Int, val maxPacketSize: Int) {
    val direction: Int get() = address and 0x80
    val type: Int get() = attributes and 3
}
data class LegacyUsbInterface(val id: Int, val alternateSetting: Int, val interfaceClass: Int,
    val interfaceSubclass: Int, val interfaceProtocol: Int, val endpoints: MutableList<LegacyUsbEndpoint>,
    var macStringIndex: Int = 0, var unionDataId: Int? = null, var ncmCapabilities: Int = 0)
data class LegacyUsbConfiguration(val id: Int, val interfaces: MutableList<LegacyUsbInterface>)

/** Parses every configuration and alternate setting, independently of API-21 UsbConfiguration. */
object LegacyUsbDescriptors {
    fun parse(bytes: ByteArray): List<LegacyUsbConfiguration> {
        val configs = mutableListOf<LegacyUsbConfiguration>()
        var current: LegacyUsbConfiguration? = null
        var iface: LegacyUsbInterface? = null
        var offset = 0
        fun u(index: Int) = bytes[index].toInt() and 255
        while (offset < bytes.size) {
            require(offset + 2 <= bytes.size) { "Truncated USB descriptor header" }
            val size = u(offset)
            require(size >= 2 && offset + size <= bytes.size) { "Truncated USB descriptor" }
            when (u(offset + 1)) {
                2 -> {
                    require(size >= 9) { "Invalid USB configuration" }
                    current = LegacyUsbConfiguration(u(offset + 5), mutableListOf())
                    configs.add(current)
                    iface = null
                }
                4 -> {
                    require(size >= 9 && current != null) { "Interface outside configuration" }
                    iface = LegacyUsbInterface(u(offset + 2), u(offset + 3), u(offset + 5),
                        u(offset + 6), u(offset + 7), mutableListOf())
                    current.interfaces.add(iface)
                }
                5 -> {
                    require(size >= 7 && iface != null) { "Endpoint outside interface" }
                    val packetSize = (u(offset + 4) or (u(offset + 5) shl 8)) and 0x7ff
                    require(packetSize in 1..1024) { "Invalid USB endpoint packet size" }
                    iface.endpoints.add(LegacyUsbEndpoint(u(offset + 2), u(offset + 3),
                        packetSize))
                }
                0x24 -> if (size >= 4 && iface != null) {
                    when (u(offset + 2)) {
                        0x0f -> iface.macStringIndex = u(offset + 3)
                        0x06 -> if (size >= 5) iface.unionDataId = u(offset + 4)
                        0x1a -> if (size >= 6) iface.ncmCapabilities = u(offset + 5)
                    }
                }
            }
            offset += size
        }
        return configs
    }
    fun choose(configs: List<LegacyUsbConfiguration>, configurationId: Int? = null): LegacyUsbConfiguration? {
        // An explicit experiment must not silently use a different configuration.
        if (configurationId != null) return configs.firstOrNull {
            it.id == configurationId && mux(it) != null && ncm(it) != null
        }
        return configs.firstOrNull { mux(it) != null && ncm(it) != null && it.interfaces.any { i ->
            i.interfaceClass == 255 && i.interfaceSubclass == 253 && i.interfaceProtocol == 1
        } } ?: configs.firstOrNull { mux(it) != null && ncm(it) != null }
    }

    fun mux(config: LegacyUsbConfiguration): LegacyUsbInterface? = config.interfaces.firstOrNull {
        it.interfaceClass == 255 && it.interfaceSubclass == 254 && it.interfaceProtocol == 2
    }
    fun ncm(config: LegacyUsbConfiguration): NcmFunctionDiscovery.NcmFunction? {
        for (control in config.interfaces.filter { it.interfaceClass == 2 && it.interfaceSubclass == 13 }) {
            val data = config.interfaces.firstOrNull {
                it.interfaceClass == 10 && it.alternateSetting == 1 &&
                    it.id == (control.unionDataId ?: control.id + 1) &&
                    it.endpoints.any { ep -> ep.direction == 128 && ep.type == 2 } &&
                    it.endpoints.any { ep -> ep.direction == 0 && ep.type == 2 }
            } ?: continue
            val status = control.endpoints.firstOrNull { it.direction == 128 && it.type == 3 }
            val result = NcmFunctionDiscovery.NcmFunction(control, data, status,
                data.endpoints.first { it.direction == 128 && it.type == 2 },
                data.endpoints.first { it.direction == 0 && it.type == 2 })
            if (status != null) return result
        }
        return null // CarPlay uses the NCM function with a notification endpoint, not the auxiliary function.
    }
}

object IphoneCarPlayConfiguration {
    const val TAG = "coorlay-usb"
    private val cache = ConcurrentHashMap<String, List<LegacyUsbConfiguration>>()
    fun configurations(device: UsbDevice): List<LegacyUsbConfiguration> = cache[device.deviceName].orEmpty()
    fun find(device: UsbDevice, configurationId: Int? = null): LegacyUsbConfiguration? =
        LegacyUsbDescriptors.choose(cache[device.deviceName].orEmpty(), configurationId)
    fun refresh(device: UsbDevice, connection: UsbDeviceConnection) {
        refresh(device, connection, 1000, null)
    }
    internal fun refresh(device: UsbDevice, connection: UsbDeviceConnection, timeout: Int, budgetMillis: Long?) {
        cache[device.deviceName] = LegacyUsbDescriptorReader.read({ value, bytes, remaining ->
            connection.controlTransfer(0x80, 6, value, 0, bytes, bytes.size, remaining)
        }, timeout, budgetMillis)
    }
    fun usbMuxInterface(config: LegacyUsbConfiguration) = LegacyUsbDescriptors.mux(config)
}

object NcmFunctionDiscovery {
    data class NcmFunction(val control: LegacyUsbInterface, val data: LegacyUsbInterface,
        val statusIn: LegacyUsbEndpoint?, val bulkIn: LegacyUsbEndpoint, val bulkOut: LegacyUsbEndpoint)
    fun find(configuration: LegacyUsbConfiguration) = LegacyUsbDescriptors.ncm(configuration)
}

object LegacyUsbNative {
    init { System.loadLibrary("coorlay_legacy_usb") }
    external fun claim(fd: Int, iface: Int): Int
    external fun release(fd: Int, iface: Int, restoreDriver: Boolean): Int
    external fun configuration(fd: Int, config: Int): Int
    external fun driver(fd: Int, iface: Int): String?
    external fun detach(fd: Int, iface: Int): Int
    external fun attach(fd: Int, iface: Int): Int
    external fun alternate(fd: Int, iface: Int, alt: Int): Int
    external fun transfer(fd: Int, endpoint: Int, data: ByteArray, offset: Int, length: Int, timeout: Int): Int
    external fun setBlocking(fd: Int): Int
}
