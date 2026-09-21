package io.github.cybersafetyid.bluelib.domain.codec

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataCodecTest {

    @Test
    fun `encodes and decodes UTF8 text correctly`() {
        val original = "Hello BlueLib! 🚀"
        val bytes = DataCodec.encodeText(original, DataEncoding.UTF8)
        val decoded = DataCodec.decodeText(bytes, DataEncoding.UTF8)
        assertEquals(original, decoded)
    }

    @Test
    fun `encodes ASCII text and rejects non-ASCII characters`() {
        val validAscii = "BlueLib 123!"
        val bytes = DataCodec.encodeText(validAscii, DataEncoding.ASCII)
        assertEquals(validAscii, DataCodec.decodeText(bytes, DataEncoding.ASCII))

        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            DataCodec.encodeText("Hello 🚀", DataEncoding.ASCII)
        }
    }

    @Test
    fun `encodes and decodes Hex strings with formatting`() {
        val hex = "0A1B2C3D"
        val expectedBytes = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D)

        val bytes1 = DataCodec.encodeHex(hex)
        val bytes2 = DataCodec.encodeHex("0x0A:1B-2C,3D")

        assertContentEquals(expectedBytes, bytes1)
        assertContentEquals(expectedBytes, bytes2)
        assertEquals("0A1B2C3D", DataCodec.decodeHex(bytes1))
        assertEquals("0A:1B:2C:3D", DataCodec.decodeHex(bytes1, separator = ":"))
    }

    @Test
    fun `rejects invalid Hex strings`() {
        // Odd length
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            DataCodec.encodeHex("0A1")
        }

        // Invalid character
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            DataCodec.encodeHex("0A1G")
        }
    }

    @Test
    fun `encodes and decodes Binary strings`() {
        val binary = "01001000 01100101" // "He"
        val expectedBytes = byteArrayOf(0x48, 0x65)

        val bytes = DataCodec.encodeBinary(binary)
        assertContentEquals(expectedBytes, bytes)
        assertEquals("0100100001100101", DataCodec.decodeBinary(bytes))
        assertEquals("01001000 01100101", DataCodec.decodeBinary(bytes, formatSpaces = true))
    }

    @Test
    fun `rejects invalid Binary strings`() {
        // Non-0/1 character
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            DataCodec.encodeBinary("01001002")
        }

        // Not multiple of 8 bits
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            DataCodec.encodeBinary("01001")
        }
    }

    @Test
    fun `encodes and decodes Base64 strings`() {
        val raw = "Hello Bluetooth World!".toByteArray(Charsets.UTF_8)
        val base64 = DataCodec.encodeBase64(raw)

        val decoded = DataCodec.decodeBase64(base64)
        assertContentEquals(raw, decoded)
    }

    @Test
    fun `rejects invalid Base64 strings`() {
        // Illegal character
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            DataCodec.decodeBase64("SGVsbG8#")
        }

        // Invalid length
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            DataCodec.decodeBase64("SGVsbG")
        }
    }
}
