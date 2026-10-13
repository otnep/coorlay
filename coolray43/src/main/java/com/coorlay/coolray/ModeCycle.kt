package com.coorlay.coolray

/**
 * Puts CarPlay into the steering-wheel MODE rotation.
 *
 * The Neusoft firmware cycles MODE only through its own sources (radio, USB, Bluetooth) and has no
 * slot for a third-party app. coorlay therefore watches the source changes that follow MODE: once
 * the firmware comes back to a source already visited since CarPlay was left, the rotation has
 * wrapped, and that press belongs to CarPlay. With radio and Bluetooth this yields
 * CarPlay → Bluetooth → radio → CarPlay. (Skipping Bluetooth audio while CarPlay runs was removed on 2026-10-13
 * at the driver's request.)
 */
internal class ModeCycle(private val clock: () -> Long) {
    /** Sources visited since CarPlay lost the speakers; null while CarPlay is the current source. */
    private var visited: MutableSet<String>? = null
    private var modeReleasedAt = Long.MIN_VALUE / 2

    fun modePressed() { modeReleasedAt = clock() }

    fun carPlayActive() { visited = null }

    /** True when this source change ends a MODE rotation and CarPlay should take over. */
    fun sourceChanged(source: String): Boolean {
        val byModePress = clock() - modeReleasedAt <= MODE_TO_SOURCE_MS
        val seen = visited
        if (seen == null) {
            visited = mutableSetOf(source)
            return false
        }
        if (byModePress && source in seen) {
            visited = null
            return true
        }
        seen += source
        return false
    }

    companion object {
        /** The firmware announces the new source up to ~3.3 s after MODE is released (journal 2026-10-07). */
        const val MODE_TO_SOURCE_MS = 5_000L
    }
}

/**
 * The delayed return to CarPlay once the MODE rotation wrapped (the OEM player takes focus first and
 * is then stopped by CarPlay taking it back). A newer MODE press means the driver moved on to the
 * next source, so it cancels a return that has not run yet.
 */
internal class PendingModeReturn(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
) {
    private var pending: Runnable? = null

    /** Replaces any pending return with [action], run after [delayMillis]. */
    fun schedule(delayMillis: Long, action: () -> Unit) {
        cancel()
        val task = Runnable { pending = null; action() }
        pending = task
        post(task, delayMillis)
    }

    /** True when a return was still pending and has now been dropped. */
    fun cancel(): Boolean {
        val task = pending ?: return false
        pending = null
        remove(task)
        return true
    }
}
