package com.coorlay.network

import java.net.BindException
import org.junit.Assert.*
import org.junit.Test

class LegacyUsbListenerBindRetryTest {
    @Test fun delayedVpnAddressSucceedsWithoutRebuildingUsbSession() {
        var clock = 0L
        var calls = 0
        val value = retryUsbListenerBind(attempt = {
            if (++calls < 4) throw BindException("bind failed: EADDRNOTAVAIL")
            "listening"
        }, now = { clock }, pause = { clock += it })
        assertEquals("listening", value)
        assertEquals(300L, clock)
    }

    @Test fun occupiedPortFailsImmediately() {
        var calls = 0
        try {
            retryUsbListenerBind(attempt = {
                calls++
                throw BindException("bind failed: EADDRINUSE")
            }, pause = { fail("Port conflict must not be retried") })
            fail("Occupied port must remain an error")
        } catch (_: BindException) { assertEquals(1, calls) }
    }

    @Test fun missingAddressStopsAtDeadlineAndPreservesFailure() {
        var clock = 0L
        val failure = BindException("Cannot assign requested address")
        try {
            retryUsbListenerBind(attempt = { throw failure }, timeoutMillis = 250,
                now = { clock }, pause = { clock += it })
            fail("Unusable VPN address must not wait forever")
        } catch (error: BindException) {
            assertSame(failure, error)
            assertEquals(250L, clock)
        }
    }
}
