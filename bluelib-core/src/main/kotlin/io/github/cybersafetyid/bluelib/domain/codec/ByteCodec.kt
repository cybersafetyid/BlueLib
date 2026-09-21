package io.github.cybersafetyid.bluelib.domain.codec

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException

/**
 * Little and big endian primitives for GATT payloads.
 *
 * Two reasons these exist instead of using `BluetoothGattCharacteristic.getIntValue()`: those
 * helpers were deprecated in Android 13 (API 33), and they silently return `null` or the wrong value
 * on short payloads. Every function here validates the length first and throws a typed error.
 */
public object ByteCodec {

    /** Encodes [value] as one byte, validating the range. */
    public fun u8(value: Int): ByteArray {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        return byteArrayOf(value.toByte())
    }

    /** Encodes [value] as an unsigned little endian 16 bit integer. */
    public fun u16Le(value: Int): ByteArray {
        if (value !in 0..0xFFFF) throw outOfRange("u16", value)
        return byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
    }

    /** Encodes [value] as an unsigned big endian 16 bit integer. */
    public fun u16Be(value: Int): ByteArray {
        if (value !in 0..0xFFFF) throw outOfRange("u16", value)
        return byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
    }

    /** Encodes [value] as an unsigned little endian 32 bit integer. */
    public fun u32Le(value: Long): ByteArray {
        if (value !in 0..0xFFFFFFFFL) throw outOfRange("u32", value)
        return ByteArray(4) { index -> ((value shr (8 * index)) and 0xFF).toByte() }
    }

    /** Encodes [value] as a signed little endian 16 bit integer. */
    public fun i16Le(value: Int): ByteArray {
        if (value !in Short.MIN_VALUE..Short.MAX_VALUE) throw outOfRange("i16", value)
        val raw = value.toShort().toInt()
        return byteArrayOf((raw and 0xFF).toByte(), ((raw shr 8) and 0xFF).toByte())
    }

    /** Encodes [value] as an IEEE 754 32 bit float, little endian. */
    public fun floatLe(value: Float): ByteArray {
        val bits = value.toRawBits()
        return ByteArray(4) { index -> ((bits shr (8 * index)) and 0xFF).toByte() }
    }

    /** Encodes [value] as UTF-8, validating that the result fits [maxBytes]. */
    public fun utf8(value: String, maxBytes: Int = 512): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "utf8",
                sizeBytes = bytes.size,
                allowed = 0..maxBytes,
                hint = "Shorten the string or split it across several writes.",
            )
        }
        return bytes
    }

    /** Encodes a binary coded decimal string such as `"12:34"`. */
    public fun bcd(digits: String): ByteArray {
        val compact = digits.filter { !it.isWhitespace() && it != ':' }
        require(compact.length % 2 == 0) { "BCD input needs an even number of digits: '$digits'" }
        return ByteArray(compact.length / 2) { index ->
            val high = compact[index * 2].digitToIntOrNull()
            val low = compact[index * 2 + 1].digitToIntOrNull()
            require(high != null && low != null) { "BCD input contains non digits: '$digits'" }
            ((high shl 4) or low).toByte()
        }
    }

    /** Parses hex text such as `"0A1B2C"` (whitespace and `0x` prefixes are ignored). */
    public fun fromHex(text: String): ByteArray {
        val compact = text.replace("0x", "", ignoreCase = true).filter { !it.isWhitespace() && it != ':' && it != '-' }
        require(compact.length % 2 == 0) { "Hex input needs an even number of characters: '$text'" }
        return ByteArray(compact.length / 2) { index ->
            val value = compact.substring(index * 2, index * 2 + 2).toIntOrNull(16)
                ?: throw BlueLibValidationException.InvalidPayload(
                    operation = "fromHex",
                    sizeBytes = compact.length / 2,
                    allowed = 0..Int.MAX_VALUE,
                    hint = "Unexpected character in '$text'.",
                )
            value.toByte()
        }
    }

    /** Renders bytes as uppercase hex, optionally separated. */
    public fun toHex(bytes: ByteArray, separator: String = ""): String =
        bytes.joinToString(separator) { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

    private fun outOfRange(name: String, value: Number): BlueLibValidationException =
        BlueLibValidationException.ValueOutOfRange(
            parameter = name,
            value = value.toLong(),
            allowed = Long.MIN_VALUE..Long.MAX_VALUE,
        )
}

/**
 * Bounds checked reader over a GATT payload.
 *
 * Reading past the end throws [BlueLibValidationException.InvalidPayload] instead of Android's
 * `ArrayIndexOutOfBoundsException`, which makes parsing untrusted peripheral data safe.
 */
public class ByteReader(private val payload: ByteArray, private val label: String = "payload") {

    /** Current read offset. */
    public var offset: Int = 0
        private set

    /** Bytes left to read. */
    public val remaining: Int
        get() = payload.size - offset

    /** `true` when every byte has been consumed. */
    public val isExhausted: Boolean
        get() = remaining == 0

    /** Unsigned 8 bit value. */
    public fun readU8(): Int {
        require(1)
        return payload[offset++].toInt() and 0xFF
    }

    /** Unsigned little endian 16 bit value. */
    public fun readU16Le(): Int = readU8() or (readU8() shl 8)

    /** Unsigned big endian 16 bit value. */
    public fun readU16Be(): Int = (readU8() shl 8) or readU8()

    /** Unsigned little endian 32 bit value. */
    public fun readU32Le(): Long {
        var value = 0L
        repeat(4) { index -> value = value or ((readU8().toLong()) shl (8 * index)) }
        return value
    }

    /** Signed little endian 16 bit value. */
    public fun readI16Le(): Int = readU16Le().let { if (it >= 0x8000) it - 0x10000 else it }

    /** IEEE 754 single precision float, little endian. */
    public fun readFloatLe(): Float = Float.fromBits(readU32Le().toInt())

    /** Reads [length] raw bytes. */
    public fun readBytes(length: Int): ByteArray {
        require(length)
        val result = payload.copyOfRange(offset, offset + length)
        offset += length
        return result
    }

    /** UTF-8 text from the remaining bytes. */
    public fun readRemainingUtf8(): String = String(readBytes(remaining), Charsets.UTF_8)

    /** Skips [count] bytes. */
    public fun skip(count: Int) {
        require(count)
    }

    private fun require(count: Int) {
        if (count < 0 || offset + count > payload.size) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "$label.read",
                sizeBytes = count,
                allowed = 0..remaining,
                hint = "The payload ended at byte $offset (length ${payload.size}); parsing is aborted " +
                    "instead of reading out of bounds.",
            )
        }
    }
}
