package com.coorlay.transport

import java.io.IOException

/** Linux 3.0 rejects SETCONFIGURATION while any active interface has a driver. */
internal object LegacyUsbConfigurationSelector {
    interface Operations {
        fun select(configuration: Int): Int
        fun driver(iface: Int): String?
        fun detach(iface: Int): Int
        fun attach(iface: Int): Int
    }

    fun select(current: Int, target: Int, activeInterfaces: List<Int>?, io: Operations,
        log: (String) -> Unit) {
        log("USB configuration current=$current target=$target")
        if (current == target) return
        val first = io.select(target)
        log("USB SETCONFIGURATION target=$target result=$first")
        if (first >= 0) return
        if (first != -16) throw IOException("Could not select USB configuration $target: errno=${-first}")
        val ids = activeInterfaces?.distinct()
            ?: throw IOException("USB configuration $target is busy; current configuration $current has no cached descriptor")
        val owners = ids.associateWith { io.driver(it).also { name ->
            log("USB current interface=$it driver=${name ?: "unbound"}")
        } }
        if (owners.values.any { it == "usbfs" })
            throw IOException("USB configuration $target is busy: another userspace USB session owns an interface")
        val detached = mutableListOf<Int>()
        var changed = false
        try {
            for (id in ids) {
                if (owners[id] == null) continue
                val result = io.detach(id)
                log("USB detach current interface=$id result=$result")
                if (result < 0) throw IOException("Could not detach USB interface $id before configuration $target: errno=${-result}")
                if (result == 1) detached += id
            }
            val retry = io.select(target)
            log("USB SETCONFIGURATION retry target=$target result=$retry")
            if (retry < 0) throw IOException("Could not select USB configuration $target after releasing kernel drivers: errno=${-retry}")
            // The old interfaces disappear on a successful configuration change. Linux binds
            // the new ones; session claims detach only the interfaces used by iAP2/NCM.
            changed = true
        } finally {
            if (!changed) for (id in detached.asReversed()) {
                try { log("USB restore current interface=$id result=${io.attach(id)}") }
                catch (error: Exception) { log("USB restore current interface=$id failed: ${error.message}") }
            }
        }
    }
}
