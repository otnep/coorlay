package com.coorlay.airplay

import java.net.SocketException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayFailureDiagnosticTest {
    @Test
    fun socketCauseAndItsLocationSurviveLargeOuterException() {
        val cause = SocketException("bind failed: EADDRNOTAVAIL")
        cause.stackTrace = arrayOf(StackTraceElement("NtpClock", "listen", "NtpClock.kt", 43))
        val error = IllegalStateException("outer".repeat(5_000), cause)
        error.stackTrace = Array(1_000) { StackTraceElement("Caller", "setup", "Caller.kt", it + 1) }

        val diagnostic = airPlayFailureDiagnostic("handler failed method=SETUP cseq=6", error)

        assertTrue(diagnostic.length < 16_384)
        assertTrue(diagnostic.contains("java.lang.IllegalStateException"))
        assertTrue(diagnostic.contains("stack frames omitted"))
        assertTrue(diagnostic.contains("Caused by: java.net.SocketException: bind failed: EADDRNOTAVAIL"))
        assertTrue(diagnostic.contains("NtpClock.listen(NtpClock.kt:43)"))
    }

    @Test
    fun cyclicCauseCannotHangLogExport() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second", first)
        first.initCause(second)

        val diagnostic = airPlayFailureDiagnostic("SETUP", first)

        assertTrue(diagnostic.contains("IllegalArgumentException: second"))
        assertTrue(diagnostic.contains("cyclic causes omitted"))
    }

    @Test
    fun DiagnosticRedactsSecretFieldsAndLongHexWithoutHidingErrno() {
        val diagnostic = airPlayFailureDiagnostic(
            "SETUP",
            SocketException("writeKey=not-for-the-journal bytes=${"ab".repeat(32)} errno=99"),
        )

        assertFalse(diagnostic.contains("not-for-the-journal"))
        assertFalse(diagnostic.contains("ab".repeat(32)))
        assertTrue(diagnostic.contains("writeKey=[redacted]"))
        assertTrue(diagnostic.contains("errno=99"))
    }
}
