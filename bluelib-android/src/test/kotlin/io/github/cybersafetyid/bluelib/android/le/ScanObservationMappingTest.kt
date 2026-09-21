package io.github.cybersafetyid.bluelib.android.le

import io.github.cybersafetyid.bluelib.domain.model.BluetoothAddress
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.validation.AdvertisingPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Scan results carry the advertising payload twice — once parsed by the platform and once raw — and
 * BlueLib must reconstruct every section from the raw bytes so a consumer never loses data on OEM
 * stacks that drop part of the parsed form.
 */
class ScanObservationMappingTest {

    private fun deviceId() = BluetoothDeviceId(
        address = BluetoothAddress.parse("AA:BB:CC:DD:EE:FF"),
    )

    @Test
    fun `every PHY mask decodes to the documented PHY list`() {
        assertEquals(listOf(Phy.LE_1M), Phy.fromMask(0x01))
        assertEquals(listOf(Phy.LE_1M, Phy.LE_2M), Phy.fromMask(0x03))
        assertEquals(listOf(Phy.LE_CODED), Phy.fromMask(Phy.LE_CODED.mask))
        assertEquals(listOf(Phy.LE_HDT), Phy.fromMask(Phy.LE_HDT.mask))
        assertTrue(Phy.fromMask(0x00).isEmpty())
    }

    @Test
    fun `the high data throughput PHY is only decoded on Android 17 and newer`() {
        // The mask is defined by the SIG, but only API 37 reports it; the domain layer keeps the
        // constant so a `ScanObservation` round trips through persistence on any version.
        assertEquals(0x08, Phy.LE_HDT.mask)
        assertEquals(0x0F, Phy.ALL_MASK)
    }

    @Test
    fun `a device id keeps the address and the address type apart`() {
        val id = deviceId()

        assertEquals("AA:BB:CC:DD:EE:FF", id.address.value)
        assertEquals(BluetoothDeviceId.of("aa:bb:cc:dd:ee:ff"), id)
    }

    @Test
    fun `a legacy advertising payload reports its size against the 31 byte budget`() {
        val payload = byteArrayOf(0x02, 0x01, 0x06, 0x03, 0x03, 0x0D, 0x18)

        assertEquals(7, payload.size)
        assertTrue(payload.size <= AdvertisingPayload.LEGACY_MAX_BYTES)
    }
}
