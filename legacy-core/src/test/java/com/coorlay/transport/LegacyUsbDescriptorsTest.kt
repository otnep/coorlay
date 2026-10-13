package com.coorlay.transport

import org.junit.Assert.*
import org.junit.Test

class LegacyUsbDescriptorsTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/usb/$name")!!.use { it.readBytes() }
    @Test fun measuredIphoneSelectsCarplayConfigurationAndCorrectNcmFunction() {
        val configs = LegacyUsbDescriptors.parse(fixture("iphone6.bin"))
        assertEquals(6, configs.size)
        val config = LegacyUsbDescriptors.choose(configs)!!
        assertEquals(6, config.id)
        val ncm = LegacyUsbDescriptors.ncm(config)!!
        assertEquals(3, ncm.control.id)
        assertEquals(0x3b, ncm.control.ncmCapabilities)
        assertEquals(4, ncm.data.id)
        assertEquals(1, ncm.data.alternateSetting)
        assertNotNull(ncm.statusIn)
        assertEquals(512, ncm.bulkIn.maxPacketSize)
        assertEquals(1, LegacyUsbDescriptors.mux(config)!!.id)
    }
    @Test fun measuredDongleWithoutNcmIsNotMisidentifiedAsDirectIphoneCarplay() {
        val configs = LegacyUsbDescriptors.parse(fixture("dongle4.bin"))
        assertEquals(4, configs.size)
        assertNull(LegacyUsbDescriptors.choose(configs))
    }
    @Test fun alternateConfigurationUsesItsOwnMuxAndNcmDescriptors() {
        val configs = LegacyUsbDescriptors.parse(fixture("iphone6.bin"))
        val chosen = LegacyUsbDescriptors.choose(configs, 5)!!
        assertEquals(5, chosen.id)
        assertNotNull(LegacyUsbDescriptors.mux(chosen))
        val function = LegacyUsbDescriptors.ncm(chosen)!!
        assertTrue(chosen.interfaces.any { it === function.control })
        assertTrue(chosen.interfaces.any { it === function.data })
        assertEquals(6, LegacyUsbDescriptors.choose(configs)!!.id)
    }
    @Test fun explicitUnsupportedConfigurationDoesNotFallBackToSix() {
        val configs = LegacyUsbDescriptors.parse(fixture("iphone6.bin"))
        assertNull(LegacyUsbDescriptors.choose(configs, 4))
        assertNull(LegacyUsbDescriptors.choose(configs, 7))
        assertEquals(6, LegacyUsbDescriptors.choose(configs, 6)!!.id)
    }
    @Test fun missingOrIncompleteFiveDoesNotMakeAnUncontrolledExperiment() {
        val configs = LegacyUsbDescriptors.parse(fixture("iphone6.bin"))
        assertNull(LegacyUsbDescriptors.choose(configs.filter { it.id != 5 }, 5))
        val five = configs.single { it.id == 5 }
        val incomplete = LegacyUsbConfiguration(5, five.interfaces.filter { it.interfaceClass != 10 }.toMutableList())
        assertNull(LegacyUsbDescriptors.choose(configs.filter { it.id != 5 } + incomplete, 5))
    }
    @Test fun truncatedDescriptorCannotReachInterfaceSelection() {
        try {
            LegacyUsbDescriptors.parse(fixture("iphone6.bin").dropLast(1).toByteArray())
            fail("Truncated descriptors must fail")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun zeroLengthDescriptorCannotLoopForever() {
        try { LegacyUsbDescriptors.parse(byteArrayOf(0, 2)); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
