package io.github.cybersafetyid.bluelib.domain.validation

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PayloadSegmenterTest {

    @Test
    fun `computes the usable payload for the ATT default MTU`() {
        assertEquals(20, PayloadSegmenter.maxChunkSize(23))
        assertEquals(514, PayloadSegmenter.maxChunkSize(PayloadSegmenter.MAX_MTU))
    }

    @Test
    fun `rejects MTUs outside the ATT range`() {
        assertFailsWith<BlueLibValidationException.InvalidMtu> { PayloadSegmenter.maxChunkSize(22) }
        assertFailsWith<BlueLibValidationException.InvalidMtu> { PayloadSegmenter.maxChunkSize(518) }
    }

    @Test
    fun `refuses a multi chunk payload written with one request`() {
        val value = ByteArray(40)

        val failure = assertFailsWith<BlueLibValidationException.InvalidPayload> {
            PayloadSegmenter.validateWrite(value, WriteMode.WITH_RESPONSE, mtu = 23)
        }

        assertTrue(failure.hint.contains("WriteMode.LONG"))
    }

    @Test
    fun `accepts the same payload in long write mode`() {
        PayloadSegmenter.validateWrite(ByteArray(40), WriteMode.LONG, mtu = 23)
    }

    @Test
    fun `rejects a long write above the 512 byte limit`() {
        assertFailsWith<BlueLibValidationException.InvalidPayload> {
            PayloadSegmenter.validateWrite(ByteArray(513), WriteMode.LONG, mtu = 517)
        }
    }

    @Test
    fun `splits a value into MTU sized chunks and preserves the bytes`() {
        val value = ByteArray(45) { it.toByte() }

        val chunks = PayloadSegmenter.segment(value, mtu = 23, writeMode = WriteMode.LONG)

        assertEquals(3, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(20, chunks[1].size)
        assertEquals(5, chunks[2].size)
        assertContentEquals(value, chunks.reduce { acc, bytes -> acc + bytes })
    }

    @Test
    fun `keeps a fitting value as a single chunk`() {
        val value = ByteArray(20)

        val chunks = PayloadSegmenter.segment(value, mtu = 23, writeMode = WriteMode.WITH_RESPONSE)

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().contentEquals(value))
    }

    @Test
    fun `clamps requested MTUs into the platform range`() {
        assertEquals(23, PayloadSegmenter.clampMtu(10))
        assertEquals(517, PayloadSegmenter.clampMtu(4096))
        assertEquals(247, PayloadSegmenter.clampMtu(247))
    }
}
