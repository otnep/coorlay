package com.coorlay.transport

/** A probe reads complete descriptors or leaves the previous cache intact. */
internal object LegacyUsbDescriptorReader {
    fun read(
        transfer: (Int, ByteArray, Int) -> Int,
        transferTimeoutMillis: Int = 1000,
        budgetMillis: Long? = null,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    ): List<LegacyUsbConfiguration> {
        require(transferTimeoutMillis > 0 && (budgetMillis == null || budgetMillis > 0))
        val started = nowMillis()
        fun descriptor(value: Int, length: Int, failure: String): ByteArray {
            val remaining = budgetMillis?.let { it - (nowMillis() - started) }
            check(remaining == null || remaining > 0) { "USB descriptor probe budget expired" }
            val timeout = remaining?.coerceAtMost(transferTimeoutMillis.toLong())?.toInt()
                ?: transferTimeoutMillis
            val bytes = ByteArray(length)
            check(transfer(value, bytes, timeout) == length) { failure }
            return bytes
        }
        val device = descriptor(0x100, 18, "Could not read USB device descriptor")
        val count = device[17].toInt() and 255
        require(count in 1..16) { "Invalid number of USB configurations" }
        val all = java.io.ByteArrayOutputStream()
        for (index in 0 until count) {
            val header = descriptor(0x200 or index, 9, "Could not read USB configuration header")
            val length = (header[2].toInt() and 255) or ((header[3].toInt() and 255) shl 8)
            require(length in 9..65535) { "Invalid configuration length" }
            all.write(descriptor(0x200 or index, length, "Could not read USB configuration"))
        }
        return LegacyUsbDescriptors.parse(all.toByteArray())
    }
}
