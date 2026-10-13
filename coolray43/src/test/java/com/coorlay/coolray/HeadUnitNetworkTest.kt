package com.coorlay.coolray

import com.coorlay.orchestration.ManualHotspotValidation
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitNetworkTest {
    @Test fun generatedNetworksPassEveryCheckTheConnectionMakes() {
        repeat(200) {
            val network = HeadUnitNetwork.generate()
            network.validate() // the SoftAP rules
            assertNull(ManualHotspotValidation.validate(network.ssid, network.credential)) // CarPlayRuntimeConfig rules
            assertTrue(network.ssid, network.ssid.matches(Regex("coorlay-[A-Z2-9]{4}")))
            assertTrue(network.credential.length == 12)
        }
    }

    @Test fun twoHeadUnitsDoNotShareANetwork() {
        assertNotEquals(HeadUnitNetwork.generate().credential, HeadUnitNetwork.generate().credential)
    }
}
