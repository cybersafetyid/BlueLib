package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.port.ClassicDevice
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPairFilterTest {

    private val targetMac = "11:22:33:44:55:66"
    private val deviceId = BluetoothDeviceId.of(targetMac)
    private val uuid1 = BluetoothUuid.parse("0000180f-0000-1000-8000-00805f9b34fb")
    private val uuid2 = BluetoothUuid.parse("0000180d-0000-1000-8000-00805f9b34fb")

    @Test
    fun `empty filter throws validation exception`() {
        assertFailsWith<BlueLibValidationException.EmptyFilter> {
            AutoPairFilter()
        }
    }

    @Test
    fun `invalid minRssi throws ValueOutOfRange`() {
        assertFailsWith<BlueLibValidationException.ValueOutOfRange> {
            AutoPairFilter(deviceName = "MyDevice", minRssi = -200)
        }
    }

    @Test
    fun `matches ScanObservation with target address`() {
        val filter = AutoPairFilter(targetAddress = BluetoothAddress.parse(targetMac))
        val matchingObs = ScanObservation(
            deviceId = deviceId,
            rssi = -60,
            deviceName = "OtherDevice",
        )
        val nonMatchingObs = ScanObservation(
            deviceId = BluetoothDeviceId.of("AA:BB:CC:DD:EE:FF"),
            rssi = -60,
        )

        assertTrue(filter.matches(matchingObs))
        assertFalse(filter.matches(nonMatchingObs))
    }

    @Test
    fun `matches ScanObservation with device name and name prefix`() {
        val exactNameFilter = AutoPairFilter(deviceName = "SmartBand")
        val prefixFilter = AutoPairFilter(namePrefix = "Smart")

        val obs1 = ScanObservation(deviceId = deviceId, rssi = -50, deviceName = "SmartBand")
        val obs2 = ScanObservation(deviceId = deviceId, rssi = -50, deviceName = "SmartWatch")

        assertTrue(exactNameFilter.matches(obs1))
        assertFalse(exactNameFilter.matches(obs2))

        assertTrue(prefixFilter.matches(obs1))
        assertTrue(prefixFilter.matches(obs2))
    }

    @Test
    fun `matches ScanObservation with service UUIDs, manufacturer data, and RSSI`() {
        val filter = AutoPairFilter(
            serviceUuids = listOf(uuid1),
            manufacturerId = 0x004C,
            minRssi = -70,
        )

        val validObs = ScanObservation(
            deviceId = deviceId,
            rssi = -65,
            serviceUuids = listOf(uuid1, uuid2),
            manufacturerData = mapOf(0x004C to byteArrayOf(0x01, 0x02)),
        )

        val weakRssiObs = validObs.copy(rssi = -80)
        val missingManufacturerObs = validObs.copy(manufacturerData = emptyMap())
        val missingUuidObs = validObs.copy(serviceUuids = emptyList())

        assertTrue(filter.matches(validObs))
        assertFalse(filter.matches(weakRssiObs))
        assertFalse(filter.matches(missingManufacturerObs))
        assertFalse(filter.matches(missingUuidObs))
    }

    @Test
    fun `matches ClassicDevice with name and RSSI`() {
        val filter = AutoPairFilter(deviceName = "Headphones", minRssi = -70)
        val classicDevice = ClassicDevice(deviceId = deviceId, name = "Headphones")

        assertTrue(filter.matches(classicDevice, rssi = -60))
        assertFalse(filter.matches(classicDevice, rssi = -80))
        assertFalse(filter.matches(classicDevice.copy(name = "Speaker"), rssi = -60))
    }
}
