package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibErrorCode
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.model.AutoPairFilter
import io.github.cybersafetyid.bluelib.domain.model.BluetoothAddress
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BondState
import io.github.cybersafetyid.bluelib.domain.model.ScanObservation
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.port.ClassicDevice
import io.github.cybersafetyid.bluelib.port.ClassicDiscoveryEvent
import io.github.cybersafetyid.bluelib.port.ScanEvent
import io.github.cybersafetyid.bluelib.testing.FakeBleScanPort
import io.github.cybersafetyid.bluelib.testing.FakeClassicPort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoPairEngineTest {

    private val targetAddress = BluetoothAddress.parse("AA:BB:CC:11:22:33")
    private val targetDeviceId = BluetoothDeviceId(targetAddress)

    @Test
    fun `autoPair with LE filter scans, matches device and creates bond`() = runTest {
        val observation = ScanObservation(
            deviceId = targetDeviceId,
            rssi = -60,
            deviceName = "SmartBand-10",
        )
        val scanPort = FakeBleScanPort(scriptedResults = listOf(ScanEvent.Observed(observation)))
        val classicPort = FakeClassicPort()

        val filter = AutoPairFilter(deviceName = "SmartBand-10", transport = Transport.LE)

        val result = AutoPairEngine.autoPair(
            filter = filter,
            scanPort = scanPort,
            classicPort = classicPort,
            timeoutMillis = 5_000L,
        )

        assertTrue(result is BlueLibResult.Success)
        assertEquals(targetDeviceId, result.value)
        assertEquals(listOf(targetDeviceId), classicPort.bondRequests)
    }

    @Test
    fun `autoPair skips bonding if device is already bonded`() = runTest {
        val observation = ScanObservation(
            deviceId = targetDeviceId,
            rssi = -60,
            deviceName = "SmartBand-10",
        )
        val scanPort = FakeBleScanPort(scriptedResults = listOf(ScanEvent.Observed(observation)))
        val bondedDevice = ClassicDevice(deviceId = targetDeviceId, bondState = BondState.BONDED)
        val classicPort = FakeClassicPort(bonded = listOf(bondedDevice))

        val filter = AutoPairFilter(deviceName = "SmartBand-10")

        val result = AutoPairEngine.autoPair(
            filter = filter,
            scanPort = scanPort,
            classicPort = classicPort,
            timeoutMillis = 5_000L,
        )

        assertTrue(result is BlueLibResult.Success)
        assertEquals(targetDeviceId, result.value)
        assertTrue(classicPort.bondRequests.isEmpty(), "Should not request bonding when already bonded")
    }

    @Test
    fun `autoPair times out when no matching device is found in scan`() = runTest {
        val nonMatchingObs = ScanObservation(
            deviceId = BluetoothDeviceId.of("99:88:77:66:55:44"),
            rssi = -60,
            deviceName = "OtherDevice",
        )
        val scanPort = FakeBleScanPort(scriptedResults = listOf(ScanEvent.Observed(nonMatchingObs)))
        val classicPort = FakeClassicPort()

        val filter = AutoPairFilter(deviceName = "SmartBand-10")

        val result = AutoPairEngine.autoPair(
            filter = filter,
            scanPort = scanPort,
            classicPort = classicPort,
            timeoutMillis = 1_000L,
        )

        assertTrue(result is BlueLibResult.Failure)
        assertEquals(BlueLibErrorCode.TIMEOUT, result.error.code)
    }

    @Test
    fun `autoPair with BREDR filter discovers Classic device and creates bond`() = runTest {
        val classicDevice = ClassicDevice(deviceId = targetDeviceId, name = "ClassicSpeaker")
        val discoveryEvents = listOf(
            ClassicDiscoveryEvent.Started,
            ClassicDiscoveryEvent.DeviceFound(classicDevice, rssi = -55),
            ClassicDiscoveryEvent.Finished,
        )
        val scanPort = FakeBleScanPort()
        val classicPort = FakeClassicPort(discoveryEvents = discoveryEvents)

        val filter = AutoPairFilter(deviceName = "ClassicSpeaker", transport = Transport.BREDR)

        val result = AutoPairEngine.autoPair(
            filter = filter,
            scanPort = scanPort,
            classicPort = classicPort,
            timeoutMillis = 5_000L,
        )

        assertTrue(result is BlueLibResult.Success)
        assertEquals(targetDeviceId, result.value)
        assertEquals(listOf(targetDeviceId), classicPort.bondRequests)
    }

    @Test
    fun `autoPair returns error if bonding fails`() = runTest {
        val observation = ScanObservation(
            deviceId = targetDeviceId,
            rssi = -60,
            deviceName = "SmartBand-10",
        )
        val scanPort = FakeBleScanPort(scriptedResults = listOf(ScanEvent.Observed(observation)))
        val classicPort = FakeClassicPort().apply {
            bondFailure = BlueLibError.BondFailed(targetDeviceId, "User rejected pairing dialog")
        }

        val filter = AutoPairFilter(deviceName = "SmartBand-10")

        val result = AutoPairEngine.autoPair(
            filter = filter,
            scanPort = scanPort,
            classicPort = classicPort,
            timeoutMillis = 5_000L,
        )

        assertTrue(result is BlueLibResult.Failure)
        assertEquals(BlueLibErrorCode.BOND_FAILED, result.error.code)
    }
}
