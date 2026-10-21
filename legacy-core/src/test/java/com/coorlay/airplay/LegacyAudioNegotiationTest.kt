package com.coorlay.airplay

import org.junit.Assert.*
import org.junit.Test

class LegacyAudioNegotiationTest {
    private fun info(microphone: Boolean = true, compressed: Boolean = false, opus: Boolean = false) = AirPlayInfoPlist.build(AirPlayConfig(
        "Coolray", "02:00:00:00:00:02", "02:00:00:00:00:02", "220.68",
        AirPlayDisplayConfig(1920, 640, fps = 30), hevc = false, microphone = microphone, compressedAudio = compressed,
        opusAudio = opus))
    private fun output(info: Map<String, Any?>, type: Int, audioType: String) = (info["audioFormats"] as List<*>)
        .map { it as Map<*, *> }.first { it["type"] == type && it["audioType"] == audioType }["audioOutputFormats"] as Int
    @Test fun wirelessAlsoOffersAacLcForMainAudioWiredStaysPcm() {
        for (audioType in listOf("media", "default", "alert")) {
            assertEquals(0, output(info(), 100, audioType) and 0x800000)
            assertTrue(output(info(compressed = true), 100, audioType) and 0x800000 != 0)
            assertTrue(output(info(compressed = true), 100, audioType) and 0xc000 != 0)
        }
        assertEquals(output(info(), 100, "telephony"), output(info(compressed = true), 100, "telephony"))
    }
    @Test fun voiceNegotiatesPcmAndMediaAacWithoutPlatformOpus() {
        val formats = info()["audioFormats"] as List<*>
        for (value in formats) {
            val format = value as Map<*, *>
            val output = format["audioOutputFormats"] as Int
            val input = format["audioInputFormats"] as? Int ?: 0
            assertEquals(0, output and 0x70000000)
            assertEquals(0, input and 0x70000000)
            if (format["audioType"] == "speechRecognition") assertTrue(input and 0x154 != 0)
            if (format["type"] == 102) assertTrue(output and 0xc00000 != 0)
        }
    }
    @Test fun wirelessOffersOpusForVoiceAndTheMicrophone() {
        val formats = (info(opus = true)["audioFormats"] as List<*>).map { it as Map<*, *> }
        for (audioType in listOf("default", "telephony", "speechRecognition")) {
            val format = formats.first { it["type"] == 100 && it["audioType"] == audioType }
            // Siri gets 16 kHz Opus only: 24/48 kHz needs the 48 kHz software decode, too slow on the unit.
            val opus = if (audioType == "speechRecognition") 0x10000000 else 0x70000000
            assertEquals(opus, (format["audioOutputFormats"] as Int) and 0x70000000)
            assertEquals(opus, (format["audioInputFormats"] as Int) and 0x70000000)
            // PCM stays offered next to Opus.
            assertTrue((format["audioInputFormats"] as Int) and 0x154 != 0)
        }
        // Music keeps its own formats; compatibility streams never carry Opus.
        assertEquals(0, formats.first { it["type"] == 102 }["audioOutputFormats"] as Int and 0x70000000)
        assertEquals(0, formats.first { it["audioType"] == "compatibility" }["audioOutputFormats"] as Int and 0x70000000)
    }
    @Test fun microphoneDisabledIsNotOfferedToPhone() {
        for (format in info(false)["audioFormats"] as List<*>)
            assertFalse((format as Map<*, *>).containsKey("audioInputFormats"))
    }
    @Test fun measuredDisplayIsAdvertisedAtItsNativeAspectAndH264() {
        val info = info()
        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(1920, display["widthPixels"])
        assertEquals(640, display["heightPixels"])
        assertEquals(30, display["maxFPS"])
        assertFalse(info.containsKey("hevcInfo"))
    }
}
