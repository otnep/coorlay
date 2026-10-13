package com.coorlay.orchestration

import com.coorlay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WiredRuntimeConfigTest {
    @Test fun wiredStartupDoesNotRequireAWirelessHotspotSsid() {
        assertEquals(CarPlayTransport.WIRED, config(CarPlayTransport.WIRED).transport)
    }

    @Test fun wiredStartupIgnoresAnUnusedWirelessHotspotPassword() {
        config(CarPlayTransport.WIRED, passphrase = "short")
    }

    @Test fun wirelessManualStartupStillRequiresASsid() {
        assertThrows(IllegalArgumentException::class.java) { config(CarPlayTransport.WIRELESS) }
    }

    @Test fun wirelessManualStartupStillChecksPasswordLength() {
        assertThrows(IllegalArgumentException::class.java) {
            config(CarPlayTransport.WIRELESS, ssid = "TestCar", passphrase = "short")
        }
    }

    private fun config(
        transport: CarPlayTransport,
        ssid: String? = null,
        passphrase: String? = null,
    ) = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        transport = transport,
        wirelessHotspotMode = WirelessHotspotMode.MANUAL,
        manualHotspotSsid = ssid,
        manualHotspotPassphrase = passphrase,
        identification = Iap2IdentificationConfig(
            name = "test", modelIdentifier = "test", manufacturer = "test",
            serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
            carPlayUsbInterfaceNumber = 3,
        ),
    )
}
