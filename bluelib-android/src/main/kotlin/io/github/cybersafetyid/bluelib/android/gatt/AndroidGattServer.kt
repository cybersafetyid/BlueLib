package io.github.cybersafetyid.bluelib.android.gatt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothProfile
import android.os.Build
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.CharacteristicDefinition
import io.github.cybersafetyid.bluelib.domain.model.GattProperty
import io.github.cybersafetyid.bluelib.domain.model.GattServerConfig
import io.github.cybersafetyid.bluelib.domain.model.GattServerConnection
import io.github.cybersafetyid.bluelib.domain.model.GattServerRequest
import io.github.cybersafetyid.bluelib.domain.validation.PayloadSegmenter
import io.github.cybersafetyid.bluelib.port.GattServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Local GATT server.
 *
 * The class answers the two questions Android leaves to the application:
 *
 * 1. **Who answers the central?** By default BlueLib does, immediately, from its local value store. An
 *    unanswered GATT request leaves the central waiting for the 30 second ATT transaction timeout, and
 *    several stacks stop accepting further requests from that central meanwhile. Applications that
 *    need dynamic values set `autoRespond = false` and answer every request themselves.
 * 2. **How does a value get in and out?** Reads, writes and notifications all go through one store, so
 *    `notify` publishes exactly the bytes a later read returns instead of the two drifting apart.
 *
 * Notifications use `notifyCharacteristicChanged(device, characteristic, confirm, value)` on Android
 * 13+ instead of the deprecated form that writes the shared `value` field — with two centrals
 * connected that field is a data race.
 */
// `sendResponse`, `clearServices` and `notifyCharacteristicChanged` all require `BLUETOOTH_CONNECT` on
// Android 12+; the host checks it before opening the server, and `notify`/`sendResponse` return typed
// errors, which is why the static permission check is suppressed here.
@SuppressLint("MissingPermission")
public class AndroidGattServer internal constructor(
    private val config: GattServerConfig,
    private val diagnostics: AndroidDiagnostics,
) : GattServer {

    private val mutableConnections = MutableStateFlow<List<GattServerConnection>>(emptyList())
    private val mutableRequests = MutableSharedFlow<GattServerRequest>(
        replay = 0,
        extraBufferCapacity = REQUEST_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Attribute state: values, subscriptions and prepared writes, all Android free. */
    private val state = GattServerState(config)

    /** Platform device per connected central, needed to answer and to notify. */
    private val devices = ConcurrentHashMap<String, BluetoothDevice>()

    private val pendingNotifications = ConcurrentHashMap<String, CompletableDeferred<BlueLibResult<Unit>>>()

    @Volatile
    private var closed = false

    @Volatile
    private var server: BluetoothGattServer? = null

    override val connections: StateFlow<List<GattServerConnection>> = mutableConnections.asStateFlow()

    override val requests: Flow<GattServerRequest> = mutableRequests.asSharedFlow()

    /** `true` when every service has been registered. */
    public val isReady: Boolean
        get() = server != null

    internal fun attach(platformServer: BluetoothGattServer) {
        server = platformServer
    }

    /** Loads the initial values declared by [GattServerConfig.services]. */
    internal fun seedValues() {
        state.seed()
    }

    /** The current value of a characteristic exposed by this server. */
    public fun valueOf(service: BluetoothUuid, characteristic: BluetoothUuid): ByteArray? =
        state.valueOf(service, characteristic)

    /** `true` when [deviceId] enabled notifications or indications for [characteristic]. */
    public fun isSubscribed(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
    ): Boolean = state.isSubscribed(deviceId, service, characteristic)

    override suspend fun notify(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        value: ByteArray,
        confirm: Boolean,
    ): BlueLibResult<Unit> {
        if (closed) return failureOf(BlueLibError.Closed("GattServer"))
        val platformServer = server ?: return failureOf(BlueLibError.Closed("GattServer"))
        val device = devices[deviceId.address.value]
            ?: return failureOf(BlueLibError.OperationRejected("$deviceId is not connected to this server"))

        val definition = state.definitionOf(service, characteristic)
            ?: return failureOf(BlueLibError.CharacteristicNotFound(service, characteristic))

        if (!GattProperty.isSubscribable(definition.properties)) {
            return failureOf(BlueLibError.CharacteristicNotNotifiable(characteristic, definition.properties))
        }

        val mtu = mtuOf(deviceId)
        if (!PayloadSegmenter.fitsInSingleWrite(value, mtu)) {
            return failureOf(
                BlueLibError.OperationRejected(
                    reason = "a notification carries at most ${PayloadSegmenter.maxChunkSize(mtu)} bytes at MTU $mtu, got ${value.size}",
                    hint = "Notifications cannot be chunked: raise the MTU from the central or shorten the value.",
                ),
            )
        }

        val platformCharacteristic = platformServer.getService(service.uuid)
            ?.getCharacteristic(characteristic.uuid)
            ?: return failureOf(BlueLibError.CharacteristicNotFound(service, characteristic))

        // Keep the store in sync so a read after the notification returns the same bytes.
        state.recordNotifiedValue(service, characteristic, value)

        val notificationKey = "${deviceId.address.value}|$service|$characteristic"
        val started = if (Build.VERSION.SDK_INT >= 33) {
            // `BluetoothStatusCodes.SUCCESS` is 0; the constant is inlined at compile time.
            platformServer.notifyCharacteristicChanged(device, platformCharacteristic, confirm, value) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                platformCharacteristic.value = value
                platformServer.notifyCharacteristicChanged(device, platformCharacteristic, confirm)
            }
        }

        if (!started) {
            val error = BlueLibError.OperationRejected(
                reason = "notifyCharacteristicChanged was rejected for $characteristic",
                hint = "Check that a central is connected and subscribed, and that the value fits the MTU.",
            )
            diagnostics.error(error, deviceId)
            return failureOf(error)
        }

        if (!confirm) return successOf(Unit)

        // Only indications are confirmed: `onNotificationSent` reports them, and waiting gives the
        // caller a real acknowledgement instead of a fire-and-forget success.
        val deferred = CompletableDeferred<BlueLibResult<Unit>>()
        pendingNotifications[notificationKey] = deferred
        val completed = deferred.await()
        pendingNotifications.remove(notificationKey)
        return completed
    }

    override suspend fun sendResponse(
        deviceId: BluetoothDeviceId,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ): BlueLibResult<Unit> {
        if (closed) return failureOf(BlueLibError.Closed("GattServer"))
        val platformServer = server ?: return failureOf(BlueLibError.Closed("GattServer"))
        val device = devices[deviceId.address.value]
            ?: return failureOf(BlueLibError.OperationRejected("$deviceId is not connected to this server"))

        PayloadSegmenter.requireValidMtu(PayloadSegmenter.DEFAULT_MTU)
        val sent = runCatching { platformServer.sendResponse(device, requestId, status, offset, value) }
            .getOrDefault(false)

        return if (sent) {
            successOf(Unit)
        } else {
            failureOf(
                BlueLibError.OperationRejected(
                    reason = "sendResponse was rejected for request $requestId",
                    hint = "A request can only be answered once, and only before the 30 second ATT timeout.",
                ),
            )
        }
    }

    /**
     * Always fails: in the server role the *central* owns the MTU exchange.
     *
     * Neither `BluetoothGattServer` nor the public `BluetoothDevice` surface exposes a way to request an
     * MTU, so pretending otherwise would be a lie. BlueLib tracks the negotiated value through
     * `onMtuChanged` and exposes it in [connections]; the central calls `requestMtu` on its side.
     */
    override suspend fun requestMtu(deviceId: BluetoothDeviceId, mtu: Int): BlueLibResult<Int> {
        PayloadSegmenter.requireValidMtu(mtu)
        return failureOf(
            BlueLibError.FeatureUnsupported(
                feature = "server initated MTU negotiation",
                currentApiLevel = ApiLevel.current,
                requiredApiLevel = Int.MAX_VALUE,
                message = "The central owns the MTU exchange in the server role; BlueLib reports the " +
                    "negotiated value through onMtuChanged. Only a GATT client can call requestMtu.",
            ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        val platformServer = server
        server = null
        runCatching { platformServer?.clearServices() }
        runCatching { platformServer?.close() }
        pendingNotifications.values.forEach { it.cancel() }
        pendingNotifications.clear()
        devices.clear()
        state.clear()
        mutableConnections.value = emptyList()
    }

    // --- Platform callback ---------------------------------------------------------------------

    internal val callback: BluetoothGattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceId = BluetoothDeviceId.of(device.address)
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    devices[deviceId.address.value] = device
                    mutableConnections.value =
                        mutableConnections.value.filterNot { it.deviceId == deviceId } +
                        GattServerConnection(deviceId, PayloadSegmenter.DEFAULT_MTU)
                    diagnostics.operationStarted("gattServer.connect", deviceId)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    devices.remove(deviceId.address.value)
                    mutableConnections.value = mutableConnections.value.filterNot { it.deviceId == deviceId }
                    // Subscriptions and half finished long writes must not survive the connection.
                    state.forget(deviceId)
                    diagnostics.operationFinished(
                        operation = "gattServer.disconnect",
                        startedAtMillis = 0L,
                        success = status == BluetoothGatt.GATT_SUCCESS,
                        deviceId = deviceId,
                    )
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val deviceId = BluetoothDeviceId.of(device.address)
            mutableConnections.value = mutableConnections.value.map {
                if (it.deviceId == deviceId) it.copy(mtu = mtu) else it
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                diagnostics.error(
                    BlueLibError.GattOperationFailed("addService(${service.uuid})", GattMapping.statusOf(status)),
                )
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val serviceUuid = serviceUuidOf(characteristic)
            val characteristicUuid = BluetoothUuid.of(characteristic.uuid)
            val deviceId = BluetoothDeviceId.of(device.address)

            emitRequest(
                GattServerRequest.ReadCharacteristic(
                    deviceId = deviceId,
                    requestId = requestId,
                    offset = offset,
                    service = serviceUuid,
                    characteristic = characteristicUuid,
                ),
            ) {
                val read = state.read(serviceUuid, characteristicUuid, offset)
                read.status to read.value
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val serviceUuid = serviceUuidOf(characteristic)
            val characteristicUuid = BluetoothUuid.of(characteristic.uuid)
            val deviceId = BluetoothDeviceId.of(device.address)
            val payload = value?.copyOf() ?: ByteArray(0)

            emitRequest(
                GattServerRequest.WriteCharacteristic(
                    deviceId = deviceId,
                    requestId = requestId,
                    offset = offset,
                    service = serviceUuid,
                    characteristic = characteristicUuid,
                    value = payload,
                    responseNeeded = responseNeeded,
                    preparedWrite = preparedWrite,
                ),
                allowResponse = responseNeeded,
            ) {
                // A long write is only published once the central executes it, which is what the ATT
                // prepare/execute sequence promises.
                val status = if (preparedWrite) {
                    state.prepareWriteChunk(deviceId, serviceUuid, characteristicUuid, offset, payload)
                } else {
                    state.write(serviceUuid, characteristicUuid, offset, payload)
                }
                status to null
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val characteristic = descriptor.characteristic
            val serviceUuid = serviceUuidOf(characteristic)
            val characteristicUuid = BluetoothUuid.of(characteristic.uuid)
            val deviceId = BluetoothDeviceId.of(device.address)

            emitRequest(
                GattServerRequest.ReadDescriptor(
                    deviceId = deviceId,
                    requestId = requestId,
                    offset = offset,
                    service = serviceUuid,
                    characteristic = characteristicUuid,
                    descriptor = BluetoothUuid.of(descriptor.uuid),
                ),
            ) {
                if (descriptor.uuid == BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION.uuid) {
                    val read = state.readClientCharacteristicConfiguration(deviceId, serviceUuid, characteristicUuid)
                    read.status to read.value
                } else {
                    ATTRIBUTE_NOT_FOUND to null
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val characteristic = descriptor.characteristic
            val serviceUuid = serviceUuidOf(characteristic)
            val characteristicUuid = BluetoothUuid.of(characteristic.uuid)
            val deviceId = BluetoothDeviceId.of(device.address)
            val payload = value?.copyOf() ?: ByteArray(0)

            if (descriptor.uuid == BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION.uuid) {
                // BlueLib owns this descriptor: answering here means applications never parse the two
                // bytes, and `isSubscribed` stays the single source of truth for `notify`.
                state.writeClientCharacteristicConfiguration(deviceId, serviceUuid, characteristicUuid, payload)
                if (responseNeeded) {
                    respond(requestId, deviceId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                return
            }

            emitRequest(
                GattServerRequest.WriteDescriptor(
                    deviceId = deviceId,
                    requestId = requestId,
                    offset = offset,
                    service = serviceUuid,
                    characteristic = characteristicUuid,
                    descriptor = BluetoothUuid.of(descriptor.uuid),
                    value = payload,
                    responseNeeded = responseNeeded,
                    preparedWrite = preparedWrite,
                ),
                allowResponse = responseNeeded,
            ) {
                ATTRIBUTE_NOT_FOUND to null
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val deviceId = BluetoothDeviceId.of(device.address)

            emitRequest(
                GattServerRequest.ExecuteWrite(
                    deviceId = deviceId,
                    requestId = requestId,
                    execute = execute,
                ),
            ) { state.executePreparedWrites(deviceId, execute) to null }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val deviceId = BluetoothDeviceId.of(device.address)
            val entry = pendingNotifications.entries.firstOrNull {
                it.key.startsWith("${deviceId.address.value}|")
            }
            val deferred = entry?.value ?: return
            pendingNotifications.remove(entry.key)

            deferred.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    successOf(Unit)
                } else {
                    failureOf(
                        BlueLibError.GattOperationFailed(
                            "notifyCharacteristicChanged",
                            GattMapping.statusOf(status),
                            deviceId,
                        ),
                    )
                },
            )
        }
    }

    // --- Internals -----------------------------------------------------------------------------

    /**
     * Emits [request] to collectors and, when the server answers automatically, sends the response
     * [outcome] computes.
     *
     * @param allowResponse `false` for write commands and long write chunks, which the ATT protocol
     *   forbids answering — responding anyway makes the platform reject the call.
     */
    private fun emitRequest(
        request: GattServerRequest,
        allowResponse: Boolean = true,
        outcome: () -> Pair<Int, ByteArray?>,
    ) {
        mutableRequests.tryEmit(request)
        if (!config.autoRespond || !allowResponse) return

        val (status, value) = outcome()
        respond(request.requestId, request.deviceId, status, request.offset, value)
    }

    private fun respond(
        requestId: Int,
        deviceId: BluetoothDeviceId,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        val platformServer = server ?: return
        val device = devices[deviceId.address.value] ?: return
        val sent = runCatching { platformServer.sendResponse(device, requestId, status, offset, value) }
            .getOrDefault(false)
        if (!sent) {
            diagnostics.error(
                BlueLibError.OperationRejected("sendResponse was rejected for request $requestId"),
                deviceId,
            )
        }
    }

    private fun mtuOf(deviceId: BluetoothDeviceId): Int =
        mutableConnections.value.firstOrNull { it.deviceId == deviceId }?.mtu ?: PayloadSegmenter.DEFAULT_MTU

    private fun serviceUuidOf(characteristic: BluetoothGattCharacteristic): BluetoothUuid =
        runCatching { characteristic.service?.uuid }.getOrNull()
            ?.let { BluetoothUuid.of(it) }
            ?: BluetoothUuid.UNKNOWN

    private companion object {
        /** Requests buffered for a slow collector before the oldest is dropped. */
        const val REQUEST_BUFFER = 64

        /** `BluetoothGatt.GATT_ATTRIBUTE_NOT_FOUND`. */
        const val ATTRIBUTE_NOT_FOUND = 0x0A
    }
}

/** Registers a GATT server with the platform and owns its lifetime. */
// The gateway and `requireReady` verify `BLUETOOTH_CONNECT` before `openGattServer`; Lint cannot follow
// that runtime gate statically.
@SuppressLint("MissingPermission")
public class AndroidGattServerHost(
    private val context: android.content.Context,
    private val adapterSource: io.github.cybersafetyid.bluelib.android.adapter.AndroidAdapterSource,
    private val permissionGateway: io.github.cybersafetyid.bluelib.android.permission.PermissionGateway,
    private val diagnostics: AndroidDiagnostics,
) : io.github.cybersafetyid.bluelib.port.GattServerPort {

    @Volatile
    private var current: AndroidGattServer? = null

    override val isOpen: Boolean
        get() = current?.isReady == true

    override suspend fun open(config: GattServerConfig): BlueLibResult<GattServer> {
        current?.let { return successOf(it) }

        val permission = permissionGateway.requireOrFailure(
            io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation.GATT_SERVER,
        )
        permission.getOrNull() ?: return failureOf(permission.errorOrNull()!!)

        val adapter = adapterSource.requireReady(
            io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation.GATT_SERVER,
        )
        adapter.getOrNull() ?: return failureOf(adapter.errorOrNull()!!)

        if (!GattMapping.isServerSupported(context)) {
            return failureOf(
                BlueLibError.FeatureUnsupported(
                    feature = "GATT server",
                    currentApiLevel = ApiLevel.current,
                    requiredApiLevel = 21,
                    message = "This device does not expose a Bluetooth GATT server (BluetoothManager is unavailable).",
                ),
            )
        }

        // The string based lookup keeps Android 5.0 working (`getSystemService(Class)` is API 23).
        val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager
            ?: return failureOf(BlueLibError.AdapterUnavailable())

        val server = AndroidGattServer(config, diagnostics)

        val platformServer = runCatching { manager.openGattServer(context, server.callback) }
            .getOrElse { throwable ->
                val error = BlueLibError.Unexpected("openGattServer failed", throwable, operation = "openGattServer")
                diagnostics.error(error)
                return failureOf(error)
            }
            ?: return failureOf(
                BlueLibError.AdapterUnavailable("BluetoothManager.openGattServer returned null; Bluetooth may be off."),
            )

        config.services.forEach { service ->
            val platformService = GattMapping.platformServiceOf(service)
            if (!platformServer.addService(platformService)) {
                server.close()
                val error = BlueLibError.OperationRejected(
                    reason = "the platform refused service ${service.uuid}",
                    hint = "A GATT server accepts at most one instance of each service UUID.",
                )
                diagnostics.error(error)
                return failureOf(error)
            }
        }

        server.attach(platformServer)
        server.seedValues()
        current = server
        return successOf(server)
    }
}
