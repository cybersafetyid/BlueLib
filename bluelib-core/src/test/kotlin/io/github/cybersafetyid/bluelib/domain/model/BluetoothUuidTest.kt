package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BluetoothUuidTest {

    @Test
    fun `expands SIG short UUIDs onto the base UUID`() {
        val heartRate = BluetoothUuid.fromShort(0x180D)

        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", heartRate.toString())
        assertTrue(heartRate.isShort16Bit)
        assertEquals(0x180D, heartRate.shortValue)
    }

    @Test
    fun `rejects out of range short values`() {
        assertFailsWith<BlueLibValidationException.InvalidUuid> { BluetoothUuid.fromShort(0x1_0000) }
        assertFailsWith<BlueLibValidationException.InvalidUuid> { BluetoothUuid.fromShort(-1) }
        assertFailsWith<BlueLibValidationException.InvalidUuid> { BluetoothUuid.fromInt(0x1_0000_0000L) }
    }

    @Test
    fun `reports null short value for vendor UUIDs`() {
        val vendor = BluetoothUuid.parse("12345678-1234-5678-1234-56789abcdef0")

        assertNull(vendor.shortValue)
        assertFalse(vendor.isShort16Bit)
    }

    @Test
    fun `parse reports typed error for malformed input`() {
        assertFailsWith<BlueLibValidationException.InvalidUuid> { BluetoothUuid.parse("not-a-uuid") }
        assertNull(BluetoothUuid.parseOrNull("not-a-uuid"))
        assertNotNull(BluetoothUuid.parseOrNull("0000180d-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `well known UUIDs match the SIG assigned numbers`() {
        assertEquals(0x1800, BluetoothUuid.GENERIC_ACCESS.shortValue)
        assertEquals(0x2902, BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION.shortValue)
    }
}
