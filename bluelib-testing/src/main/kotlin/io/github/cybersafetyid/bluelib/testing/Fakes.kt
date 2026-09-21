package io.github.cybersafetyid.bluelib.testing

import io.github.cybersafetyid.bluelib.domain.BluetoothFeature
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingHandle
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingRequest
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.BondState
import io.github.cybersafetyid.bluelib.domain.model.ConnectionPriority
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import io.github.cybersafetyid.bluelib.domain.model.GattProfile
import io.github.cybersafetyid.bluelib.domain.model.GattServerConnection
import io.github.cybersafetyid.bluelib.domain.model.GattServerRequest
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.PhyCoding
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import io.github.cybersafetyid.bluelib.port.BleAdvertisePort
import io.github.cybersafetyid.bluelib.port.BleScanPort
import io.github.cybersafetyid.bluelib.port.ClassicConnection
import io.github.cybersafetyid.bluelib.port.ClassicDevice
import io.github.cybersafetyid.bluelib.port.ClassicDiscoveryEvent
import io.github.cybersafetyid.bluelib.port.ClassicPort
import io.github.cybersafetyid.bluelib.port.ClockPort
import io.github.cybersafetyid.bluelib.port.DiagnosticEvent
import io.github.cybersafetyid.bluelib.port.DiagnosticsPort
import io.github.cybersafetyid.bluelib.port.GattClientPort
import io.github.cybersafetyid.bluelib.port.GattConnectRequest
import io.github.cybersafetyid.bluelib.port.GattServer
import io.github.cybersafetyid.bluelib.port.GattSession
import io.github.cybersafetyid.bluelib.port.PermissionPort
import io.github.cybersafetyid.bluelib.port.PermissionStatus
import io.github.cybersafetyid.bluelib.port.SocketSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * Fakes for every BlueLib port, for testing an application without a device.
 *
 * A fake is only useful if it behaves like the real thing in the ways that matter, so these record
 * what they were asked to do and let a test script the interesting failure: an adapter that turns off
 * mid-scan, a characteristic read that returns status 133, a peripheral that never answers discovery.
 *
 * ```kotlin
 * val clock = FakeClock()
 * val diagnostics = RecordingDiagnostics(clock)
 * val session = FakeGattSession(deviceId, clock).apply { failNextRead(BlueLibError.GattOperationFailed("read", GattStatus.CONNECTION_CONGESTED)) }
 * ```
 */
public class FakeClock(private var nowMillis: Long = 1_700_000_000_000L) : ClockPort {

    override fun nowMillis(): Long = nowMillis

    /** Moves the clock forward, which is what expires timeouts and scan quotas. */
    public fun advanceBy(millis: Long) {
        require(millis >= 0) { "time only moves forward" }
        nowMillis += millis
    }
}

/** Diagnostics sink that keeps every event so a test can assert on them. */
public class RecordingDiagnostics(private val clock: ClockPort = FakeClock()) : DiagnosticsPort {

    private val mutableEvents = MutableSharedFlow<DiagnosticEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val recorded = mutableListOf<DiagnosticEvent>()

    override val events: Flow<DiagnosticEvent> = mutableEvents.asSharedFlow()

    /** Every event emitted so far, in order. */
    public val recordedEvents: List<DiagnosticEvent> get() = recorded.toList()

    /** Errors reported so far, which is usually what a test cares about. */
    public val reportedErrors: List<BlueLibError>
        get() = recorded.filterIsInstance<DiagnosticEvent.ErrorReported>().map { it.error }

    override fun emit(event: DiagnosticEvent) {
        recorded += event
        mutableEvents.tryEmit(event)
    }

    /** Convenience matching how the platform layer reports a failed operation. */
    public fun error(error: BlueLibError, deviceId: BluetoothDeviceId? = null) {
        emit(DiagnosticEvent.ErrorReported(error, deviceId, clock.nowMillis()))
    }

    /** Forgets every recorded event. */
    public fun clear() {
        recorded.clear()
    }
}

/** Permission port whose answers a test sets per permission. */
public class FakePermissionPort(
    private var default: PermissionStatus = PermissionStatus.GRANTED,
) : PermissionPort {

    private val statuses = mutableMapOf<String, PermissionStatus>()

    override fun statusOf(permission: String): PermissionStatus = statuses[permission] ?: default

    /** Sets the status of one permission. */
    public fun set(permission: String, status: PermissionStatus): FakePermissionPort = apply {
        statuses[permission] = status
    }

    /** Grants everything. */
    public fun grantAll(): FakePermissionPort = apply {
        statuses.clear()
        default = PermissionStatus.GRANTED
    }

    /** Denies everything, as a user who dismissed the prompt would. */
    public fun denyAll(permanently: Boolean = false): FakePermissionPort = apply {
        statuses.clear()
        default = if (permanently) PermissionStatus.DENIED_PERMANENTLY else PermissionStatus.DENIED
    }
}

/** Scanner that replays a scripted list of results instead of touching a radio. */
public class FakeBleScanPort(
    private val scriptedResults: List<io.github.cybersafetyid.bluelib.port.ScanEvent> = emptyList(),
    private var failure: BlueLibError? = null,
) : BleScanPort {

    /** Requests that were started, in order. */
    public val startedRequests: MutableList<ScanRequest> = mutableListOf()

    private var scanning = false

    override val isScanning: Boolean get() = scanning

    /** Makes the next scan fail with [error]. */
    public fun failWith(error: BlueLibError?) {
        failure = error
    }

    override fun scan(request: ScanRequest): Flow<io.github.cybersafetyid.bluelib.port.ScanEvent> {
        startedRequests += request
        val error = failure
        return flow {
            scanning = true
            try {
                if (error != null) {
                    emit(io.github.cybersafetyid.bluelib.port.ScanEvent.Failed(error))
                    return@flow
                }
                scriptedResults.forEach { emit(it) }
            } finally {
                scanning = false
            }
        }
    }
}

/** Advertiser that records start and stop calls. */
public class FakeBleAdvertisePort(
    override val isAdvertiserAvailable: Boolean = true,
    private val failure: BlueLibError? = null,
) : BleAdvertisePort {

    public val started: MutableList<AdvertisingRequest> = mutableListOf()
    public val stopped: MutableList<AdvertisingHandle> = mutableListOf()

    override suspend fun start(request: AdvertisingRequest): AdvertisingHandle {
        failure?.let { throw io.github.cybersafetyid.bluelib.domain.error.BlueLibException(it) }
        started += request
        return AdvertisingHandle(setId = started.size, isExtended = request.parameters.useExtendedAdvertising)
    }

    override suspend fun stop(handle: AdvertisingHandle) {
        stopped += handle
    }

    override suspend fun stopAll() {
        stopped += started.indices.map { AdvertisingHandle(setId = it + 1, isExtended = false) }
    }
}

/**
 * One GATT connection whose every operation a test scripts.
 *
 * Failing the *next* operation rather than all of them is what makes retry and recovery paths
 * testable: the first read returns 133, the retry succeeds, and the test asserts the value arrived.
 */
public class FakeGattSession(
    override val deviceId: BluetoothDeviceId,
    mtu: Int = 23,
    profile: GattProfile? = null,
) : GattSession {

    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val mutableProfile = MutableStateFlow(profile)
    private val mutableMtu = MutableStateFlow(mtu)

    private val pendingReads = ArrayDeque<BlueLibResult<ByteArray>>()
    private val pendingWrites = ArrayDeque<BlueLibResult<Unit>>()

    private val notificationChannels = mutableMapOf<String, MutableSharedFlow<ByteArray>>()

    /** Characteristics read so far, as `service|characteristic`. */
    public val reads: MutableList<String> = mutableListOf()

    /** Characteristics written so far, with the mode used. */
    public val writes: MutableList<Triple<String, ByteArray, WriteMode>> = mutableListOf()

    /** Requested MTUs, in order. */
    public val mtuRequests: MutableList<Int> = mutableListOf()

    /** Requested priorities, in order. */
    public val priorityRequests: MutableList<ConnectionPriority> = mutableListOf()

    /** Requested PHYs, in order. */
    public val phyRequests: MutableList<Phy> = mutableListOf()

    public var lastKnownPhy: Phy = Phy.LE_1M

    /** `true` once [close] was called; used to assert that connections are not leaked. */
    public var closed: Boolean = false
        private set

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    override val profile: StateFlow<GattProfile?> = mutableProfile.asStateFlow()

    override val mtu: StateFlow<Int> = mutableMtu.asStateFlow()

    /** Moves the connection into a new state as the platform would. */
    public fun moveTo(state: ConnectionState) {
        mutableState.value = state
    }

    /** Scripts the result of the next read (or of every read when called once with a sticky value). */
    public fun nextReadReturns(result: BlueLibResult<ByteArray>): FakeGattSession = apply {
        pendingReads.addLast(result)
    }

    /** Scripts the result of the next write. */
    public fun nextWriteReturns(result: BlueLibResult<Unit>): FakeGattSession = apply {
        pendingWrites.addLast(result)
    }

    /** Delivers a notification on a subscribed characteristic. */
    public fun emitNotification(service: BluetoothUuid, characteristic: BluetoothUuid, value: ByteArray) {
        notificationChannels.getOrPut("$service|$characteristic") {
            MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }.tryEmit(value)
    }

    override suspend fun discoverServices(): BlueLibResult<GattProfile> =
        successOf(mutableProfile.value ?: GattProfile())

    override suspend fun read(service: BluetoothUuid, characteristic: BluetoothUuid): BlueLibResult<ByteArray> {
        reads += "$service|$characteristic"
        val scripted = pendingReads.removeFirstOrNull()
        return scripted ?: successOf(ByteArray(0))
    }

    override suspend fun write(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        value: ByteArray,
        mode: WriteMode,
    ): BlueLibResult<Unit> {
        writes += Triple("$service|$characteristic", value, mode)
        return pendingWrites.removeFirstOrNull() ?: successOf(Unit)
    }

    override suspend fun readDescriptor(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
    ): BlueLibResult<ByteArray> = successOf(ByteArray(0))

    override suspend fun writeDescriptor(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
        value: ByteArray,
    ): BlueLibResult<Unit> = successOf(Unit)

    override fun subscribe(service: BluetoothUuid, characteristic: BluetoothUuid): Flow<ByteArray> =
        notificationChannels.getOrPut("$service|$characteristic") {
            MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }.asSharedFlow()

    override suspend fun requestConnectionPriority(priority: ConnectionPriority): BlueLibResult<Unit> {
        priorityRequests += priority
        return successOf(Unit)
    }

    override suspend fun requestPhy(phy: Phy, coding: PhyCoding?): BlueLibResult<Phy> {
        phyRequests += phy
        lastKnownPhy = phy
        return successOf(phy)
    }

    override suspend fun readPhy(): BlueLibResult<List<Phy>> = successOf(listOf(lastKnownPhy))

    override fun close() {
        closed = true
        mutableState.value = ConnectionState.CLOSED
    }
}

/** GATT client that hands out [FakeGattSession]s and records the requests. */
public class FakeGattClient(
    private val defaultProfile: GattProfile? = null,
    private var failure: BlueLibError? = null,
) : GattClientPort {

    private val sessions = mutableMapOf<String, FakeGattSession>()

    /** Connect requests received, in order. */
    public val connectRequests: MutableList<Pair<BluetoothDeviceId, GattConnectRequest>> = mutableListOf()

    override val openSessions: List<BluetoothDeviceId>
        get() = sessions.values.filterNot { it.closed }.map { it.deviceId }

    /** Makes the next connect fail with [error]. */
    public fun failNextConnect(error: BlueLibError?) {
        failure = error
    }

    override suspend fun connect(
        deviceId: BluetoothDeviceId,
        request: GattConnectRequest,
    ): BlueLibResult<GattSession> {
        connectRequests += deviceId to request
        failure?.let { error ->
            failure = null
            return failureOf(error)
        }
        val session = sessions.getOrPut(deviceId.address.value) {
            FakeGattSession(deviceId, mtu = request.mtu ?: 23, profile = defaultProfile).apply {
                moveTo(ConnectionState.READY)
            }
        }
        return successOf(session)
    }

    /** The session handed out for [deviceId], for assertions. */
    public fun sessionFor(deviceId: BluetoothDeviceId): FakeGattSession? = sessions[deviceId.address.value]
}

/** Classic port with scripted discovery results and socket behaviour. */
public class FakeClassicPort(
    private val discoveryEvents: List<ClassicDiscoveryEvent> = emptyList(),
    private val bonded: List<ClassicDevice> = emptyList(),
) : ClassicPort {

    private val mutableBonded = MutableStateFlow(bonded)

    /** Bond requests received, in order. */
    public val bondRequests: MutableList<BluetoothDeviceId> = mutableListOf()

    /** RFCOMM connections requested, in order. */
    public val rfcommRequests: MutableList<Triple<BluetoothDeviceId, BluetoothUuid, SocketSettings>> = mutableListOf()

    /** What [bond] should answer; `null` means success. */
    public var bondFailure: BlueLibError? = null

    override fun discover(includeRssi: Boolean): Flow<ClassicDiscoveryEvent> =
        flow { discoveryEvents.forEach { emit(it) } }

    override fun bondedDevices(): Flow<List<ClassicDevice>> = mutableBonded.asStateFlow()

    override fun bondState(deviceId: BluetoothDeviceId): Flow<BondState> = flow {
        emit(mutableBonded.value.firstOrNull { it.deviceId == deviceId }?.bondState ?: BondState.NONE)
    }

    override suspend fun bond(
        deviceId: BluetoothDeviceId,
        transport: Transport,
        timeoutMillis: Long,
    ): BlueLibResult<Unit> {
        bondRequests += deviceId
        if (bondFailure != null) {
            return failureOf(bondFailure!!)
        }
        val currentList = mutableBonded.value
        val existingIndex = currentList.indexOfFirst { it.deviceId == deviceId }
        if (existingIndex >= 0) {
            val updated = currentList.toMutableList()
            updated[existingIndex] = updated[existingIndex].copy(bondState = BondState.BONDED)
            mutableBonded.value = updated
        } else {
            mutableBonded.value = currentList + ClassicDevice(deviceId = deviceId, bondState = BondState.BONDED)
        }
        return successOf(Unit)
    }

    override suspend fun unbond(deviceId: BluetoothDeviceId): BlueLibResult<Unit> = successOf(Unit)

    override suspend fun connectRfcomm(
        deviceId: BluetoothDeviceId,
        serviceUuid: BluetoothUuid,
        settings: SocketSettings,
    ): BlueLibResult<ClassicConnection> {
        rfcommRequests += Triple(deviceId, serviceUuid, settings)
        return failureOf(
            BlueLibError.FeatureUnsupported("fake RFCOMM", currentApiLevel = 21, requiredApiLevel = 21),
        )
    }

    override suspend fun connectL2cap(
        deviceId: BluetoothDeviceId,
        psm: Int,
        settings: SocketSettings,
    ): BlueLibResult<ClassicConnection> = failureOf(
        BlueLibError.FeatureUnsupported("fake L2CAP", currentApiLevel = 21, requiredApiLevel = 21),
    )
}

/** GATT server that records responses and lets a test push requests. */
public class FakeGattServer : GattServer {

    private val mutableConnections = MutableStateFlow<List<GattServerConnection>>(emptyList())
    private val mutableRequests = MutableSharedFlow<GattServerRequest>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Answers sent so far: request id, status, offset and payload. */
    public val responses: MutableList<Response> = mutableListOf()

    /** Notifications sent so far. */
    public val notifications: MutableList<Triple<String, String, ByteArray>> = mutableListOf()

    public var closed: Boolean = false
        private set

    override val connections: StateFlow<List<GattServerConnection>> = mutableConnections.asStateFlow()

    override val requests: Flow<GattServerRequest> = mutableRequests.asSharedFlow()

    /** Simulates a central sending a request to this server. */
    public fun simulateRequest(request: GattServerRequest) {
        mutableRequests.tryEmit(request)
    }

    /** Simulates a central connecting. */
    public fun simulateConnection(connection: GattServerConnection) {
        mutableConnections.value = mutableConnections.value + connection
    }

    override suspend fun notify(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        value: ByteArray,
        confirm: Boolean,
    ): BlueLibResult<Unit> {
        notifications += Triple(service.toString(), characteristic.toString(), value)
        return successOf(Unit)
    }

    override suspend fun sendResponse(
        deviceId: BluetoothDeviceId,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ): BlueLibResult<Unit> {
        responses += Response(deviceId, requestId, status, offset, value)
        return successOf(Unit)
    }

    override suspend fun requestMtu(deviceId: BluetoothDeviceId, mtu: Int): BlueLibResult<Int> =
        successOf(mtu)

    override fun close() {
        closed = true
        mutableConnections.value = emptyList()
    }

    /** One captured `sendResponse` call. */
    public data class Response(
        val deviceId: BluetoothDeviceId,
        val requestId: Int,
        val status: Int,
        val offset: Int,
        val value: ByteArray?,
    )
}

/** Adapter source that reports a fixed capability set. */
public class FakeAdapterSource(
    private val features: Set<BluetoothFeature> = BluetoothFeature.entries.toSet(),
    private val available: Boolean = true,
    private val state: String = "ON",
    override val apiLevel: Int = 37,
    override val minorApiLevel: Int = 0,
) : io.github.cybersafetyid.bluelib.port.AdapterAvailabilityPort {

    private val mutableState = MutableStateFlow(state)

    override val isAdapterAvailable: Boolean get() = available

    override val adapterState: StateFlow<String> = mutableState.asStateFlow()

    override fun isFeatureSupported(feature: BluetoothFeature): Boolean = available && feature in features

    /** Simulates the user turning Bluetooth on or off. */
    public fun setState(name: String) {
        mutableState.value = name
    }
}

/** A GATT server port that opens exactly one server. */
public class FakeGattServerPort(
    private val server: FakeGattServer = FakeGattServer(),
    private var failure: BlueLibError? = null,
) : io.github.cybersafetyid.bluelib.port.GattServerPort {

    private var opened = false

    override val isOpen: Boolean get() = opened

    /** The server handed out, for assertions. */
    public val currentServer: FakeGattServer get() = server

    /** Makes the next `open` fail with [error]. */
    public fun failNextOpen(error: BlueLibError?) {
        failure = error
    }

    override suspend fun open(
        config: io.github.cybersafetyid.bluelib.domain.model.GattServerConfig,
    ): BlueLibResult<GattServer> {
        failure?.let { error ->
            failure = null
            return failureOf(error)
        }
        opened = true
        return successOf(server)
    }
}

/** Flow that never emits and never completes, for tests that need a collector to stay suspended. */
public fun <T> neverEmits(): Flow<T> = emptyFlow()
