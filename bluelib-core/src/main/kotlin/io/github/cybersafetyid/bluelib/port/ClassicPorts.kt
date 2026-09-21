package io.github.cybersafetyid.bluelib.port

import io.github.cybersafetyid.bluelib.domain.BluetoothFeature
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.BondLossReason
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.BondState
import io.github.cybersafetyid.bluelib.domain.model.Transport
import kotlinx.coroutines.flow.Flow

/** A Classic (BR/EDR) device as reported by discovery or the bonded device list. */
public data class ClassicDevice(
    val deviceId: BluetoothDeviceId,
    val name: String? = null,
    val bondState: BondState = BondState.NONE,
    /** `BluetoothClass` description, for example `Audio/Video (Headphones)`. */
    val deviceClass: String? = null,
    val uuids: List<BluetoothUuid> = emptyList(),
)

/** Events reported while discovering Classic devices. */
public sealed interface ClassicDiscoveryEvent {
    /** Discovery started. */
    public data object Started : ClassicDiscoveryEvent

    /** A device was found; [rssi] is only reported by API 30+ in the `ACTION_FOUND` extra. */
    public data class DeviceFound(val device: ClassicDevice, val rssi: Int?) : ClassicDiscoveryEvent

    /** Discovery finished, either because it completed or because it was cancelled. */
    public data object Finished : ClassicDiscoveryEvent

    /** Discovery failed. */
    public data class Failed(val error: BlueLibError) : ClassicDiscoveryEvent
}

/**
 * Settings used to open an RFCOMM or L2CAP socket.
 *
 * Android 16 (API 36) added `BluetoothSocketSettings` which finally exposes encryption and
 * authentication requirements; on older versions BlueLib falls back to the classic
 * `createRfcommSocketToServiceRecord` / `listenUsingRfcommWithServiceRecord` pair, and reports
 * [encryptionRequired] as unsupported instead of silently ignoring it.
 */
public data class SocketSettings(
    val serviceName: String = "BlueLib RFCOMM",
    val secure: Boolean = true,
    val encryptionRequired: Boolean = false,
    val authenticationRequired: Boolean = false,
    val feature: BluetoothFeature = BluetoothFeature.SOCKET_SETTINGS,
)

/** A connected Classic socket. */
public interface ClassicConnection : AutoCloseable {
    /** Remote device. */
    public val deviceId: BluetoothDeviceId

    /** Incoming bytes; the flow completes when the socket closes. */
    public val incoming: Flow<ByteArray>

    /** Writes bytes to the socket. */
    public suspend fun write(value: ByteArray): BlueLibResult<Unit>

    /** `true` while the socket is usable. */
    public val isConnected: Boolean
}

/** Bluetooth Classic port: discovery, bonding and sockets. */
public interface ClassicPort {
    /** Starts discovery and emits until the scan finishes or the collector cancels. */
    public fun discover(includeRssi: Boolean = true): Flow<ClassicDiscoveryEvent>

    /** Devices currently bonded to the phone. */
    public fun bondedDevices(): Flow<List<ClassicDevice>>

    /** Bond state changes for one device. */
    public fun bondState(deviceId: BluetoothDeviceId): Flow<BondState>

    /**
     * Creates a bond.
     *
     * On Android 17 (API 37) the platform also runs *autonomous re-pairing* when a bond is lost, so
     * BlueLib reports [BondLossReason] and the `systemRepairInProgress` flag instead of forcing a
     * manual pairing flow.
     */
    public suspend fun bond(
        deviceId: BluetoothDeviceId,
        transport: Transport = Transport.BREDR,
        timeoutMillis: Long = 30_000L,
    ): BlueLibResult<Unit>

    /** Removes the bond, using `CompanionDeviceManager.removeBond` on Android 16+ when possible. */
    public suspend fun unbond(deviceId: BluetoothDeviceId): BlueLibResult<Unit>

    /** Opens an RFCOMM socket to a service UUID (SPP, custom profiles, ...). */
    public suspend fun connectRfcomm(
        deviceId: BluetoothDeviceId,
        serviceUuid: BluetoothUuid,
        settings: SocketSettings = SocketSettings(),
    ): BlueLibResult<ClassicConnection>

    /** Opens an L2CAP channel (Android 10+ for insecure, Android 10+ for secure CoC). */
    public suspend fun connectL2cap(
        deviceId: BluetoothDeviceId,
        psm: Int,
        settings: SocketSettings = SocketSettings(),
    ): BlueLibResult<ClassicConnection>
}
