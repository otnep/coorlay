package com.coorlay.coolray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticLogExporterTest {
    private fun journal(text: String) = File.createTempFile("journal", ".txt").apply { deleteOnExit(); writeText(text) }

    @Test fun smallJournalIsExportedWhole() {
        journalTailOf(journal("a\nb\n"), 100) { skipped, lines -> assertNull(skipped); assertEquals(listOf("a", "b"), lines) }
    }

    @Test fun largeJournalKeepsOnlyWholeTrailingLines() {
        // 8 bytes from the end lands inside "second"; the export starts at the next full line.
        journalTailOf(journal("first\nsecond\nthird\n"), 8) { skipped, lines ->
            assertEquals(13L, skipped)
            assertEquals(listOf("third"), lines)
        }
    }

    @Test fun wifiDirectFrequencyComesFromTheFirmwareLog() {
        val log = journal("x\n10-12 21:09:29.700 I wpa_supplicant: p2p0: P2P-GROUP-STARTED p2p-p2p0-0 GO ssid=\"D\" freq=2412 passphrase=\"p\"\n" +
            "10-12 21:11:13.430 I wpa_supplicant: p2p0: P2P-GROUP-STARTED p2p-p2p0-1 GO ssid=\"D\" freq=2437 passphrase=\"p\"\n")
        assertEquals(2437, DiagnosticLogExporter.p2pGroupFrequency("p2p-p2p0-1", listOf(log)))
        assertEquals(2412, DiagnosticLogExporter.p2pGroupFrequency("p2p-p2p0-0", listOf(log)))
        assertNull(DiagnosticLogExporter.p2pGroupFrequency("p2p-p2p0-2", listOf(log)))
        assertEquals(1, DiagnosticLogExporter.wifiChannel(2412))
        assertEquals(6, DiagnosticLogExporter.wifiChannel(2437))
        assertEquals(36, DiagnosticLogExporter.wifiChannel(5180))
    }

    @Test fun firmwareWifiDirectPassphraseIsRedactedInHeadUnitLogs() {
        val line = "10-12 21:09:29.700  3766  3766 I wpa_supplicant: p2p0: P2P-GROUP-STARTED p2p-p2p0-0 GO " +
            "ssid=\"DIRECT-zZ\" freq=2412 passphrase=\"sGWjeTli\" go_dev_addr=bc:82:5d:a6:c9:89\n"
        val other = "10-12 21:09:29.800  3000  3000 D Media : freq=2412\n"
        val out = java.io.StringWriter()
        DiagnosticLogExporter.copyLines(java.io.StringReader(line + other), out)
        assertFalse(out.toString().contains("sGWjeTli"))
        assertTrue(out.toString().contains("freq=2412 passphrase=[redacted]"))
        assertTrue(out.toString().endsWith(other))
    }

    @Test fun quickCheckSkipsOrdinaryLinesButNotSecrets() {
        assertFalse(DiagnosticLogExporter.mightHoldSecret("20:16:17.011 Сохранение лога: /mnt/udisk2 cafe 10:20:30"))
        assertFalse(DiagnosticLogExporter.mightHoldSecret("20:05:34.826 WLAN wlan0 rx=155pk/178KB err=0 drop=0"))
        assertTrue(DiagnosticLogExporter.mightHoldSecret("peer AA:bb:01:02:03:0f joined"))
        assertTrue(DiagnosticLogExporter.mightHoldSecret("key " + "a1".repeat(16)))
        assertTrue(DiagnosticLogExporter.mightHoldSecret("Passphrase: x"))
        assertTrue(DiagnosticLogExporter.mightHoldSecret("x -----BEGIN RSA PRIVATE KEY----- y"))
        assertEquals("peer [device] joined", DiagnosticLogExporter.sanitize("peer AA:bb:01:02:03:0f joined"))
    }

    @Test fun sanitizerStillRedactsSecrets() {
        assertEquals("password=[redacted]", DiagnosticLogExporter.sanitize("password=abc"))
        assertEquals("peer [device]", DiagnosticLogExporter.sanitize("peer 01:23:45:67:89:ab"))
    }

    @Test fun headUnitLogSelectionAddsThePreviousOnlyWhenTheNewestIsSmall() {
        val dir = createTempDir("kc2log")
        try {
            fun log(name: String, bytes: Int, modified: Long) = File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(modified) }
            val old = log("052575_KC2Log_a.txt", 10, 1_000_000)
            val newest = log("052577_KC2Log_b.txt", 10, 3_000_000)
            log("052576_KC2Log_c.log", 10, 4_000_000)
            log("other.txt", 10, 5_000_000)
            assertEquals(listOf(newest, old), DiagnosticLogExporter.headUnitLogs(dir))
            newest.writeBytes(ByteArray(DiagnosticLogExporter.HEAD_UNIT_LOG_SMALL_BYTES.toInt())); newest.setLastModified(3_000_000)
            assertEquals(listOf(newest), DiagnosticLogExporter.headUnitLogs(dir))
            // Files from the last ten minutes are all copied, newest first.
            val recent = log("052578_KC2Log_d.txt", 10, 3_000_000 - 9 * 60 * 1000)
            assertEquals(listOf(newest, recent), DiagnosticLogExporter.headUnitLogs(dir))
            assertEquals(emptyList<File>(), DiagnosticLogExporter.headUnitLogs(File(dir, "missing")))
        } finally { dir.deleteRecursively() }
    }

    @Test fun headUnitLogCopyKeepsTheTrailingBytesUnderAsciiName() {
        val dir = createTempDir("kc2copy")
        try {
            val source = File(dir, "052577_KC2Log_第6041次打火.txt").apply { writeText("0123456789") }
            val report = File(dir, "coorlay-1.txt").apply { writeText("r") }
            val copied = DiagnosticLogExporter.copyHeadUnitLog(source, report, 1, maxBytes = 4)
            assertEquals("coorlay-1-kc2log-1.txt", copied.output.name)
            assertEquals("6789", copied.output.readText())
            assertEquals(6L, copied.skipped); assertEquals(10L, copied.sourceBytes)
        } finally { dir.deleteRecursively() }
    }

    @Test fun headUnitLogCopySanitizesOnlyCoorlayLines() {
        val dir = createTempDir("kc2sanitize")
        try {
            val lines = "10-09 23:32:18.620  9158  9797 I coorlay-usb:   [2] passphrase: \"Secret12345\"\n" +
                "10-09 23:35:25.430  9158 10410 I xcertplay-usb:  bodyHex=62706c6973743030d301020304050654747970\n" +
                "10-09 23:35:34.410  3672  3672 D PhoneStatusBar: 空调 panel 0123456789abcdef0123456789abcdef\r\n" +
                "tail without newline"
            val source = File(dir, "1_KC2Log_x.txt").apply { writeBytes(lines.toByteArray(Charsets.UTF_8)) }
            val report = File(dir, "coorlay-2.txt").apply { writeText("r") }
            val copied = DiagnosticLogExporter.copyHeadUnitLog(source, report, 1).output.readText(Charsets.UTF_8)
            assertFalse(copied, "Secret12345" in copied)
            assertFalse(copied, "62706c6973743030" in copied)
            // Other apps' lines, line endings and multi-byte text stay byte for byte.
            assertTrue(copied, copied.contains("PhoneStatusBar: 空调 panel 0123456789abcdef0123456789abcdef\r\n"))
            assertTrue(copied, copied.endsWith("tail without newline"))
        } finally { dir.deleteRecursively() }
    }

    private fun journalTailOf(file: File, max: Long, check: (Long?, List<String>) -> Unit) =
        DiagnosticLogExporter.journalTail(file, max) { skipped, lines -> check(skipped, lines.toList()) }
}
