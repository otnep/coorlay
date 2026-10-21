package com.coorlay.media

import com.coorlay.iap2.body.Iap2BodyReader
import com.coorlay.iap2.wire.Iap2Frame

/**
 * The iPhone's calls from iAP2 CallStateUpdate (0x4155): remote id (0), status (2) and call UUID (4).
 * Status: 0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active, 5 held, 6 disconnecting.
 * Several calls can exist at once (call waiting): a ringing one counts first, then a connected one,
 * then an outgoing one. Ported from DiPlay's CarPlayCallState without the caller's name.
 */
class CarPlayCallState {
    enum class Phase { RINGING, DIALING, ACTIVE }

    private val calls = LinkedHashMap<String, Int>()

    /** The phase that matters for the wheel's call key, null without a call. */
    var phase: Phase? = null
        private set

    /** True when [frame] changed [phase]. */
    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId != CALL_STATE_UPDATE) return false
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return false
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull() ?: return false
        val remote = runCatching { body.optionalString(REMOTE_ID) }.getOrNull()?.trim().orEmpty()
        val id = runCatching { body.optionalString(CALL_UUID) }.getOrNull()?.takeIf { it.isNotBlank() } ?: remote
        if (status == DISCONNECTED || status == DISCONNECTING) calls.remove(id) else calls[id] = status
        val next = when {
            calls.containsValue(RINGING) -> Phase.RINGING
            calls.containsValue(ACTIVE) || calls.containsValue(HELD) -> Phase.ACTIVE
            calls.containsValue(SENDING) || calls.containsValue(CONNECTING) -> Phase.DIALING
            else -> null
        }
        if (next == phase) return false
        phase = next
        return true
    }

    /** The session ended; true when a call was still known. */
    fun clear(): Boolean {
        calls.clear()
        val had = phase != null
        phase = null
        return had
    }

    companion object {
        const val CALL_STATE_UPDATE = 0x4155
        private const val REMOTE_ID = 0
        private const val STATUS = 2
        private const val CALL_UUID = 4
        private const val DISCONNECTED = 0
        private const val SENDING = 1
        private const val RINGING = 2
        private const val CONNECTING = 3
        private const val ACTIVE = 4
        private const val HELD = 5
        private const val DISCONNECTING = 6
    }
}
