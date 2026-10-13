package com.coorlay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Android 4.3 IoBridge.bind rejects an explicit IPv6 unspecified address without a scope.
 * Its default wildcard takes the native dual-stack path and still accepts IPv6 traffic.
 * Preserve concrete/scoped addresses when a listener must bind to a particular interface.
 */
internal fun airPlaySocketAddress(address: InetAddress? = null, port: Int = 0): InetSocketAddress =
    if (address == null || address.isAnyLocalAddress) InetSocketAddress(port)
    else InetSocketAddress(address, port)
