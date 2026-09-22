package io.github.cybersafetyid.bluelib.domain.codec

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import java.io.ByteArrayOutputStream

/**
 * Strategy for framing outgoing messages and reconstructing incoming packets over Bluetooth streams.
 */
public interface MessageFramer {

    /** Prepares [message] payload for transmission (e.g. appending a delimiter or length header). */
    public fun frame(message: ByteArray): ByteArray

    /**
     * Feeds an incoming byte [chunk] into the parser buffer.
     *
     * @return List of complete parsed messages extracted from the buffer.
     */
    public fun parseIncoming(chunk: ByteArray): List<ByteArray>

    /** Clears any accumulated state in the parser buffer. */
    public fun reset()
}

/** Default framer that transmits raw bytes without adding or parsing headers/delimiters. */
public object RawFramer : MessageFramer {
    override fun frame(message: ByteArray): ByteArray = message
    override fun parseIncoming(chunk: ByteArray): List<ByteArray> = if (chunk.isNotEmpty()) listOf(chunk) else emptyList()
    override fun reset() {}
}

/**
 * Framer that uses a byte sequence delimiter (such as `\n` or `\r\n`) to delimit messages.
 */
public class DelimiterFramer(
    public val delimiter: ByteArray,
    public val maxBufferSize: Int = 16_384,
) : MessageFramer {

    private val buffer = ByteArrayOutputStream()

    init {
        if (delimiter.isEmpty()) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "DelimiterFramer",
                sizeBytes = 0,
                allowed = 1..Int.MAX_VALUE,
                hint = "Delimiter byte array cannot be empty.",
            )
        }
    }

    override fun frame(message: ByteArray): ByteArray = message + delimiter

    @Synchronized
    override fun parseIncoming(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()

        if ((buffer.size() + chunk.size) > maxBufferSize) {
            buffer.reset()
            throw BlueLibValidationException.InvalidPayload(
                operation = "DelimiterFramer.parseIncoming",
                sizeBytes = buffer.size() + chunk.size,
                allowed = 0..maxBufferSize,
                hint = "Incoming message buffer exceeded max limit ($maxBufferSize bytes) without finding delimiter.",
            )
        }

        buffer.write(chunk)
        val currentBytes = buffer.toByteArray()
        val result = mutableListOf<ByteArray>()

        var searchOffset = 0
        while (searchOffset <= (currentBytes.size - delimiter.size)) {
            val matchIndex = indexOfDelimiter(currentBytes, searchOffset)
            searchOffset = if (matchIndex != -1) {
                val messageBytes = currentBytes.copyOfRange(searchOffset, matchIndex)
                result.add(messageBytes)
                matchIndex + delimiter.size
            } else {
                break
            }
        }

        buffer.reset()
        if (searchOffset < currentBytes.size) {
            val remaining = currentBytes.copyOfRange(searchOffset, currentBytes.size)
            buffer.write(remaining)
        }

        return result
    }

    @Synchronized
    override fun reset() {
        buffer.reset()
    }

    private fun indexOfDelimiter(data: ByteArray, startOffset: Int): Int {
        outer@ for (i in startOffset..(data.size - delimiter.size)) {
            for (j in delimiter.indices) {
                if (data[i + j] != delimiter[j]) continue@outer
            }
            return i
        }
        return -1
    }

    public companion object {
        /** Line Feed (`\n`) delimiter. */
        public val LINE_FEED: DelimiterFramer = DelimiterFramer(byteArrayOf(0x0A))

        /** Carriage Return + Line Feed (`\r\n`) delimiter. */
        public val CRLF: DelimiterFramer = DelimiterFramer(byteArrayOf(0x0D, 0x0A))

        /** Null byte (`0x00`) delimiter. */
        public val NULL_BYTE: DelimiterFramer = DelimiterFramer(byteArrayOf(0x00))
    }
}

/**
 * Framer that prefixes each message with a 1, 2, or 4-byte big-endian length header.
 */
public class LengthPrefixedFramer(
    public val headerBytes: Int = 2,
    public val maxMessageSize: Int = 65_535,
) : MessageFramer {

    private val buffer = ByteArrayOutputStream()

    init {
        if (headerBytes !in listOf(1, 2, 4)) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "LengthPrefixedFramer",
                sizeBytes = headerBytes,
                allowed = 1..4,
                hint = "headerBytes must be 1, 2, or 4 bytes.",
            )
        }
    }

    override fun frame(message: ByteArray): ByteArray {
        if (message.size > maxMessageSize) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "LengthPrefixedFramer.frame",
                sizeBytes = message.size,
                allowed = 0..maxMessageSize,
                hint = "Message size exceeds configured maximum ($maxMessageSize bytes).",
            )
        }

        val header = when (headerBytes) {
            1 -> byteArrayOf(message.size.toByte())
            2 -> byteArrayOf(((message.size shr 8) and 0xFF).toByte(), (message.size and 0xFF).toByte())
            4 -> byteArrayOf(
                ((message.size shr 24) and 0xFF).toByte(),
                ((message.size shr 16) and 0xFF).toByte(),
                ((message.size shr 8) and 0xFF).toByte(),
                (message.size and 0xFF).toByte(),
            )
            else -> error("Unreachable")
        }

        return header + message
    }

    @Synchronized
    override fun parseIncoming(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        buffer.write(chunk)

        val result = mutableListOf<ByteArray>()
        var data = buffer.toByteArray()

        while (data.size >= headerBytes) {
            val length = when (headerBytes) {
                1 -> data[0].toInt() and 0xFF
                2 -> ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                4 -> ((data[0].toInt() and 0xFF) shl 24) or
                    ((data[1].toInt() and 0xFF) shl 16) or
                    ((data[2].toInt() and 0xFF) shl 8) or
                    (data[3].toInt() and 0xFF)
                else -> error("Unreachable")
            }

            if (length > maxMessageSize) {
                buffer.reset()
                throw BlueLibValidationException.InvalidPayload(
                    operation = "LengthPrefixedFramer.parseIncoming",
                    sizeBytes = length,
                    allowed = 0..maxMessageSize,
                    hint = "Parsed header length ($length bytes) exceeds maximum allowed size ($maxMessageSize bytes).",
                )
            }

            val totalPacketSize = headerBytes + length
            if (data.size >= totalPacketSize) {
                val payload = data.copyOfRange(headerBytes, totalPacketSize)
                result.add(payload)
                data = data.copyOfRange(totalPacketSize, data.size)
            } else {
                break
            }
        }

        buffer.reset()
        if (data.isNotEmpty()) {
            buffer.write(data)
        }

        return result
    }

    @Synchronized
    override fun reset() {
        buffer.reset()
    }
}
