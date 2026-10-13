package com.coorlay.network

import java.net.SocketException

/** A newly established VPN address may be tentative while IPv6 DAD completes. */
internal fun <T> retryUsbListenerBind(
    attempt: () -> T,
    timeoutMillis: Long = 5000,
    now: () -> Long = { System.nanoTime() / 1_000_000L },
    pause: (Long) -> Unit = Thread::sleep,
    report: (String) -> Unit = {},
): T {
    val deadline = now() + timeoutMillis
    while (true) {
        try { return attempt() }
        catch (error: SocketException) {
            val message = error.message.orEmpty()
            val addressPending = message.contains("EADDRNOTAVAIL") ||
                message.contains("Cannot assign requested address", ignoreCase = true)
            val remaining = deadline - now()
            if (!addressPending || remaining <= 0) throw error
            report("VPN IPv6 address is not bindable yet; retrying AirPlay listener")
            pause(minOf(100L, remaining))
        }
    }
}
