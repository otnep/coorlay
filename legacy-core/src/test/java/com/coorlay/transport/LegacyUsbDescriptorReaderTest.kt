package com.coorlay.transport

import org.junit.Assert.*
import org.junit.Test

class LegacyUsbDescriptorReaderTest {
    private class Phone(bytes: ByteArray) {
        var configs: List<ByteArray> = configurations(bytes)
        val requests = mutableListOf<Pair<Int, Int>>()
        var failValue: Int? = null
        var elapsed = 0L
        var timePerTransfer = 0L
        fun transfer(value: Int, target: ByteArray, timeout: Int): Int {
            requests += value to timeout
            elapsed += minOf(timePerTransfer, timeout.toLong())
            if (value == failValue) return -1
            val source = if (value == 0x100) ByteArray(18).apply {
                this[0] = 18; this[1] = 1; this[17] = configs.size.toByte()
            } else configs[value and 255]
            source.copyInto(target, endIndex = target.size)
            return target.size
        }
        companion object {
            fun configurations(bytes: ByteArray): List<ByteArray> {
                val result = mutableListOf<ByteArray>(); var offset = 0
                while (offset < bytes.size) {
                    val size = bytes[offset].toInt() and 255
                    if (bytes[offset + 1].toInt() == 2) {
                        val length = (bytes[offset + 2].toInt() and 255) or
                            ((bytes[offset + 3].toInt() and 255) shl 8)
                        result += bytes.copyOfRange(offset, offset + length)
                        offset += length
                    } else offset += size
                }
                return result
            }
        }
    }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/usb/$name")!!.use { it.readBytes() }

    @Test fun changedDescriptorsRevealCarPlayWithoutRequiringANewDeviceName() {
        val phone = Phone(fixture("dongle4.bin"))
        assertNull(LegacyUsbDescriptors.choose(LegacyUsbDescriptorReader.read(phone::transfer)))
        phone.configs = Phone.configurations(fixture("iphone6.bin"))
        val refreshed = LegacyUsbDescriptorReader.read(phone::transfer, 500, 2000)
        assertEquals(6, refreshed.size)
        assertEquals(6, LegacyUsbDescriptors.choose(refreshed)!!.id)
        // The probe uses GET_DESCRIPTOR only; no vendor mode switch or SET_CONFIGURATION.
        assertTrue(phone.requests.all { it.first ushr 8 in 1..2 })
    }
    @Test fun removedDeviceStopsProbeAtFirstFailedTransfer() {
        val phone = Phone(fixture("iphone6.bin")).apply { failValue = 0x100 }
        try { LegacyUsbDescriptorReader.read(phone::transfer, 500, 2000); fail() }
        catch (error: IllegalStateException) { assertEquals("Could not read USB device descriptor", error.message) }
        assertEquals(listOf(0x100 to 500), phone.requests)
    }
    @Test fun incompleteConfigurationsCannotBeReturnedAsReady() {
        val phone = Phone(fixture("iphone6.bin")).apply { failValue = 0x203 }
        try { LegacyUsbDescriptorReader.read(phone::transfer, 500, 2000); fail() }
        catch (error: IllegalStateException) { assertEquals("Could not read USB configuration header", error.message) }
        assertTrue(phone.requests.none { it.first > 0x203 })
    }
    @Test fun totalProbeBudgetBoundsIndividualTransfersAndStopsFurtherReads() {
        val phone = Phone(fixture("iphone6.bin")).apply { timePerTransfer = 350 }
        try {
            LegacyUsbDescriptorReader.read(phone::transfer, 500, 800, { phone.elapsed })
            fail("A slow descriptor probe must not hold the worker indefinitely")
        } catch (error: IllegalStateException) {
            assertEquals("USB descriptor probe budget expired", error.message)
        }
        assertEquals(listOf(500, 450, 100), phone.requests.map { it.second })
        assertEquals(800L, phone.elapsed)
    }
}
