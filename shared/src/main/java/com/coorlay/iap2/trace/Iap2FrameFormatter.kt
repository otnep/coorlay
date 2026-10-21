package com.coorlay.iap2.trace

import com.coorlay.iap2.catalog.Iap2Endpoints
import com.coorlay.iap2.catalog.Iap2FieldSpec
import com.coorlay.iap2.catalog.Iap2WireType
import com.coorlay.iap2.wire.Iap2Frame
import com.coorlay.iap2.wire.Iap2Parameter
import com.coorlay.iap2.wire.Iap2ParameterList
import com.coorlay.iap2.wire.Iap2ProtocolException
import com.coorlay.iap2.wire.Iap2WireCodec

enum class Iap2TraceDirection(val label: String) {
    TX("TX"),
    RX("RX"),
}

/**
 * Human-readable rendering for the on-device iAP2 trace.
 *
 * Known endpoints use their field names and wire types. Unknown/medium-confidence endpoints still
 * show ordered parameter IDs and safe scalar/string/byte summaries, so no frame is invisible just
 * because it has not received a strict schema yet.
 */
object Iap2FrameFormatter {
    private const val MAX_PARAMETERS = 80
    private const val MAX_STRING_CHARS = 240
    private const val MAX_HEX_BYTES = 24

    fun format(
        direction: Iap2TraceDirection,
        context: String,
        frame: Iap2Frame,
    ): String {
        val endpoint = Iap2Endpoints.byId(frame.messageId)
        val encoded = frame.encodedFrame()
        val parameters = try {
            Iap2ParameterList.parse(frame.payload)
        } catch (_: Iap2ProtocolException) {
            null
        }
        val lines = ArrayList<String>()
        val secrets = SecretFlag()
        val personal = endpoint?.feature == PERSONAL_FEATURE
        if (parameters == null) {
            lines += "  body=malformed TLV"
        } else {
            appendParameters(
                parameters = parameters,
                specs = endpoint?.fields.orEmpty(),
                depth = 1,
                lines = lines,
                secrets = secrets,
                personal = personal,
            )
        }
        // The hex preview of 0x5703/0x4301 carried the Wi-Fi passphrase in exported logs (2026-10-09).
        lines += when {
            secrets.found -> "  raw-body=[redacted: frame contains a secret]"
            personal -> "  raw-body=[redacted: personal data]"
            else -> "  raw-body=${hexPreview(frame.payload)}"
        }

        val endpointText = endpoint?.let { " ${it.name}" }.orEmpty()
        return buildString {
            append("IAP2 ")
            append(direction.label)
            append(" [")
            append(context)
            append("] 0x")
            append(frame.messageId.toString(16).padStart(4, '0'))
            append(endpointText)
            append(" frame=")
            append(encoded.size)
            append("B body=")
            append(if (parameters == null) "malformed" else "${parameters.size} params")
            lines.forEach { line ->
                append('\n')
                append(line)
            }
        }
    }

    fun formatFailure(
        direction: Iap2TraceDirection,
        context: String,
        messageId: Int?,
        failure: Throwable,
    ): String = buildString {
        append("IAP2 ")
        append(direction.label)
        append(" [")
        append(context)
        append(']')
        messageId?.let {
            append(" 0x")
            append(it.toString(16).padStart(4, '0'))
            append(Iap2Endpoints.byId(it)?.let { endpoint -> " ${endpoint.name}" }.orEmpty())
        }
        append(" FAILED: ")
        append(failure.message ?: failure.javaClass.simpleName)
    }

    private class SecretFlag { var found = false }

    /**
     * Calls, contacts and recents: CallStateUpdate printed the caller's number and contact name in logs
     * that users share (2026-10-21). Their strings and bytes are left out; numbers such as the call status stay.
     */
    private const val PERSONAL_FEATURE = "Communications"
    private val SCALARS = setOf(Iap2WireType.VOID, Iap2WireType.U8, Iap2WireType.I8, Iap2WireType.U16, Iap2WireType.I16,
        Iap2WireType.U32, Iap2WireType.I32, Iap2WireType.U64, Iap2WireType.GROUP)

    private fun personalOpaque(payload: ByteArray): String =
        if (readableString(payload) == null && payload.size in intArrayOf(0, 1, 2, 4, 8)) formatOpaque(payload)
        else "[personal: ${payload.size}B]"

    private fun isSecret(name: String) = SECRET_FIELD.containsMatchIn(name)
    private val SECRET_FIELD = Regex("(?i)passphrase|password|secret|privateKey")

    private fun appendParameters(
        parameters: Iap2ParameterList,
        specs: List<Iap2FieldSpec>,
        depth: Int,
        lines: MutableList<String>,
        secrets: SecretFlag,
        personal: Boolean = false,
    ) {
        val fields = specs.associateBy(Iap2FieldSpec::id)
        parameters.asList().take(MAX_PARAMETERS).forEach { parameter ->
            val field = fields[parameter.id]
            val name = field?.name ?: "param"
            val value = if (field != null && isSecret(name)) {
                secrets.found = true
                "[redacted]"
            } else if (field == null) {
                if (personal) personalOpaque(parameter.payload) else formatOpaque(parameter.payload)
            } else if (personal && field.type !in SCALARS) {
                "[personal: ${parameter.payload.size}B]"
            } else {
                formatKnown(parameter.payload, field, depth, secrets, personal)
            }
            lines += "${indent(depth)}[${parameter.id}] $name: $value"
        }
        if (parameters.size > MAX_PARAMETERS) {
            lines += "${indent(depth)}... ${parameters.size - MAX_PARAMETERS} more parameters"
        }
    }

    private fun formatKnown(
        payload: ByteArray,
        spec: Iap2FieldSpec,
        depth: Int,
        secrets: SecretFlag,
        personal: Boolean,
    ): String = try {
        when (spec.type) {
            Iap2WireType.VOID -> if (payload.isEmpty()) "void" else "void? ${hexPreview(payload)}"
            Iap2WireType.U8 -> scalar("u8", payload, 1, Iap2WireCodec.readU8(payload))
            Iap2WireType.I8 -> scalar("i8", payload, 1, Iap2WireCodec.readI8(payload))
            Iap2WireType.U16 -> scalar("u16", payload, 2, Iap2WireCodec.readU16(payload))
            Iap2WireType.I16 -> scalar("i16", payload, 2, Iap2WireCodec.readI16(payload))
            Iap2WireType.U32 -> scalar("u32", payload, 4, Iap2WireCodec.readU32(payload))
            Iap2WireType.I32 -> scalar("i32", payload, 4, Iap2WireCodec.readI32(payload))
            Iap2WireType.U64 -> scalar("u64", payload, 8, Iap2WireCodec.readU64(payload))
            Iap2WireType.U16_LIST -> Iap2WireCodec.readU16List(payload)
                .joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }
            Iap2WireType.BYTES -> bytes(payload)
            Iap2WireType.STRING -> quote(truncate(Iap2WireCodec.readString(payload)))
            Iap2WireType.STRING_LIST -> Iap2WireCodec.readStrings(payload)
                .joinToString(prefix = "[", postfix = "]") { quote(truncate(it)) }
            Iap2WireType.GROUP -> {
                val nested = Iap2ParameterList.parse(payload)
                val nestedLines = ArrayList<String>()
                appendParameters(nested, spec.children, depth + 1, nestedLines, secrets, personal)
                if (nestedLines.isEmpty()) "{}" else nestedLines.joinToString("\n", prefix = "{\n", postfix = "\n${indent(depth)}}")
            }
            Iap2WireType.OPAQUE -> formatOpaque(payload)
        }
    } catch (_: Exception) {
        if (personal) personalOpaque(payload) else formatOpaque(payload)
    }

    private fun formatOpaque(payload: ByteArray): String {
        if (payload.isEmpty()) return "void"
        readableString(payload)?.let { return quote(truncate(it)) }
        return when (payload.size) {
            1 -> "u8=${u8(payload[0])} (0x${hexPreview(payload)})"
            2 -> "u16=${Iap2WireCodec.readU16(payload)} (0x${hexPreview(payload)})"
            4 -> "u32=${Iap2WireCodec.readU32(payload)} (0x${hexPreview(payload)})"
            8 -> "u64=${Iap2WireCodec.readU64(payload)} (0x${hexPreview(payload)})"
            else -> bytes(payload)
        }
    }

    private fun scalar(
        name: String,
        payload: ByteArray,
        size: Int,
        value: Number,
    ): String {
        require(payload.size == size) { "expected $size bytes" }
        return "$name=$value (0x${hexPreview(payload)})"
    }

    private fun bytes(payload: ByteArray): String =
        "bytes=${payload.size} [${hexPreview(payload)}]"

    private fun readableString(payload: ByteArray): String? {
        if (payload.isEmpty() || payload.last() != 0.toByte()) return null
        val content = payload.copyOf(payload.size - 1)
        if (content.any { byte ->
                val value = byte.toInt() and 0xff
                value < 0x20 && value != 0x09
            }
        ) {
            return null
        }
        val decoded = content.decodeToString()
        if (decoded.contains('\uFFFD')) return null
        return decoded
    }

    private fun quote(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t") + "\""

    private fun truncate(value: String): String =
        if (value.length <= MAX_STRING_CHARS) value
        else value.take(MAX_STRING_CHARS) + "..."

    private fun hexPreview(payload: ByteArray): String {
        if (payload.isEmpty()) return "<empty>"
        val shown = payload.take(MAX_HEX_BYTES).joinToString(" ") {
            "%02x".format(it.toInt() and 0xff)
        }
        return if (payload.size <= MAX_HEX_BYTES) shown else "$shown ... (+${payload.size - MAX_HEX_BYTES}B)"
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff

    private fun indent(depth: Int): String = "  ".repeat(depth)
}
