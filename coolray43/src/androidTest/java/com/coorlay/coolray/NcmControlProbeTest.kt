package com.coorlay.coolray

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.test.InstrumentationTestCase
import com.coorlay.transport.IphoneCarPlayConfiguration
import com.coorlay.transport.LegacyUsbNative

/** Manual hardware probe, intentionally excluded from CoolrayTestRunner.getAllTests(). */
class NcmControlProbeTest : InstrumentationTestCase() {
    fun testReadConnectedIphoneNcmControls() = probe(false)
    fun testEnableConnectedIphoneEthernetFilter() = probe(true)

    private fun probe(enableFilter: Boolean) {
        val manager = instrumentation.targetContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.filter { it.vendorId == 0x05ac && it.productId == 0x12a8 }
        assertEquals("Connect only the bench iPhone", 1, devices.size)
        val device = devices.single()
        assertTrue("Existing application USB permission required", manager.hasPermission(device))
        val connection = manager.openDevice(device) ?: error("Cannot open bench iPhone")
        var controlId: Int? = null
        var detached = false
        try {
            val current = ByteArray(1)
            assertEquals(1, connection.controlTransfer(0x80, 8, 0, 0, current, 1, 1000))
            assertEquals("Probe does not change configurations", 6, current[0].toInt() and 255)
            IphoneCarPlayConfiguration.refresh(device, connection)
            val config = IphoneCarPlayConfiguration.find(device) ?: error("No CarPlay configuration")
            val ncm = com.coorlay.transport.NcmFunctionDiscovery.find(config) ?: error("No NCM")
            val claimed = LegacyUsbNative.claim(connection.fileDescriptor, ncm.control.id)
            assertTrue("Could not claim NCM control: $claimed", claimed >= 0)
            controlId = ncm.control.id
            detached = claimed == 1
            fun request(type: Int, code: Int, value: Int, length: Int): Int {
                val data = ByteArray(length)
                val count = connection.controlTransfer(type, code, value, ncm.control.id, data, length, 1000)
                val response = if (count > 0) data.take(count).joinToString(" ") { "%02x".format(it.toInt() and 255) } else "[]"
                val line = "type=0x${type.toString(16)} request=0x${code.toString(16)} value=$value index=${ncm.control.id} length=$length result=$count response=$response"
                instrumentation.sendStatus(0, Bundle().apply { putString("ncm_control", line) })
                return count
            }
            assertEquals("GET_NTB_PARAMETERS", 28, request(0xa1, 0x80, 0, 28))
            request(0xa1, 0x83, 0, 2)
            request(0xa1, 0x85, 0, 4)
            if (enableFilter) assertEquals("SET_ETHERNET_PACKET_FILTER", 0, request(0x21, 0x43, 0x0e, 0))
        } finally {
            controlId?.let { LegacyUsbNative.release(connection.fileDescriptor, it, detached) }
            connection.close()
        }
    }
}
