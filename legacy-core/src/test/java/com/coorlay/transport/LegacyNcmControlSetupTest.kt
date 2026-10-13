package com.coorlay.transport

import org.junit.Assert.*
import org.junit.Test

class LegacyNcmControlSetupTest {
    private class Phone {
        val calls = mutableListOf<List<Int>>()
        var shortParameters = false
        var unsupported = false
        fun transfer(type: Int, request: Int, value: Int, iface: Int, data: ByteArray, length: Int): Int {
            calls += listOf(type, request, value, iface, length)
            if (unsupported) return -1
            when (request) {
                0x80 -> {
                    if (shortParameters) return 8
                    val captured = byteArrayOf(0x1c, 0, 3, 0, 0xfc.toByte(), 0x7f, 0, 0, 4, 0, 0, 0,
                        4, 0, 0, 0, 0xfc.toByte(), 0x7f, 0, 0, 4, 0, 0, 0, 4, 0, 0, 2)
                    captured.copyInto(data)
                    return 28
                }
                0x85 -> { byteArrayOf(0xfc.toByte(), 0x7f, 0, 0).copyInto(data); return 4 }
                0x43 -> return 0
                else -> error("Unexpected USB request")
            }
        }
    }
    @Test fun capturedPhoneParametersAndAdvertisedFilterUseControlInterface() {
        val phone = Phone(); val logs = mutableListOf<String>()
        LegacyNcmControlSetup.configure(3, 0x3b, phone::transfer, logs::add)
        assertTrue(logs.any { "inputMax=32764 outputMax=32764" in it })
        assertTrue(logs.any { "bytes=32764" in it })
        assertEquals(listOf(0x21, 0x43, 14, 3, 0), phone.calls.last())
    }
    @Test fun deviceWithoutEthernetFilterCapabilityDoesNotReceiveOptionalWrite() {
        val phone = Phone()
        LegacyNcmControlSetup.configure(5, 0x3a, phone::transfer, {})
        assertTrue(phone.calls.all { it[3] == 5 && it[0] == 0xa1 })
        assertEquals(2, phone.calls.size)
    }
    @Test fun ShortOrUnsupportedOptionalControlsRemainDiagnostic() {
        val phone = Phone().apply { shortParameters = true }; val logs = mutableListOf<String>()
        LegacyNcmControlSetup.configure(3, 1, phone::transfer, logs::add)
        assertTrue(logs.any { "GET_NTB_PARAMETERS" in it && "result=8" in it })
        assertFalse(logs.any { "inputMax=" in it })
        phone.unsupported = true
        LegacyNcmControlSetup.configure(3, 1, phone::transfer, logs::add)
        assertTrue(logs.any { "SET_ETHERNET_PACKET_FILTER" in it && "result=-1" in it })
    }
}
