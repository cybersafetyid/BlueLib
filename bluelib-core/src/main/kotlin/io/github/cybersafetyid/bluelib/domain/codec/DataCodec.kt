package io.github.cybersafetyid.bluelib.domain.codec

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import java.nio.charset.Charset

/** Supported text/data encodings in BlueLib messaging. */
public enum class DataEncoding {
    UTF8,
    ASCII,
    ISO_8859_1,
    HEX,
    BINARY,
    BASE64,
    RAW,
    ;

    public val charset: Charset?
        get() = when (this) {
            UTF8 -> Charsets.UTF_8
            ASCII -> Charsets.US_ASCII
            ISO_8859_1 -> Charsets.ISO_8859_1
            else -> null
        }
}

/**
 * Universal codec and validation engine for Bluetooth data payloads.
 *
 * Supports bi-directional encoding/decoding and strict validation for Text, Hex, Binary, and Base64.
 */
public object DataCodec {

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val BASE64_URL_SAFE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Encodes [text] to [ByteArray] using the specified [encoding]. */
    public fun encodeText(text: String, encoding: DataEncoding = DataEncoding.UTF8): ByteArray {
        val charset = encoding.charset ?: throw BlueLibValidationException.InvalidPayload(
            operation = "encodeText",
            sizeBytes = text.length,
            allowed = 0..Int.MAX_VALUE,
            hint = "Encoding '$encoding' is not a character-set encoding. Use encodeHex, encodeBinary or encodeBase64.",
        )
        if (encoding == DataEncoding.ASCII) {
            validateAscii(text)
        }
        return text.toByteArray(charset)
    }

    /** Decodes [bytes] to string using the specified [encoding]. */
    public fun decodeText(bytes: ByteArray, encoding: DataEncoding = DataEncoding.UTF8): String {
        val charset = encoding.charset ?: throw BlueLibValidationException.InvalidPayload(
            operation = "decodeText",
            sizeBytes = bytes.size,
            allowed = 0..Int.MAX_VALUE,
            hint = "Encoding '$encoding' is not a character-set encoding. Use decodeHex, decodeBinary or decodeBase64.",
        )
        return String(bytes, charset)
    }

    /** Encodes a hexadecimal string (e.g. `"48656C6C6F"` or `"48:65:6C:6C:6F"`) into raw bytes. */
    public fun encodeHex(hex: String): ByteArray {
        val compact = hex.replace("0x", "", ignoreCase = true)
            .filter { (!it.isWhitespace()) && (it != ':') && (it != '-') && (it != ',') }

        if (compact.length % 2 != 0) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "encodeHex",
                sizeBytes = compact.length,
                allowed = 0..Int.MAX_VALUE,
                hint = "Hex string must have an even number of characters: '$hex'.",
            )
        }

        return ByteArray(compact.length / 2) { index ->
            val byteStr = compact.substring(index * 2, index * 2 + 2)
            val parsed = byteStr.toIntOrNull(16)
                ?: throw BlueLibValidationException.InvalidPayload(
                    operation = "encodeHex",
                    sizeBytes = compact.length,
                    allowed = 0..Int.MAX_VALUE,
                    hint = "Invalid hexadecimal character sequence '$byteStr' in '$hex'.",
                )
            parsed.toByte()
        }
    }

    /** Renders [bytes] as uppercase Hex string. */
    public fun decodeHex(bytes: ByteArray, separator: String = ""): String =
        bytes.joinToString(separator) { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

    /** Encodes a binary string (e.g. `"01001000 01100101"` or `"0100100001100101"`) into raw bytes. */
    public fun encodeBinary(binaryString: String): ByteArray {
        val compact = binaryString.filter { !it.isWhitespace() && it != '-' && it != ',' }
        if (compact.any { it != '0' && it != '1' }) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "encodeBinary",
                sizeBytes = compact.length,
                allowed = 0..Int.MAX_VALUE,
                hint = "Binary string contains invalid characters (only '0' and '1' allowed): '$binaryString'.",
            )
        }
        if (compact.length % 8 != 0) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "encodeBinary",
                sizeBytes = compact.length,
                allowed = 0..Int.MAX_VALUE,
                hint = "Binary string length (${compact.length} bits) must be a multiple of 8 bits: '$binaryString'.",
            )
        }

        return ByteArray(compact.length / 8) { index ->
            val byteChunk = compact.substring(index * 8, index * 8 + 8)
            byteChunk.toInt(2).toByte()
        }
    }

    /** Decodes [bytes] into an 8-bit padded binary string. */
    public fun decodeBinary(bytes: ByteArray, formatSpaces: Boolean = false): String {
        val separator = if (formatSpaces) " " else ""
        return bytes.joinToString(separator) { byte ->
            (byte.toInt() and 0xFF).toString(2).padStart(8, '0')
        }
    }

    /** Encodes [bytes] into a Base64 string. */
    public fun encodeBase64(bytes: ByteArray, urlSafe: Boolean = false): String {
        val alphabet = if (urlSafe) BASE64_URL_SAFE_ALPHABET else BASE64_ALPHABET
        val builder = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i++].toInt() and 0xFF
            val b1 = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1
            val b2 = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1

            val triple = (b0 shl 16) or ((if (b1 != -1) b1 else 0) shl 8) or (if (b2 != -1) b2 else 0)

            builder.append(alphabet[(triple shr 18) and 0x3F])
            builder.append(alphabet[(triple shr 12) and 0x3F])
            if (b1 != -1) {
                builder.append(alphabet[(triple shr 6) and 0x3F])
            } else {
                builder.append('=')
            }
            if (b2 != -1) {
                builder.append(alphabet[triple and 0x3F])
            } else {
                builder.append('=')
            }
        }
        return builder.toString()
    }

    /** Decodes a Base64 string into raw bytes. */
    public fun decodeBase64(base64String: String): ByteArray {
        val compact = base64String.filter { !it.isWhitespace() }
        if (compact.isEmpty()) return ByteArray(0)

        // Validate character set
        compact.firstOrNull { char ->
            char != '=' && char !in BASE64_ALPHABET && char !in BASE64_URL_SAFE_ALPHABET
        }?.let { invalidChar ->
            throw BlueLibValidationException.InvalidPayload(
                operation = "decodeBase64",
                sizeBytes = compact.length,
                allowed = 0..Int.MAX_VALUE,
                hint = "Illegal Base64 character '$invalidChar' in '$base64String'.",
            )
        }

        if (compact.length % 4 != 0) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "decodeBase64",
                sizeBytes = compact.length,
                allowed = 0..Int.MAX_VALUE,
                hint = "Base64 string length (${compact.length}) must be a multiple of 4.",
            )
        }

        fun decodeChar(c: Char): Int {
            if (c == '=') return 0
            var idx = BASE64_ALPHABET.indexOf(c)
            if (idx == -1) idx = BASE64_URL_SAFE_ALPHABET.indexOf(c)
            return if (idx != -1) idx else 0
        }

        val padCount = compact.takeLast(2).count { it == '=' }
        val outputLen = (compact.length * 3) / 4 - padCount
        val result = ByteArray(outputLen)
        var outIdx = 0

        var i = 0
        while (i < compact.length) {
            val c0 = decodeChar(compact[i++])
            val c1 = decodeChar(compact[i++])
            val c2 = decodeChar(compact[i++])
            val c3 = decodeChar(compact[i++])

            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3

            if (outIdx < outputLen) result[outIdx++] = ((triple shr 16) and 0xFF).toByte()
            if (outIdx < outputLen) result[outIdx++] = ((triple shr 8) and 0xFF).toByte()
            if (outIdx < outputLen) result[outIdx++] = (triple and 0xFF).toByte()
        }

        return result
    }

    private fun validateAscii(text: String) {
        val nonAscii = text.firstOrNull { it.code > 127 }
        if (nonAscii != null) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "encodeText(ASCII)",
                sizeBytes = text.length,
                allowed = 0..Int.MAX_VALUE,
                hint = "Non-ASCII character '$nonAscii' (code ${nonAscii.code}) in string '$text'.",
            )
        }
    }
}
