package com.coorlay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessPeerCandidatesTest {
    private val nina = WirelessBluetoothPeer("AA:AA:AA:AA:AA:01", "iPhone (Нина)")
    private val other = WirelessBluetoothPeer("AA:AA:AA:AA:AA:02", "Андрей")

    @Test fun withoutAChoiceEveryPairedPhoneIsTriedIphoneNamedFirst() {
        assertEquals(listOf(nina, other), wirelessPeerCandidates(listOf(other, nina), null))
    }

    @Test fun thePhoneThatConnectedLastIsTriedFirst() {
        assertEquals(listOf(other, nina), wirelessPeerCandidates(listOf(nina, other), null, "aa:aa:aa:aa:aa:02"))
        // A last phone that is no longer paired changes nothing.
        assertEquals(listOf(nina, other), wirelessPeerCandidates(listOf(other, nina), null, "AA:AA:AA:AA:AA:09"))
    }

    @Test fun aPhoneChosenInSettingsIsTheOnlyCandidate() {
        assertEquals(listOf(other), wirelessPeerCandidates(listOf(nina, other), other.address))
    }
}
