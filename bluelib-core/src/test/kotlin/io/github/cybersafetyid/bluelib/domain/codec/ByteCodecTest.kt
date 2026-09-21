package io.github.cybersafetyid.bluelib.domain.codec

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ByteCodecTest {

    @Test
    fun `encodes little endian integers`() {
        assertContentEquals(byteArrayOf(0x34, 0x12), ByteCodec.u16Le(0x1234))
        assertContentEquals(byteArrayOf(0x12, 0x34), ByteCodec.u16Be(0x1234))
        assertContentEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), ByteCodec.u32Le(0x12345678L))
    }

    @Test
    fun `rejects out of range values`() {
        assertFailsWith<BlueLibValidationException.ValueOutOfRange> { ByteCodec.u16Le(0x1_0000) }
        assertFailsWith<BlueLibValidationException.ValueOutOfRange> { ByteCodec.u32Le(-1) }
        assertFailsWith<IllegalArgumentException> { ByteCodec.u8(256) }
    }

    @Test
    fun `round trips a GATT payload through the reader`() {
        val payload = ByteCodec.u16Le(0x180D) + ByteCodec.u8(0x03) + ByteCodec.floatLe(36.6f) + ByteCodec.utf8("hr")

        val reader = ByteReader(payload)

        assertEquals(0x180D, reader.readU16Le())
        assertEquals(0x03, reader.readU8())
        assertEquals(36.6f, reader.readFloatLe())
        assertEquals("hr", reader.readRemainingUtf8())
        assertTrue(reader.isExhausted)
    }

    @Test
    fun `reading past the end is a typed error rather than a crash`() {
        val reader = ByteReader(byteArrayOf(0x01), label = "scanRecord")

        val failure = assertFailsWith<BlueLibValidationException.InvalidPayload> { reader.readU32Le() }

        assertEquals("invalid-payload", failure.docsAnchor)
        assertTrue(failure.operation.contains("scanRecord"), "the label must identify the payload: ${failure.operation}")
    }

    @Test
    fun `reads signed values`() {
        assertContentEquals(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), ByteCodec.i16Le(-2))
        assertEquals(-2, ByteReader(byteArrayOf(0xFE.toByte(), 0xFF.toByte())).readI16Le())
    }

    @Test
    fun `parses hex text and renders it back`() {
        val bytes = ByteCodec.fromHex("0x0A:1b-2C")

        assertContentEquals(byteArrayOf(0x0A, 0x1B, 0x2C), bytes)
        assertEquals("0A1B2C", ByteCodec.toHex(bytes))
        assertEquals("0A 1B 2C", ByteCodec.toHex(bytes, separator = " "))
    }

    @Test
    fun `encodes binary coded decimals`() {
        assertContentEquals(byteArrayOf(0x12, 0x34), ByteCodec.bcd("12:34"))
        assertFailsWith<IllegalArgumentException> { ByteCodec.bcd("123") }
    }

    @Test
    fun `validates the utf8 payload budget`() {
        val failure = assertFailsWith<BlueLibValidationException.InvalidPayload> {
            ByteCodec.utf8("a".repeat(600))
        }

        assertEquals("invalid-payload", failure.docsAnchor)
    }
}
