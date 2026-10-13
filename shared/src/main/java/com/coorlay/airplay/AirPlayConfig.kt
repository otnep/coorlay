package com.coorlay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    /** Display feature bits; null keeps the main-screen default (high-fidelity touch and knobs). */
    val features: Int? = null,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val manufacturer: String = "coorlay",
    val model: String = "coorlay",
    val oemLabel: String = "coorlay",
    val icons: List<AirPlayIcon> = emptyList(),
    /** iOS 27 video in car (see [VideoInCar]); video plays only while [VideoInCar.allowed]. */
    val videoInCar: Boolean = false,
    val detailedDiagnostics: Boolean = true,
    /**
     * Wireless sessions: also offer AAC-LC for the main audio types. Over Wi-Fi the iPhone set up no audio stream
     * at all for PCM-only formats (2026-10-08) and played music on its speaker or over Bluetooth A2DP.
     */
    val compressedAudio: Boolean = false,
    /**
     * CarPlay's main buffered audio (see [BufferedAudioStream], DiPlay 0.2.15): apps that support it
     * (Apple Music) send music ahead of time over TCP, so short Wi-Fi gaps do not interrupt it.
     */
    val mainBufferedAudio: Boolean = false,
    /**
     * Offer Opus next to PCM for voice, prompts and the microphone (wireless). Offered only PCM over Wi-Fi,
     * the iPhone sets up no CarPlay audio at all and falls back to Bluetooth (DiPlay 0.2.16, ea898e3).
     * Android 4.3 has no Opus codec; coorlay decodes and encodes it in software (Concentus).
     */
    val opusAudio: Boolean = false,
)

/** The offer, SETUP and controls must all honor the user's audio-output setting. */
internal val AirPlayConfig.bufferedAudioOutputEnabled: Boolean
    get() = mainBufferedAudio && !disableAudioOutput
