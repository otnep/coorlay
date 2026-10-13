package com.coorlay.coolray

/** Bounded recovery for USB disappearance; permission and authentication errors remain visible. */
internal class UsbRecoveryPolicy {
    private var attempts = 0
    private var pending = false

    fun reset() { attempts = 0; pending = false }
    fun started() { pending = false }

    fun request(message: String): Long? {
        if (pending || attempts >= 3 || !transient(message)) return null
        pending = true
        return 2000L shl attempts++
    }

    private fun transient(message: String): Boolean =
        message.contains("Could not read USB device descriptor") ||
        message.contains("Could not read USB configuration") ||
        message.contains("Could not open Apple USB device") ||
        message.contains("iPhone USB re-enumeration timed out") ||
        message.contains("USB NCM data path did not start within 60s") ||
        (message.contains("USB endpoint") && Regex("errno=(19|32|71|108)\\b").containsMatchIn(message))
}
