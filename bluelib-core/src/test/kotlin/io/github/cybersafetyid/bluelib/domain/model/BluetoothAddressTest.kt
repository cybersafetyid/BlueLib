package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BluetoothAddressTest {

    @Test
    fun `parses and normalises an address`() {
        val address = BluetoothAddress.parse("aa:bb:cc:dd:ee:ff")

        assertEquals("AA:BB:CC:DD:EE:FF", address.value)
        assertFalse(address.isPlaceholder)
    }

    @Test
    fun `rejects malformed addresses`() {
        val invalid = listOf("", "AA:BB", "AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE", "GG:00:00:00:00:00", "AA:BB:CC:DD:EE:FF:00")

        invalid.forEach { raw ->
            assertFailsWith<BlueLibValidationException.InvalidAddress>(raw) {
                BluetoothAddress.parse(raw)
            }
            assertNull(BluetoothAddress.parseOrNull(raw))
            assertFalse(BluetoothAddress.isValid(raw))
        }
    }

    @Test
    fun `detects the placeholder address Android returns for the local adapter`() {
        val placeholder = BluetoothAddress.parse("02:00:00:00:00:00")

        assertTrue(placeholder.isPlaceholder)
        assertEquals(BluetoothAddress.PLACEHOLDER, placeholder.value)
    }

    @Test
    fun `converts to and from raw bytes`() {
        val bytes = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val address = BluetoothAddress.fromBytes(bytes)

        assertEquals("00:11:22:33:44:55", address.value)
        assertContentEquals(bytes, address.bytes)
    }

    @Test
    fun `requires exactly six octets`() {
        assertFailsWith<BlueLibValidationException.InvalidAddress> {
            BluetoothAddress.fromBytes(byteArrayOf(1, 2, 3))
        }
    }
}
