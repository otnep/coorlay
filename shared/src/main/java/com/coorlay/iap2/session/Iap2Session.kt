package com.coorlay.iap2.session

import com.coorlay.iap2.body.Iap2BodyBuilder
import com.coorlay.iap2.body.Iap2BodyReader
import com.coorlay.iap2.catalog.Iap2Endpoint
import com.coorlay.iap2.message.Iap2Messages
import com.coorlay.iap2.trace.Iap2FrameFormatter
import com.coorlay.iap2.trace.Iap2TraceDirection
import com.coorlay.iap2.wire.Iap2Frame
import com.coorlay.transport.BlockingDuplexByteStream
import com.coorlay.transport.Iap2CsmChannel

/**
 * One immediately readable and writable iAP2 CSM session.
 *
 * [Iap2CsmChannel] remains the lower-level frame transport. This class is the protocol-facing
 * facade used by services: it accepts endpoint builders, sends complete frames, receives complete
 * frames, and exposes the typed body reader.
 */
class Iap2Session private constructor(
    private val channel: Iap2CsmChannel,
    private val traceContext: String,
    private val onTrace: (String) -> Unit,
    private val traceFrames: Boolean = true,
) : java.io.Closeable {
    val isClosed: Boolean get() = channel.isClosed
    private var receivedFrameCount = 0L
    private var lastFlowTraceNanos = System.nanoTime()

    fun awaitReady(timeoutMillis: Long): Boolean {
        val ready = channel.awaitReady(timeoutMillis)
        emitTrace("IAP2 READY [$traceContext] ready=$ready")
        return ready
    }

    fun send(frame: Iap2Frame, timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS) {
        try {
            channel.send(frame, timeoutMillis)
            emitFrameTrace(Iap2TraceDirection.TX, frame)
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.TX,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
            throw failure
        }
    }

    fun send(
        endpoint: Iap2Endpoint,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.build(endpoint, block), timeoutMillis)
    }

    fun sendRaw(
        messageId: Int,
        timeoutMillis: Long = DEFAULT_SEND_TIMEOUT_MILLIS,
        block: Iap2BodyBuilder.() -> Unit,
    ) {
        send(Iap2Messages.buildRaw(messageId, block), timeoutMillis)
    }

    fun recv(timeoutMillis: Long): Iap2Frame? {
        return try {
            channel.recv(timeoutMillis)?.also {
                receivedFrameCount++
                val now = System.nanoTime()
                if (now - lastFlowTraceNanos >= 15_000_000_000L) {
                    lastFlowTraceNanos = now
                    val stats = channel.receiveQueueStats()
                    emitTrace("IAP2 FLOW [$traceContext] rxFrames=$receivedFrameCount pending=${stats.pending} " +
                        "bytes=${stats.bytes} peak=${stats.peak} pauses=${stats.pauses}")
                }
                emitFrameTrace(Iap2TraceDirection.RX, it)
            }
        } catch (failure: Throwable) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    Iap2TraceDirection.RX,
                    traceContext,
                    messageId = null,
                    failure = failure,
                ),
            )
            throw failure
        }
    }

    fun reader(frame: Iap2Frame): Iap2BodyReader = Iap2Messages.reader(frame)

    override fun close() {
        try {
            channel.close()
        } finally {
            emitTrace("IAP2 CLOSE [$traceContext]")
        }
    }

    private fun emitFrameTrace(direction: Iap2TraceDirection, frame: Iap2Frame) {
        if (!traceFrames) return
        try {
            emitTrace(Iap2FrameFormatter.format(direction, traceContext, frame))
        } catch (failure: Exception) {
            emitTrace(
                Iap2FrameFormatter.formatFailure(
                    direction,
                    traceContext,
                    frame.messageId,
                    failure,
                ),
            )
        }
    }

    private fun emitTrace(message: String) {
        try {
            onTrace(message)
        } catch (_: Exception) {
            // Logging must never change protocol behavior.
        }
    }

    companion object {
        private const val DEFAULT_SEND_TIMEOUT_MILLIS = 5_000L

        fun open(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wired",
            onTrace: (String) -> Unit = {},
            traceFrames: Boolean = true,
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.open(underlying), traceContext, onTrace, traceFrames)

        fun openWireless(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless",
            onTrace: (String) -> Unit = {},
            traceFrames: Boolean = true,
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openWireless(underlying), traceContext, onTrace, traceFrames)

        fun openTunnel(
            underlying: BlockingDuplexByteStream,
            traceContext: String = "wireless-tunnel",
            onTrace: (String) -> Unit = {},
            traceFrames: Boolean = true,
        ): Iap2Session =
            Iap2Session(Iap2CsmChannel.openTunnel(underlying), traceContext, onTrace, traceFrames)

        fun wrap(
            channel: Iap2CsmChannel,
            traceContext: String = "iap2",
            onTrace: (String) -> Unit = {},
        ): Iap2Session = Iap2Session(channel, traceContext, onTrace)
    }
}
