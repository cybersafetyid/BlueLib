package io.github.cybersafetyid.bluelib.domain.codec

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageFramerTest {

    @Test
    fun `RawFramer transmits and parses raw bytes without changes`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        assertContentEquals(bytes, RawFramer.frame(bytes))

        val parsed = RawFramer.parseIncoming(bytes)
        assertEquals(1, parsed.size)
        assertContentEquals(bytes, parsed[0])
    }

    @Test
    fun `DelimiterFramer appends delimiter and reconstructs fragmented chunks`() {
        val framer = DelimiterFramer.LINE_FEED
        val msg1 = "Hello".toByteArray(Charsets.UTF_8)
        val msg2 = "World".toByteArray(Charsets.UTF_8)

        val framed1 = framer.frame(msg1)
        assertContentEquals("Hello\n".toByteArray(Charsets.UTF_8), framed1)

        // Feed chunk in two separate fragments
        val chunk1 = "Hel".toByteArray(Charsets.UTF_8)
        val chunk2 = "lo\nWor".toByteArray(Charsets.UTF_8)
        val chunk3 = "ld\n".toByteArray(Charsets.UTF_8)

        val parse1 = framer.parseIncoming(chunk1)
        assertTrue(parse1.isEmpty())

        val parse2 = framer.parseIncoming(chunk2)
        assertEquals(1, parse2.size)
        assertContentEquals(msg1, parse2[0])

        val parse3 = framer.parseIncoming(chunk3)
        assertEquals(1, parse3.size)
        assertContentEquals(msg2, parse3[0])
    }

    @Test
    fun `DelimiterFramer buffer overflow throws InvalidPayload`() {
        val framer = DelimiterFramer(delimiter = byteArrayOf(0x0A), maxBufferSize = 10)
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            framer.parseIncoming("This string is longer than 10 bytes without linefeed".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `LengthPrefixedFramer frames and reconstructs packets`() {
        val framer = LengthPrefixedFramer(headerBytes = 2)
        val payload = "BlueLib".toByteArray(Charsets.UTF_8) // 7 bytes

        val framed = framer.frame(payload)
        assertEquals(9, framed.size)
        assertEquals(0, framed[0].toInt())
        assertEquals(7, framed[1].toInt())

        // Feed partially
        val parse1 = framer.parseIncoming(framed.copyOfRange(0, 4))
        assertTrue(parse1.isEmpty())

        val parse2 = framer.parseIncoming(framed.copyOfRange(4, framed.size))
        assertEquals(1, parse2.size)
        assertContentEquals(payload, parse2[0])
    }

    @Test
    fun `LengthPrefixedFramer rejects invalid header configuration`() {
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            LengthPrefixedFramer(headerBytes = 3)
        }
    }
}
