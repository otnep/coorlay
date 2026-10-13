package com.coorlay.transport

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LegacyUsbConfigurationSelectorTest {
    private class Kernel : LegacyUsbConfigurationSelector.Operations {
        val drivers = linkedMapOf<Int, String?>()
        val detached = mutableListOf<Int>()
        val restored = mutableListOf<Int>()
        var selects = 0
        var selected = 4
        var selectError = 0
        var detachError: Int? = null
        var pairedDriver = false
        override fun select(configuration: Int): Int {
            selects++
            if (drivers.values.any { it != null }) return -16
            if (selectError < 0) return selectError
            selected = configuration
            return 0
        }
        override fun driver(iface: Int) = drivers[iface]
        override fun detach(iface: Int): Int {
            if (iface == detachError) return -13
            if (drivers[iface] == null) return 0
            detached += iface
            if (pairedDriver) drivers.keys.toList().forEach { drivers[it] = null }
            else drivers[iface] = null
            return 1
        }
        override fun attach(iface: Int): Int { restored += iface; drivers[iface] = "kernel"; return 1 }
    }
    private fun select(kernel: Kernel, current: Int = 4, interfaces: List<Int>? = kernel.drivers.keys.toList()) =
        LegacyUsbConfigurationSelector.select(current, 6, interfaces, kernel) {}
    private fun failure(action: () -> Unit): IOException {
        try { action(); fail("Expected IOException") } catch (error: IOException) { return error }
        throw AssertionError()
    }

    @Test fun alreadyActiveConfigurationDoesNotResetClaimedInterfaces() {
        val kernel = Kernel().apply { drivers[1] = "usbfs"; selected = 6 }
        select(kernel, current = 6)
        assertEquals(0, kernel.selects)
        assertTrue(kernel.detached.isEmpty())
    }
    @Test fun unclaimedDeviceSelectsWithoutDetaching() {
        val kernel = Kernel()
        select(kernel)
        assertEquals(6, kernel.selected)
        assertEquals(1, kernel.selects)
    }
    @Test fun kernelDriverOccupyingTwoInterfacesIsDetachedOnce() {
        val kernel = Kernel().apply { drivers[2] = "cdc_ncm"; drivers[3] = "cdc_ncm"; pairedDriver = true }
        select(kernel, interfaces = listOf(2, 3, 3))
        assertEquals(6, kernel.selected)
        assertEquals(listOf(2), kernel.detached)
        assertTrue(kernel.restored.isEmpty())
    }
    @Test fun anotherUserspaceSessionIsPreservedWithoutDetachingAnyDriver() {
        val kernel = Kernel().apply { drivers[1] = "kernel"; drivers[2] = "usbfs" }
        assertTrue(failure { select(kernel) }.message!!.contains("userspace"))
        assertEquals("kernel", kernel.drivers[1])
        assertEquals("usbfs", kernel.drivers[2])
        assertTrue(kernel.detached.isEmpty())
    }
    @Test fun failedRetryRestoresOnlyDriversDetachedByThisAttempt() {
        val kernel = Kernel().apply { drivers[1] = "kernel"; drivers[2] = null; drivers[3] = "kernel"; selectError = -19 }
        assertTrue(failure { select(kernel) }.message!!.contains("errno=19"))
        assertEquals(listOf(3, 1), kernel.restored)
        assertNull(kernel.drivers[2])
        assertEquals(4, kernel.selected)
    }
    @Test fun failedDetachRestoresPreviouslyDetachedDriver() {
        val kernel = Kernel().apply { drivers[1] = "kernel"; drivers[2] = "kernel"; detachError = 2 }
        assertTrue(failure { select(kernel) }.message!!.contains("errno=13"))
        assertEquals(listOf(1), kernel.restored)
        assertEquals(1, kernel.selects)
    }
    @Test fun missingActiveDescriptorDoesNotGuessInterfaceNumbers() {
        val kernel = Kernel().apply { drivers[1] = "kernel" }
        assertTrue(failure { select(kernel, interfaces = null) }.message!!.contains("no cached descriptor"))
        assertTrue(kernel.detached.isEmpty())
    }
    @Test fun nonBusySelectionErrorDoesNotDetachAnything() {
        val kernel = Kernel().apply { selectError = -22 }
        assertTrue(failure { select(kernel) }.message!!.contains("errno=22"))
        assertTrue(kernel.detached.isEmpty())
        assertEquals(1, kernel.selects)
    }
}
