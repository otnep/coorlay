package com.coorlay.transport

import org.junit.Assert.*
import org.junit.Test

class LegacyNcmStartupPolicyTest {
    @Test fun completelySilentPipeExpiresAfterTheFirstOutboundAttempt() {
        var now = 10_000L; val policy = LegacyNcmStartupPolicy { now }
        now += 120_000
        assertFalse(policy.timedOut())
        policy.attempted()
        now += 59_999
        assertFalse(policy.timedOut())
        now++
        assertTrue(policy.timedOut())
    }
    @Test fun ongoingTimeoutsDoNotMoveTheStartupDeadline() {
        var now = 0L; val policy = LegacyNcmStartupPolicy { now }
        policy.attempted()
        now = 59_000; policy.attempted()
        now = 60_000
        assertTrue(policy.timedOut())
    }
    @Test fun successfulTransmissionOrReceptionAllowsAnIndefinitelyIdleSession() {
        var now = 0L; val policy = LegacyNcmStartupPolicy { now }
        policy.attempted(); policy.progressed()
        now = 24 * 60 * 60 * 1000L
        assertFalse(policy.timedOut())
    }
}
