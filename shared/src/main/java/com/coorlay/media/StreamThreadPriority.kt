package com.coorlay.media

import android.os.Process

/**
 * Linux nice values for the streaming threads. Thread.MAX_PRIORITY only reaches -8 on Dalvik, so audio
 * (UDP receive + AAC decode/AudioTrack) asks for URGENT_AUDIO (-19) and video keeps URGENT_DISPLAY (-8),
 * now logged. Best effort: a refused priority must never stop the stream. Applies to the calling thread.
 */
object StreamThreadPriority {
    fun audio(): String = apply("audio", Process.THREAD_PRIORITY_URGENT_AUDIO)
    fun video(): String = apply("video", Process.THREAD_PRIORITY_URGENT_DISPLAY)

    private fun apply(kind: String, priority: Int): String {
        val name = Thread.currentThread().name
        return try {
            Process.setThreadPriority(priority)
            "Thread priority $name ($kind): requested=$priority actual=${Process.getThreadPriority(Process.myTid())}"
        } catch (error: RuntimeException) {
            "Thread priority $name ($kind): requested=$priority refused: ${error.message}"
        }
    }
}
