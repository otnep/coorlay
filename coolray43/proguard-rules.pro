# coorlay release (R8). Shrinks and optimizes; names stay readable in journals and stack traces.
-dontobfuscate

# Conscrypt: its JCA provider and JNI find classes and methods by name; LegacyVideoCrypto
# reflects org.conscrypt.NativeCrypto.
-keep class org.conscrypt.** { *; }

# java.security.Provider creates the SPI from its class name (LegacyPrng, Android 4.3).
-keep class com.coorlay.coolray.KernelRandomSpi { public <init>(); }

# Wireless mDNS. Small; kept whole instead of tracking its internal lookups.
-keep class javax.jmdns.** { *; }
# Platform-internal SSL classes of old Android versions, present on the device, not in the SDK.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
