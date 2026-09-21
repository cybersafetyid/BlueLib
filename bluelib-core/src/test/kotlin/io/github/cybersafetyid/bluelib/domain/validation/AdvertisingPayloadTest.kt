package io.github.cybersafetyid.bluelib.domain.validation

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.AdvertiseData
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AdvertisingPayloadTest {

    @Test
    fun `computes the AD structure size for a minimal payload`() {
        val data = AdvertiseData(serviceUuids = listOf(BluetoothUuid.HEART_RATE))

        // flags (3) + 16 bit service uuid field (2 header + 2 payload)
        assertEquals(7, AdvertisingPayload.sizeOf(data))
    }

    @Test
    fun `counts 128 bit UUIDs as sixteen bytes each`() {
        val vendor = BluetoothUuid.parse("12345678-1234-5678-1234-56789abcdef0")
        val data = AdvertiseData(serviceUuids = listOf(vendor))

        assertEquals(3 + 2 + 16, AdvertisingPayload.sizeOf(data))
    }

    @Test
    fun `includes device name and tx power when requested`() {
        val data = AdvertiseData(includeDeviceName = true, includeTxPowerLevel = true)

        // flags (3) + tx power (3) + name (2 + 10)
        assertEquals(18, AdvertisingPayload.sizeOf(data, nameLength = 10))
    }

    @Test
    fun `rejects a payload that does not fit the legacy budget`() {
        val payload = ByteArray(29) { 0x11 }
        val data = AdvertiseData(
            serviceUuids = listOf(BluetoothUuid.HEART_RATE),
            manufacturerData = mapOf(0x004C to payload),
            includeDeviceName = true,
        )

        val failure = assertFailsWith<BlueLibValidationException.AdvertisingDataTooLarge> {
            AdvertisingPayload.validate(data, section = "advertiseData", nameLength = 12)
        }

        assertTrue(failure.maximumBytes == AdvertisingPayload.LEGACY_MAX_BYTES)
        assertTrue(failure.requestedBytes > failure.maximumBytes)
        assertEquals("advertising-data-too-large", failure.docsAnchor)
    }

    @Test
    fun `accepts the same payload with an extended advertising budget`() {
        val payload = ByteArray(200) { 0x11 }
        val data = AdvertiseData(manufacturerData = mapOf(0x004C to payload))

        AdvertisingPayload.validate(
            data,
            section = "advertiseData",
            budget = AdvertisingPayload.EXTENDED_MAX_BYTES,
        )
    }

    @Test
    fun `rejects a single AD structure longer than 255 bytes`() {
        // 252 byte payload + 2 byte company id = 254, above the 253 byte AD structure limit.
        val payload = ByteArray(252)
        val data = AdvertiseData(manufacturerData = mapOf(0x004C to payload))

        assertFailsWith<BlueLibValidationException.AdvertisingDataTooLarge> {
            AdvertisingPayload.validate(
                data,
                section = "advertiseData",
                budget = AdvertisingPayload.EXTENDED_MAX_BYTES,
            )
        }
    }

    @Test
    fun `rejects an empty device name`() {
        val data = AdvertiseData(includeDeviceName = true)

        assertFailsWith<BlueLibValidationException.ValueOutOfRange> {
            AdvertisingPayload.validate(data, section = "advertiseData", nameLength = 0)
        }
    }

    @Test
    fun `rejects a device name that cannot fit a legacy packet`() {
        val data = AdvertiseData(includeDeviceName = true)

        assertFailsWith<BlueLibValidationException.ValueOutOfRange> {
            AdvertisingPayload.validate(
                data,
                section = "advertiseData",
                nameLength = AdvertisingPayload.LEGACY_MAX_BYTES,
            )
        }
    }
}
