package com.coorlay.transport

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Detect only a data pipe that has never transferred anything, not an idle CarPlay session. */
internal class LegacyNcmStartupPolicy(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val firstAttempt = AtomicLong(Long.MIN_VALUE)
    private val progressed = AtomicBoolean(false)
    fun attempted() { firstAttempt.compareAndSet(Long.MIN_VALUE, now()) }
    fun progressed() { progressed.set(true) }
    fun timedOut(): Boolean {
        val start = firstAttempt.get()
        return !progressed.get() && start != Long.MIN_VALUE && now() - start >= 60_000
    }
    companion object { const val MESSAGE = "USB NCM data path did not start within 60s" }
}
