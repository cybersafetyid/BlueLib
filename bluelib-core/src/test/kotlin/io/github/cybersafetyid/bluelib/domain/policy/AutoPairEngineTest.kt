package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibErrorCode
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.AutoPairFilter
import io.github.cybersafetyid.bluelib.domain.model.BluetoothAddress
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.BondState
import io.github.cybersafetyid.bluelib.domain.model.ScanObservation
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.port.BleScanPort
import io.github.cybersafetyid.bluelib.port.ClassicConnection
import io.github.cybersafetyid.bluelib.port.ClassicDevice
import io.github.cybersafetyid.bluelib.port.ClassicDiscoveryEvent
import io.github.cybersafetyid.bluelib.port.ClassicPort
import io.github.cybersafetyid.bluelib.port.ScanEvent
import io.github.cybersafetyid.bluelib.port.SocketSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
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
        val scanPort = TestScanPort(listOf(ScanEvent.Observed(observation)))
        val classicPort = TestClassicPort()

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
        val scanPort = TestScanPort(listOf(ScanEvent.Observed(observation)))
        val bondedDevice = ClassicDevice(deviceId = targetDeviceId, bondState = BondState.BONDED)
        val classicPort = TestClassicPort(bondedDevicesList = listOf(bondedDevice))

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
        val scanPort = TestScanPort(listOf(ScanEvent.Observed(nonMatchingObs)))
        val classicPort = TestClassicPort()

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
        val scanPort = TestScanPort(emptyList())
        val classicPort = TestClassicPort(discoveryEvents = discoveryEvents)

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
        val scanPort = TestScanPort(listOf(ScanEvent.Observed(observation)))
        val classicPort = TestClassicPort().apply {
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

    private class TestScanPort(private val events: List<ScanEvent>) : BleScanPort {
        override val isScanning: Boolean = false
        override fun scan(request: ScanRequest): Flow<ScanEvent> = events.asFlow()
    }

    private class TestClassicPort(
        private val discoveryEvents: List<ClassicDiscoveryEvent> = emptyList(),
        private val bondedDevicesList: List<ClassicDevice> = emptyList(),
    ) : ClassicPort {
        val bondRequests = mutableListOf<BluetoothDeviceId>()
        var bondFailure: BlueLibError? = null

        override fun discover(includeRssi: Boolean): Flow<ClassicDiscoveryEvent> = discoveryEvents.asFlow()
        override fun bondedDevices(): Flow<List<ClassicDevice>> = MutableStateFlow(bondedDevicesList)
        override fun bondState(deviceId: BluetoothDeviceId): Flow<BondState> = flowOf(
            bondedDevicesList.firstOrNull { it.deviceId == deviceId }?.bondState ?: BondState.NONE,
        )
        override suspend fun bond(
            deviceId: BluetoothDeviceId,
            transport: Transport,
            timeoutMillis: Long,
        ): BlueLibResult<Unit> {
            bondRequests += deviceId
            return bondFailure?.let { failureOf(it) } ?: successOf(Unit)
        }
        override suspend fun unbond(deviceId: BluetoothDeviceId): BlueLibResult<Unit> = successOf(Unit)
        override suspend fun connectRfcomm(deviceId: BluetoothDeviceId, serviceUuid: BluetoothUuid, settings: SocketSettings): BlueLibResult<ClassicConnection> =
            failureOf(BlueLibError.FeatureUnsupported("fake", 21, 21))
        override suspend fun connectL2cap(deviceId: BluetoothDeviceId, psm: Int, settings: SocketSettings): BlueLibResult<ClassicConnection> =
            failureOf(BlueLibError.FeatureUnsupported("fake", 21, 21))
    }
}
