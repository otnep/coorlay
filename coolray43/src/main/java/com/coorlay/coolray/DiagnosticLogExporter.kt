package com.coorlay.coolray

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import com.coorlay.UiText.tr
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogExporter {
    // Compiled once: the journal export sanitizes tens of thousands of lines on the head unit's ARM CPU.
    private val privateKey = Regex("(?is)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----")
    private val secret = Regex("(?i)(password|passphrase|authorization|privateKey|sessionKey)\\s*[:=]\\s*[^\\r\\n]+")
    private val longHex = Regex("[0-9a-fA-F]{32,}")
    private val macAddress = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")

    /** The newest journal is exported whole; the rotated one only up to this many trailing bytes. */
    internal const val PREVIOUS_JOURNAL_TAIL_BYTES = 2L * 1024 * 1024

    /** Folder where the firmware's logTool.sh writes one logcat file per ignition/rotation. */
    internal const val HEAD_UNIT_LOG_DIR = "/storage/emulated/0/KC2Log"
    /** A newest head-unit log below this size (fresh ignition or rotation) is joined by the previous one. */
    internal const val HEAD_UNIT_LOG_SMALL_BYTES = 1L * 1024 * 1024
    /** How far back from the newest head-unit log to copy. */
    internal const val HEAD_UNIT_LOG_WINDOW_MS = 10L * 60 * 1000
    /** Upper bound for all copied head-unit logs together (the flash drive copy runs while the user waits). */
    internal const val HEAD_UNIT_LOG_TOTAL_BYTES = 48L * 1024 * 1024
    /** Larger head-unit logs are copied from this many trailing bytes. */
    internal const val HEAD_UNIT_LOG_MAX_BYTES = 64L * 1024 * 1024

    class CopiedLog(val source: File, val output: File, val sourceBytes: Long, val skipped: Long)

    /**
     * Every `*_KC2Log_*.txt` written in the [HEAD_UNIT_LOG_WINDOW_MS] before the newest one (logTool.sh starts a new
     * ~1.3 MB file every 20–60 s, so two files missed most of a drive), newest first, within
     * [HEAD_UNIT_LOG_TOTAL_BYTES]; plus the previous file when the newest one is still small.
     */
    internal fun headUnitLogs(directory: File = File(HEAD_UNIT_LOG_DIR)): List<File> {
        val logs = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.contains("_KC2Log_") && it.name.endsWith(".txt") }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        val newest = logs.firstOrNull() ?: return emptyList()
        val wanted = logs.filterIndexed { index, file ->
            newest.lastModified() - file.lastModified() <= HEAD_UNIT_LOG_WINDOW_MS ||
                (index == 1 && newest.length() < HEAD_UNIT_LOG_SMALL_BYTES)
        }
        var total = 0L
        return wanted.takeWhile { file -> (total == 0L || total + file.length() <= HEAD_UNIT_LOG_TOTAL_BYTES).also { total += file.length() } }
    }

    /**
     * Copies [source] next to [report] under an ASCII name (the firmware names contain Chinese). logTool.sh is
     * still appending to the newest file, so only the length seen at the start is copied.
     */
    internal fun copyHeadUnitLog(source: File, report: File, number: Int, maxBytes: Long = HEAD_UNIT_LOG_MAX_BYTES): CopiedLog {
        val output = File(report.parentFile, report.nameWithoutExtension + "-kc2log-$number.txt")
        check(output.createNewFile()) { "Head-unit log destination already exists: ${output.name}" }
        try {
            val length = source.length()
            val skip = (length - maxBytes).coerceAtLeast(0)
            RandomAccessFile(source, "r").use { input ->
                input.seek(skip)
                FileOutputStream(output).use { stream ->
                    // Latin-1 maps every byte to one char and back, so other apps' lines (Chinese UTF-8 included)
                    // are copied byte for byte; only coorlay's own lines are sanitized: builds up to 0.0.44b-19
                    // wrote raw packet bodies and the Wi-Fi passphrase to logcat under the xcertplay tags.
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(
                        java.nio.channels.Channels.newInputStream(input.channel).let { BoundedInput(it, length - skip) },
                        Charsets.ISO_8859_1), 64 * 1024)
                    val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(stream, Charsets.ISO_8859_1), 64 * 1024)
                    copyLines(reader, writer)
                    writer.flush()
                    stream.fd.sync()
                }
            }
            output.setReadable(true, false)
            return CopiedLog(source, output, length, skip)
        } catch (error: Exception) { output.delete(); throw error }
    }

    /** Copies lines keeping their exact endings; lines tagged by coorlay pass through [sanitize]. */
    internal fun copyLines(reader: java.io.Reader, writer: java.io.Writer) {
        val line = StringBuilder()
        fun flush() {
            if (line.isEmpty()) return
            val text = line.toString()
            // The firmware's own wpa_supplicant prints the Wi-Fi Direct passphrase (P2P-GROUP-STARTED, 2026-10-12).
            writer.write(if (OWN_TAGS.any(text::contains) || text.contains("passphrase")) sanitize(text) else text)
            line.setLength(0)
        }
        val buffer = CharArray(64 * 1024)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            var start = 0
            for (i in 0 until read) if (buffer[i] == '\n') {
                line.append(buffer, start, i + 1 - start); flush(); start = i + 1
            }
            line.append(buffer, start, read - start)
        }
        flush()
    }
    private val OWN_TAGS = listOf(" coorlay", " xcertplay") // " xcertplay": tags of builds before 1.0.0

    /** Reads at most [left] bytes: the firmware is still appending to the newest head-unit log. */
    private class BoundedInput(private val input: java.io.InputStream, private var left: Long) : java.io.InputStream() {
        override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }
    }

    fun sanitize(text: String): String = if (!mightHoldSecret(text)) text else text
        .replace(privateKey, "[private key redacted]")
        .replace(secret, "$1=[redacted]")
        .replace(longHex, "[redacted]")
        .replace(macAddress, "[device]")

    /**
     * One pass over [text] for what the four patterns need: a 32-character hex run, a MAC-like "hh:" chain, or a
     * keyword ("-----BEGIN", password, passphrase, authorization, privateKey, sessionKey). Running the regexes on
     * every one of ~80 000 journal lines took most of the 45–50 s a log save needed on the head unit (2026-10-12).
     */
    internal fun mightHoldSecret(text: String): Boolean {
        var hexRun = 0
        for (i in text.indices) {
            val c = text[i]
            if (isHex(c)) { if (++hexRun >= 32) return true; continue }
            if (c == ':' && hexRun >= 2 && isMac(text, i - 2)) return true
            hexRun = 0
            when (c) {
                '-' -> if (text.startsWith("-----BEGIN", i)) return true
                'p', 'P', 'a', 'A', 's', 'S' -> for (word in KEYWORDS) if (text.regionMatches(i, word, 0, word.length, ignoreCase = true)) return true
            }
        }
        return false
    }
    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    /** "hh:hh:hh:hh:hh:hh" from [start]; a clock "20:16:17.000" is not one. */
    private fun isMac(text: String, start: Int): Boolean {
        if (start < 0 || start + 17 > text.length) return false
        for (k in 0 until 17) {
            val c = text[start + k]
            if (if (k % 3 == 2) c != ':' else !isHex(c)) return false
        }
        return true
    }
    private val KEYWORDS = listOf("password", "passphrase", "authorization", "privateKey", "sessionKey")

    /**
     * The Wi-Fi Direct group's frequency in MHz from the firmware's wpa_supplicant line in the newest KC2Log
     * ("P2P-GROUP-STARTED <iface> GO ... freq=2412"): API 18 does not expose the channel. Null when not found.
     */
    internal fun p2pGroupFrequency(iface: String, logs: List<File> = headUnitLogs()): Int? {
        val marker = "P2P-GROUP-STARTED $iface "
        for (log in logs.take(2)) {
            val found = journalTail(log, 512L * 1024) { _, lines ->
                lines.filter { marker in it }.lastOrNull()
            } ?: continue
            return Regex("""\bfreq=(\d+)""").find(found)?.groupValues?.get(1)?.toIntOrNull()
        }
        return null
    }

    /** IEEE channel number of [mhz] (2.4 and 5 GHz). */
    internal fun wifiChannel(mhz: Int): Int = when (mhz) {
        2484 -> 14
        in 2412..2472 -> (mhz - 2407) / 5
        in 5000..5900 -> (mhz - 5000) / 5
        else -> 0
    }

    /** Lines of [file] within [maxBytes] of its end; [block] gets the skipped byte count, or null when nothing was cut. */
    internal fun <T> journalTail(file: File, maxBytes: Long, block: (skipped: Long?, Sequence<String>) -> T): T {
        val length = file.length()
        val skip = (length - maxBytes).coerceAtLeast(0)
        return file.inputStream().use { input ->
            var skipped = 0L
            while (skipped < skip) {
                val step = input.skip(skip - skipped)
                if (step <= 0) break
                skipped += step
            }
            if (skip > 0) {
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break
                    skipped++
                    if (byte == '\n'.code) break
                }
            }
            input.bufferedReader(Charsets.UTF_8).useLines { block(if (skip > 0) skipped else null, it) }
        }
    }

    fun storageRoots(): List<File> {
        val roots = mutableListOf(Environment.getExternalStorageDirectory())
        System.getenv("SECONDARY_STORAGE")?.split(File.pathSeparator)?.filter { it.isNotBlank() }?.forEach { roots += File(it) }
        for (base in listOf("/mnt", "/storage", "/mnt/media_rw")) {
            File(base).listFiles()?.filter {
                it.isDirectory && it.name !in setOf("asec", "obb", "secure", "shell", "emulated", "runtime", "self", "media_rw")
            }?.let(roots::addAll)
        }
        return roots.distinctBy { it.absolutePath }.sortedWith(compareBy<File>(
            { if (it.name.contains("usb", true) || it.name.contains("udisk", true)) 0 else 1 },
            { it.absolutePath }))
    }

    fun save(context: Context, directory: File, bluetooth: BluetoothSnapshot?): File {
        require(directory.isDirectory) { tr("Папка не существует", "The folder does not exist") + ": ${directory.absolutePath}" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val destination = File(directory, "coorlay-$stamp.txt")
        try {
            val output = FileOutputStream(destination)
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                fun section(title: String, text: String) {
                    writer.write("\n===== $title =====\n")
                    writer.write(sanitize(text)); writer.write("\n"); writer.flush()
                }
                section("Device", ConnectionDiagnostics.platform(context).joinToString("\n"))
                section("Settings", ConnectionDiagnostics.settings(context).joinToString("\n"))
                section("Bluetooth", bluetooth?.describe() ?: "Bluetooth diagnostics not finished")
                section("Built-in Neusoft Bluetooth", OemBluetoothDiagnostics.collect(context))
                section("Wi-Fi and head-unit hotspot", ConnectionDiagnostics.wifi(context).joinToString("\n"))
                section("USB now", ConnectionDiagnostics.usb(context).joinToString("\n"))
                section("System and network", system(context))
                section("CPU load (1 s)", cpuLoad())
                // Newest first: a report cut short (drive pulled early) must still hold the latest run.
                for ((name, limit) in listOf("connection-journal.txt" to Long.MAX_VALUE,
                        "connection-journal.previous.txt" to PREVIOUS_JOURNAL_TAIL_BYTES)) {
                    val journal = File(context.filesDir, name)
                    if (journal.exists()) {
                        writer.write("\n===== App journal ($name) =====\n")
                        journalTail(journal, limit) { skipped, lines ->
                            if (skipped != null) writer.write("… beginning skipped: $skipped bytes …\n")
                            lines.forEach { writer.write(sanitize(it)); writer.write("\n") }
                        }
                        writer.flush()
                    }
                }
                section("Logcat (messages visible to the app)", logcat())
                writer.flush()
                // The success dialog means the removable drive has received the complete file.
                output.fd.sync()
            }
            destination.setReadable(true, false)
        } catch (error: Exception) {
            // Do not leave a misleading success-looking partial report on the flash drive.
            destination.delete()
            throw error
        }
        return destination
    }

    /** Top CPU consumers over one second, to tell app/mod load apart from coorlay itself. */
    internal fun cpuLoad(sampleMillis: Long = 1000): String {
        fun totalJiffies(): Long = File("/proc/stat").useLines { lines ->
            lines.first().trim().split(Regex("\\s+")).drop(1).sumOf { it.toLongOrNull() ?: 0L }
        }
        fun processJiffies(): Map<Int, Pair<String, Long>> {
            val result = HashMap<Int, Pair<String, Long>>()
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                try {
                    val stat = File(dir, "stat").readText()
                    val name = stat.substringAfter('(').substringBeforeLast(')')
                    val fields = stat.substringAfterLast(')').trim().split(' ')
                    // After the name: state ppid ... utime is field 14 and stime 15 of the full line.
                    result[pid] = name to (fields[11].toLong() + fields[12].toLong())
                } catch (_: Exception) { }
            }
            return result
        }
        return try {
            val total0 = totalJiffies(); val before = processJiffies()
            Thread.sleep(sampleMillis)
            val total1 = totalJiffies(); val after = processJiffies()
            val elapsed = (total1 - total0).coerceAtLeast(1)
            val load = File("/proc/loadavg").readText().trim()
            val top = after.mapNotNull { (pid, now) ->
                val previous = before[pid] ?: return@mapNotNull null
                val used = now.second - previous.second
                if (used <= 0) null else Triple(pid, now.first, used)
            }.sortedByDescending { it.third }.take(12)
            (listOf("loadavg $load") + top.map { (pid, name, used) ->
                val cmd = try { File("/proc/$pid/cmdline").readText().replace(0.toChar(), ' ').trim() } catch (_: Exception) { "" }
                "%5.1f%%  pid=%d  %s  %s".format(Locale.US, used * 100.0 / elapsed, pid, name, cmd)
            }).joinToString("\n")
        } catch (error: Exception) {
            "unavailable: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    private fun system(context: Context): String {
        val result = mutableListOf<String>()
        try { result += "VPN prepared=${android.net.VpnService.prepare(context) == null}" }
        catch (error: Exception) { result += "VPN: ${error.javaClass.simpleName}: ${error.message}" }
        for (permission in listOf("BLUETOOTH", "BLUETOOTH_ADMIN", "WRITE_EXTERNAL_STORAGE", "RECORD_AUDIO")) {
            result += "Permission $permission=" + (context.packageManager.checkPermission(
                "android.permission.$permission", context.packageName) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        for (path in listOf("/proc/version", "/proc/mounts", "/proc/net/dev")) {
            try { result += "$path:\n" + File(path).readText().take(32000) }
            catch (error: Exception) { result += "$path: ${error.javaClass.simpleName}" }
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                result += "Network ${iface.name}: up=${iface.isUp}; loopback=${iface.isLoopback}; mtu=${iface.mtu}"
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) result += "  address=${addresses.nextElement().hostAddress}"
            }
        } catch (error: Exception) { result += "Network: ${error.javaClass.simpleName}: ${error.message}" }
        try {
            val relevant = context.packageManager.getInstalledApplications(0).filter {
                it.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                    listOf("bluetooth", "neusoft", "geely", "carusb", "carbt").any { name -> it.packageName.contains(name, true) }
            }
            result += "System USB/Bluetooth/head-unit packages: " + relevant.joinToString { it.packageName }
            for (app in relevant.filter { it.packageName.contains("bluetooth", true) || it.packageName.contains("btphone", true) }) {
                val info = context.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SERVICES)
                result += "Bluetooth package=${app.packageName}; enabled=${app.enabled}; version=${info.versionName}; services=" +
                    info.services.orEmpty().joinToString { "${it.name}(enabled=${it.enabled}; exported=${it.exported}; permission=${it.permission})" }
            }
        } catch (error: Exception) { result += "System packages: ${error.javaClass.simpleName}" }
        result += "Storage: " + storageRoots().joinToString { "${it.absolutePath} (write=${it.canWrite()})" }
        return result.joinToString("\n")
    }

    private fun logcat(): String {
        val output = StringBuffer()
        return try {
            // Android 4.3 limits this to this UID; no privileged READ_LOGS permission is requested.
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "3000"))
            val reader = Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { if (output.length < 512000) output.append(it).append('\n') }
                    }
                } catch (_: Exception) { }
            }, "coolray-logcat-export").apply { isDaemon = true; start() }
            reader.join(3000)
            process.destroy()
            reader.join(300)
            if (reader.isAlive) output.append("\nLogcat: reading time limited\n")
            output.toString().ifEmpty { "Logcat returned no messages for the app" }
        } catch (error: Exception) { "Logcat: ${error.javaClass.simpleName}: ${error.message}" }
    }
}
