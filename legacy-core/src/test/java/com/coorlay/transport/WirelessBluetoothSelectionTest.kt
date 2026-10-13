package com.coorlay.transport

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class WirelessBluetoothSelectionTest {
    private val phone = WirelessBluetoothPeer("01:23:45:67:89:AB", "iPhone")
    @Test fun configuredPeerAndOneIphoneAreUnambiguous() {
        val peers = listOf(WirelessBluetoothPeer("11:23:45:67:89:AB", "Other"), phone)
        assertEquals(phone, selectWirelessPeer(peers, null))
        assertEquals(phone, selectWirelessPeer(peers, phone.address.lowercase()))
    }
    @Test fun neverConnectsToAnArbitraryPeerAfterPairingWasRemoved() {
        try { selectWirelessPeer(listOf(phone), "21:23:45:67:89:AB"); fail() } catch (_: IOException) { }
        try { selectWirelessPeer(listOf(phone, phone.copy(address = "31:23:45:67:89:AB")), null); fail() } catch (_: IOException) { }
    }
}
