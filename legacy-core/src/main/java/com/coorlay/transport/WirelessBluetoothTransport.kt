package com.coorlay.transport

import com.coorlay.UiText.tr
import java.io.Closeable
import java.io.IOException
import java.util.UUID

data class WirelessBluetoothPeer(val address: String, val name: String)

/** A head-unit radio need not be registered with Android's BluetoothAdapter. */
interface WirelessBluetoothTransport : Closeable {
    fun localAddress(): String
    fun pairedDevices(): List<WirelessBluetoothPeer>
    fun connect(address: String, uuid: UUID, timeoutMillis: Long): BlockingDuplexByteStream
}

/**
 * Phones to try for wireless CarPlay, in order. A phone chosen in settings is the only candidate.
 * Otherwise every paired device is tried, iPhone-named ones first: several phones are often paired
 * and the one in the car is not necessarily the only "iPhone" in the list.
 */
/**
 * Without a chosen phone every paired one is tried: the one that last connected ([preferred]) first, then iPhones.
 * Name order alone tried another driver's "iPhone (Нина)" for 10 s before the user's own "APn" (2026-10-12).
 */
fun wirelessPeerCandidates(peers: List<WirelessBluetoothPeer>, selected: String?, preferred: String? = null): List<WirelessBluetoothPeer> {
    if (!selected.isNullOrBlank()) return listOf(selectWirelessPeer(peers, selected))
    if (peers.isEmpty()) throw IOException(tr("Сопрягите iPhone через штатный Bluetooth магнитолы", "Pair the iPhone through the head unit's own Bluetooth"))
    return peers.sortedWith(compareByDescending<WirelessBluetoothPeer> { preferred != null && it.address.equals(preferred, true) }
        .thenByDescending { it.name.contains("iPhone", true) })
}

fun selectWirelessPeer(peers: List<WirelessBluetoothPeer>, selected: String?): WirelessBluetoothPeer {
    if (!selected.isNullOrBlank()) return peers.firstOrNull { it.address.equals(selected, true) }
        ?: throw IOException(tr("Выбранный iPhone больше не сопряжён; выберите его в настройках coorlay", "The chosen iPhone is no longer paired; choose it in coorlay settings"))
    val iphones = peers.filter { it.name.contains("iPhone", true) }
    if (iphones.size == 1) return iphones.single()
    if (peers.size == 1) return peers.single()
    throw IOException(if (peers.isEmpty()) tr("Сопрягите iPhone через штатный Bluetooth магнитолы", "Pair the iPhone through the head unit's own Bluetooth")
        else tr("Выберите iPhone в настройках coorlay: сопряжено несколько устройств", "Choose the iPhone in coorlay settings: several devices are paired"))
}
