package com.coorlay.coolray

import android.test.InstrumentationTestRunner
import junit.framework.TestSuite

/** Old framework discovery otherwise uses the shared-library class loader on API 18. */
class CoolrayTestRunner : InstrumentationTestRunner() {
    override fun onCreate(arguments: android.os.Bundle?) {
        // Dalvik does not load classes2.dex from the separate instrumentation APK itself.
        androidx.multidex.MultiDex.installInstrumentation(context, targetContext)
        // Framework instrumentation may start tests before Application.onCreate completes.
        LegacyPrng.install()
        super.onCreate(arguments)
    }
    override fun getLoader(): ClassLoader = CoolrayTestRunner::class.java.classLoader!!
    override fun getAllTests(): TestSuite = TestSuite().apply {
        addTestSuite(PlatformSmokeTest::class.java)
        addTestSuite(ConnectionFlowTest::class.java)
        addTestSuite(UsbTlsTest::class.java)
        addTestSuite(BluetoothEnableTest::class.java)
        addTestSuite(HotspotTest::class.java)
        addTestSuite(AirPlaySetupTest::class.java)
        addTestSuite(VideoCryptoTest::class.java)
        addTestSuite(AnwPlatformTest::class.java)
        addTestSuite(NcmNeighborTest::class.java)
        addTestSuite(DisplayResolutionTest::class.java)
        addTestSuite(SmoothVideoTest::class.java)
        addTestSuite(ClusterMediaTest::class.java)
        addTestSuite(OpusDalvikTest::class.java)
    }
}
