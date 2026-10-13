package com.coorlay.coolray

import org.junit.Assert.*
import org.junit.Test

class UsbRecoveryPolicyTest {
    @org.junit.Test fun silentNcmStartupCanRecoverWithinTheExistingRetryBudget() {
        val policy = UsbRecoveryPolicy()
        assertEquals(2000L, policy.request("USB NCM data path did not start within 60s"))
        assertNull(policy.request("USB NCM data path did not start within 60s"))
        policy.started()
        assertEquals(4000L, policy.request("USB NCM data path did not start within 60s"))
    }
    @Test fun disconnectedUsbBacksOffAndStopsAfterThreeAttempts() {
        val policy = UsbRecoveryPolicy()
        assertEquals(2000L, policy.request("USB endpoint 85 failed: errno=108"))
        assertNull(policy.request("USB endpoint 88 failed: errno=19")) // same failure wave
        policy.started()
        assertEquals(4000L, policy.request("iPhone USB re-enumeration timed out"))
        policy.started()
        assertEquals(8000L, policy.request("Could not read USB device descriptor"))
        policy.started()
        assertNull(policy.request("USB endpoint 85 failed: errno=108"))
    }

    @Test fun authenticationAndDeniedPermissionNeverTriggerReconnect() {
        val policy = UsbRecoveryPolicy()
        for (error in listOf("iPhone USB permission was denied", "InvalidPairRecord",
            "MFi authentication rejected", "USB endpoint 85 failed: errno=110")) {
            assertNull(error, policy.request(error))
        }
        assertEquals(2000L, policy.request("Could not read USB configuration header"))
    }

    @Test fun successfulSessionOrManualRequestResetsRecoveryBudget() {
        val policy = UsbRecoveryPolicy()
        assertEquals(2000L, policy.request("USB endpoint 85 failed: errno=71"))
        policy.reset()
        assertEquals(2000L, policy.request("USB endpoint 85 failed: errno=19"))
        assertNull(UsbRecoveryPolicy().request("USB endpoint 85 failed: errno=190"))
    }
}
