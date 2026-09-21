package io.github.cybersafetyid.bluelib.port

import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.ConnectionPriority
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import io.github.cybersafetyid.bluelib.domain.model.GattProfile
import io.github.cybersafetyid.bluelib.domain.model.GattServerConfig
import io.github.cybersafetyid.bluelib.domain.model.GattServerConnection
import io.github.cybersafetyid.bluelib.domain.model.GattServerRequest
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.PhyCoding
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import io.github.cybersafetyid.bluelib.domain.validation.PayloadSegmenter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything a caller can ask for when opening a GATT connection.
 *
 * Defaults follow the platform pitfalls documented in `docs/research/gatt-pitfalls.md`: LE transport
 * only (`TRANSPORT_AUTO` can silently fall back to BR/EDR and break GATT), `autoConnect` off (the
 * platform hides failures while it is on), service discovery performed eagerly, and every operation
 * timed out.
 */
public data class GattConnectRequest(
    val transport: Transport = Transport.LE,
    /**
     * Leave `false` unless you need Android to reconnect for you. With `autoConnect = true` the
     * platform does not report connect failures and can hold the connection for a long time.
     */
    val autoConnect: Boolean = false,
    /** Requested ATT MTU; `null` skips negotiation and keeps the 23 byte default. */
    val mtu: Int? = PayloadSegmenter.MAX_MTU,
    val connectionPriority: ConnectionPriority = ConnectionPriority.BALANCED,
    val discoverServices: Boolean = true,
    /** Ask for the 2M PHY once connected (Android 8.0+). */
    val preferHighThroughputPhy: Boolean = false,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val retries: Int = 2,
    /** Opportunistic connections (Android 17 connection settings) are not reported by the platform. */
    val opportunistic: Boolean = false,
) {
    init {
        mtu?.let { PayloadSegmenter.requireValidMtu(it) }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        require(retries >= 0) { "retries must be >= 0" }
        require(transport.supportsGatt) { "GATT requires the LE or AUTO transport" }
    }

    public companion object {
        /** Default deadline for connect plus discovery; long enough for slow peripherals. */
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 15_000L
    }
}

/** One GATT connection to a peripheral. */
public interface GattSession : AutoCloseable {
    /** The peripheral. */
    public val deviceId: BluetoothDeviceId

    /** Connection state as a hot stream; collected by the library's diagnostics too. */
    public val state: StateFlow<ConnectionState>

    /** Discovered profile, `null` until discovery completes. */
    public val profile: StateFlow<GattProfile?>

    /** Negotiated MTU; starts at 23 until negotiation finishes. */
    public val mtu: StateFlow<Int>

    /** (Re)discovers services. Safe to call after `onServiceChanged`. */
    public suspend fun discoverServices(): BlueLibResult<GattProfile>

    /** Reads a characteristic value. */
    public suspend fun read(service: BluetoothUuid, characteristic: BluetoothUuid): BlueLibResult<ByteArray>

    /** Writes a characteristic value, chunking it when [mode] is `LONG`. */
    public suspend fun write(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        value: ByteArray,
        mode: WriteMode = WriteMode.WITH_RESPONSE,
    ): BlueLibResult<Unit>

    /** Reads a descriptor value. */
    public suspend fun readDescriptor(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
    ): BlueLibResult<ByteArray>

    /** Writes a descriptor value. */
    public suspend fun writeDescriptor(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
        value: ByteArray,
    ): BlueLibResult<Unit>

    /**
     * Subscribes to notifications, writing the Client Characteristic Configuration descriptor first
     * and re-subscribing automatically if the connection is restored.
     */
    public fun subscribe(service: BluetoothUuid, characteristic: BluetoothUuid): Flow<ByteArray>

    /** Requests a connection priority. */
    public suspend fun requestConnectionPriority(priority: ConnectionPriority): BlueLibResult<Unit>

    /** Requests a PHY combination (Android 8.0+). */
    public suspend fun requestPhy(phy: Phy, coding: PhyCoding? = null): BlueLibResult<Phy>

    /** Reads the current PHY combination (Android 8.0+). */
    public suspend fun readPhy(): BlueLibResult<List<Phy>>
}

/** GATT client entry point. */
public interface GattClientPort {
    /** Opens a connection, or returns the existing session for the device when one is alive. */
    public suspend fun connect(deviceId: BluetoothDeviceId, request: GattConnectRequest): BlueLibResult<GattSession>

    /** Sessions currently open, for leak detection and diagnostics. */
    public val openSessions: List<BluetoothDeviceId>
}

/** Local GATT server. */
public interface GattServer : AutoCloseable {
    /** Centrals currently connected. */
    public val connections: StateFlow<List<GattServerConnection>>

    /**
     * Requests received from centrals.
     *
     * Hot and lossless enough for interactive use (bounded buffer, oldest dropped): every request
     * still must be answered through [sendResponse] or the central will time out. The buffer exists so
     * a slow collector cannot block the binder thread — not so requests can be ignored.
     */
    public val requests: Flow<GattServerRequest>

    /** Sends a notification or indication to one central. */
    public suspend fun notify(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        value: ByteArray,
        confirm: Boolean = false,
    ): BlueLibResult<Unit>

    /** Answers a read or write request received from a central. */
    public suspend fun sendResponse(
        deviceId: BluetoothDeviceId,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ): BlueLibResult<Unit>

    /** Requests a higher MTU for a connected central (Android 6.0+). */
    public suspend fun requestMtu(deviceId: BluetoothDeviceId, mtu: Int): BlueLibResult<Int>

    /** Convenience over [sendResponse]: answers [request] with the ATT success status. */
    public suspend fun respond(
        request: GattServerRequest,
        value: ByteArray? = null,
    ): BlueLibResult<Unit> = sendResponse(
        deviceId = request.deviceId,
        requestId = request.requestId,
        status = GATT_SUCCESS,
        offset = request.offset,
        value = value,
    )

    public companion object {
        /** `BluetoothGatt.GATT_SUCCESS`. */
        public const val GATT_SUCCESS: Int = 0
    }
}

/** GATT server entry point. */
public interface GattServerPort {
    /** Opens a server exposing [config]. */
    public suspend fun open(config: GattServerConfig): BlueLibResult<GattServer>

    /** `true` when a server is currently open. */
    public val isOpen: Boolean
}
