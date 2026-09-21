package io.github.cybersafetyid.bluelib.android.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import androidx.annotation.RequiresApi
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibException
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.ConnectionPriority
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import io.github.cybersafetyid.bluelib.domain.model.GattCharacteristicInfo
import io.github.cybersafetyid.bluelib.domain.model.GattProfile
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.PhyCoding
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import io.github.cybersafetyid.bluelib.domain.policy.MtuNegotiationPolicy
import io.github.cybersafetyid.bluelib.domain.policy.RetryPolicy
import io.github.cybersafetyid.bluelib.domain.state.ConnectionEvent
import io.github.cybersafetyid.bluelib.domain.state.ConnectionStateMachine
import io.github.cybersafetyid.bluelib.domain.validation.PayloadSegmenter
import io.github.cybersafetyid.bluelib.port.ClockPort
import io.github.cybersafetyid.bluelib.port.GattConnectRequest
import io.github.cybersafetyid.bluelib.port.GattSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * One GATT connection.
 *
 * The class exists to absorb Android's quirks, all of which are documented in
 * `docs/research/gatt-pitfalls.md`:
 *
 * * **Failures are reported as `status` on the callback**, not as exceptions, and `status != 0` on a
 *   disconnect is the only signal that a connect attempt *failed* rather than dropped.
 * * **`close()` must always be called**: leaking a `BluetoothGatt` leaks a controller connection slot
 *   and eventually makes every later connect fail with status 133.
 * * **The service cache goes stale.** Android caches the GATT database per device; a peripheral that
 *   changed its services triggers `onServiceChanged` (Android 12+) which BlueLib turns into a
 *   rediscovery, and `GATT_DATABASE_OUT_OF_SYNC` (0x12) is retried as a rediscovery too.
 * * **Notifications must be re-armed after a reconnect**, otherwise the peripheral keeps sending and
 *   nobody listens; BlueLib remembers every subscription and restores it.
 * * **Values are only valid inside their callback** before Android 13, hence the byte-array copies.
 */
// Every operation runs after `AndroidGattClient` verified the adapter state and `BLUETOOTH_CONNECT`, and
// every failure comes back as a typed error; Android Lint cannot follow that runtime gate statically.
@SuppressLint("MissingPermission")
public class AndroidGattSession internal constructor(
    override val deviceId: BluetoothDeviceId,
    private val request: GattConnectRequest,
    private val context: android.content.Context,
    private val diagnostics: AndroidDiagnostics,
    private val clock: ClockPort,
    private val sessionScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher?,
    retryPolicy: RetryPolicy,
    private val operationTimeoutMillis: Long,
) : GattSession {

    private val machine = ConnectionStateMachine()
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val mutableProfile = MutableStateFlow<GattProfile?>(null)
    private val mutableMtu = MutableStateFlow(PayloadSegmenter.DEFAULT_MTU)

    private val queue = GattOperationQueue(
        clock = clock,
        diagnostics = diagnostics,
        operationTimeoutMillis = operationTimeoutMillis,
        retryPolicy = retryPolicy,
        deviceId = deviceId,
    )

    private val connected = CompletableDeferred<Unit>()
    private var discovery: CompletableDeferred<BlueLibResult<GattProfile>>? = null
    private var pendingDiscovery: CompletableDeferred<BlueLibResult<GattProfile>>? = null
    private var pendingRead: CompletableDeferred<BlueLibResult<ByteArray>>? = null
    private var pendingWrite: CompletableDeferred<BlueLibResult<Unit>>? = null
    private var pendingDescriptorRead: CompletableDeferred<BlueLibResult<ByteArray>>? = null
    private var pendingDescriptorWrite: CompletableDeferred<BlueLibResult<Unit>>? = null
    private var pendingMtu: CompletableDeferred<BlueLibResult<Int>>? = null
    private var pendingPriority: CompletableDeferred<BlueLibResult<Unit>>? = null
    private var pendingPhyWrite: CompletableDeferred<BlueLibResult<Phy>>? = null

    /** Last PHY BlueLib requested; returned instead of the unreliable `readPhy()` result. */
    private var lastKnownPhy: Phy = Phy.LE_1M

    private val notifications = ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()
    private val subscriptions = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var closed = false

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    override val profile: StateFlow<GattProfile?> = mutableProfile.asStateFlow()

    override val mtu: StateFlow<Int> = mutableMtu.asStateFlow()

    /** Name of the GATT operation currently in flight, or `null`. */
    public val currentOperation: String?
        get() = queue.currentOperation

    /** Attaches the platform object returned by `connectGatt`. */
    internal fun attach(platformGatt: BluetoothGatt) {
        gatt = platformGatt
    }

    /** Suspends until the connection is usable, or fails with the connect error. */
    internal suspend fun awaitConnected(): BlueLibResult<Unit> = runCatching { connected.await() }
        .fold(
            onSuccess = { successOf(Unit) },
            onFailure = { throwable ->
                failureOf(
                    (throwable as? BlueLibException)?.error
                        ?: (throwable as? BlueLibError)
                        ?: BlueLibError.ConnectionFailed(device = deviceId, status = null),
                )
            },
        )

    /** Callback handed to `connectGatt`; exposed because the platform needs it at connect time. */
    internal val callback: BluetoothGattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(platformGatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val mapped = GattMapping.statusOf(status)
                diagnostics.error(BlueLibError.ConnectionFailed(deviceId, mapped), deviceId)
                if (!connected.isCompleted) {
                    connected.completeExceptionally(BlueLibException(BlueLibError.ConnectionFailed(device = deviceId, status = mapped)))
                }
                transition(ConnectionEvent.DISCONNECTED, mapped, null)
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    transition(ConnectionEvent.CONNECTED, null, null)
                    // Kick off discovery *before* releasing connect(): the connect procedure waits for
                    // the profile right after, and only an already-pending discovery is safe to await.
                    if (request.discoverServices) {
                        pendingDiscovery = CompletableDeferred()
                        requestDiscovery(platformGatt)
                    }
                    restoreSubscriptions()
                    if (!connected.isCompleted) connected.complete(Unit)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val connectionLost = BlueLibError.ConnectionLost(device = deviceId, status = null, bondLossReason = null)
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(BlueLibException(connectionLost))
                    }
                    transition(ConnectionEvent.DISCONNECTED, null, connectionLost)
                }
            }
        }

        override fun onServicesDiscovered(platformGatt: BluetoothGatt, status: Int) {
            val deferred = pendingDiscovery ?: discovery
            pendingDiscovery = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val error = BlueLibError.GattOperationFailed("discoverServices", GattMapping.statusOf(status), deviceId)
                diagnostics.error(error, deviceId)
                transition(ConnectionEvent.DISCONNECTED, null, null)
                deferred?.complete(failureOf(error))
                return
            }
            val profileSnapshot = GattMapping.profileOf(platformGatt.services)
            mutableProfile.value = profileSnapshot
            transition(ConnectionEvent.SERVICES_READY, null, null)
            deferred?.complete(successOf(profileSnapshot))
        }

        @RequiresApi(31)
        override fun onServiceChanged(platformGatt: BluetoothGatt) {
            // The peripheral changed its GATT database: drop the cached profile and rediscover.
            mutableProfile.value = null
            transition(ConnectionEvent.SERVICE_CHANGED, null, null)
            requestDiscovery(platformGatt)
        }

        override fun onMtuChanged(platformGatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingMtu?.complete(
                    failureOf(BlueLibError.GattOperationFailed("requestMtu", GattMapping.statusOf(status), deviceId)),
                )
                return
            }
            mutableMtu.value = mtu
            pendingMtu?.complete(successOf(mtu))
        }

        @Deprecated("Superseded by the byte array overload on Android 13+.")
        override fun onCharacteristicRead(
            platformGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            completeRead(status, value)
        }

        override fun onCharacteristicRead(
            platformGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(status, value)
        }

        @Deprecated("Superseded by the byte array overload on Android 13+.")
        override fun onCharacteristicWrite(
            platformGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            completeWrite(status)
        }

        @Deprecated("Superseded by the byte array overload on Android 13+.")
        override fun onCharacteristicChanged(
            platformGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            deliverNotification(characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            platformGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            deliverNotification(characteristic, value)
        }

        @Deprecated("Superseded by the byte array overload on Android 13+.")
        override fun onDescriptorRead(
            platformGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            val value = descriptor.value ?: ByteArray(0)
            completeDescriptorRead(status, value)
        }

        override fun onDescriptorRead(
            platformGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray,
        ) {
            completeDescriptorRead(status, value)
        }

        @Deprecated("Superseded by the byte array overload on Android 13+.")
        override fun onDescriptorWrite(
            platformGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            completeDescriptorWrite(status)
        }

        @RequiresApi(26)
        override fun onPhyUpdate(platformGatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingPhyWrite?.complete(
                    failureOf(
                        BlueLibError.PhyUpdateFailed(deviceId, Phy.fromMask(txPhy).firstOrNull() ?: Phy.LE_1M, GattMapping.statusOf(status)),
                    ),
                )
                return
            }
            pendingPhyWrite?.complete(successOf(Phy.fromMask(txPhy).firstOrNull() ?: Phy.LE_1M))
        }
    }

    // --- Client-side helpers ----------------------------------------------------------------

    /** Requests [target] MTU and validates what the peripheral actually granted. */
    internal suspend fun negotiateMtu(target: Int): BlueLibResult<Int> {
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))
        val plan = MtuNegotiationPolicy.plan(target)
        val requested = queue.run("requestMtu") {
            val deferred = CompletableDeferred<BlueLibResult<Int>>()
            pendingMtu = deferred
            val started = launchOnPlatform { platformGatt.requestMtu(plan) }
            if (!started) return@run failureOf(BlueLibError.OperationRejected("requestMtu was rejected"))
            deferred.await()
        }

        val negotiated = requested.getOrNull() ?: return failureOf(requested.errorOrNull()!!)
        return when (val outcome = MtuNegotiationPolicy.evaluate(plan, negotiated)) {
            is MtuNegotiationPolicy.NegotiationOutcome.Negotiated -> {
                mutableMtu.value = outcome.result.negotiated
                successOf(outcome.result.negotiated)
            }

            is MtuNegotiationPolicy.NegotiationOutcome.Failed -> failureOf(outcome.error)
        }
    }

    /**
     * Waits for the service discovery triggered while connecting.
     *
     * The connect procedure waits on this, so it must never issue a second `discoverServices`:
     * Android ignores a discovery request that arrives while one is in flight, and the caller would
     * then hang until its own timeout. It therefore awaits [pendingDiscovery] — created by the very
     * callback that released [awaitConnected] — and only falls back to issuing its own when nothing
     * is pending (a connection reused without the discovery flag).
     */
    internal suspend fun awaitServices(): BlueLibResult<GattProfile> {
        mutableProfile.value?.let { return successOf(it) }
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))

        pendingDiscovery?.let { inFlight -> return inFlight.await() }

        return queue.run("discoverServices") {
            val deferred = CompletableDeferred<BlueLibResult<GattProfile>>()
            pendingDiscovery = deferred
            discovery = deferred
            launchOnPlatform { platformGatt.discoverServices() }
            deferred.await()
        }
    }

    // --- Public operations -------------------------------------------------------------------

    override suspend fun discoverServices(): BlueLibResult<GattProfile> {
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))
        return queue.run("discoverServices") {
            val deferred = CompletableDeferred<BlueLibResult<GattProfile>>()
            discovery = deferred
            prepareForDiscovery()
            launchOnPlatform { platformGatt.discoverServices() }
            deferred.await()
        }
    }

    override suspend fun read(service: BluetoothUuid, characteristic: BluetoothUuid): BlueLibResult<ByteArray> {
        val resolved = resolvePlatformCharacteristic(service, characteristic)
            ?: return failureOf(serviceOrCharacteristicError(service, characteristic))
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))

        return queue.run("readCharacteristic") {
            val deferred = CompletableDeferred<BlueLibResult<ByteArray>>()
            pendingRead = deferred
            val started = launchOnPlatform { readCharacteristic(platformGatt, resolved) }
            if (!started) return@run failureOf(BlueLibError.OperationRejected("readCharacteristic was rejected"))
            deferred.await()
        }
    }

    override suspend fun write(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        value: ByteArray,
        mode: WriteMode,
    ): BlueLibResult<Unit> {
        val resolved = resolvePlatformCharacteristic(service, characteristic)
            ?: return failureOf(serviceOrCharacteristicError(service, characteristic))
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))

        val writeType = when (mode) {
            WriteMode.WITH_RESPONSE, WriteMode.LONG -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            WriteMode.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        return queue.run("writeCharacteristic") {
            if (mode == WriteMode.LONG) {
                writeLong(platformGatt, resolved, value)
            } else {
                PayloadSegmenter.validateWrite(value, mode, mutableMtu.value)
                val deferred = CompletableDeferred<BlueLibResult<Unit>>()
                pendingWrite = deferred
                val started = launchOnPlatform { writeCharacteristic(platformGatt, resolved, value, writeType) }
                if (!started) return@run failureOf(BlueLibError.OperationRejected("writeCharacteristic was rejected"))
                deferred.await()
            }
        }
    }

    override suspend fun readDescriptor(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
    ): BlueLibResult<ByteArray> {
        val resolved = resolveDescriptor(service, characteristic, descriptor)
            ?: return failureOf(BlueLibError.OperationRejected("Descriptor $descriptor was not found"))
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))

        return queue.run("readDescriptor") {
            val deferred = CompletableDeferred<BlueLibResult<ByteArray>>()
            pendingDescriptorRead = deferred
            val started = launchOnPlatform { platformGatt.readDescriptor(resolved) }
            if (!started) return@run failureOf(BlueLibError.OperationRejected("readDescriptor was rejected"))
            deferred.await()
        }
    }

    override suspend fun writeDescriptor(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
        value: ByteArray,
    ): BlueLibResult<Unit> {
        val resolved = resolveDescriptor(service, characteristic, descriptor)
            ?: return failureOf(BlueLibError.OperationRejected("Descriptor $descriptor was not found"))
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))

        return queue.run("writeDescriptor") {
            val deferred = CompletableDeferred<BlueLibResult<Unit>>()
            pendingDescriptorWrite = deferred
            val started = launchOnPlatform { writeDescriptor(platformGatt, resolved, value) }
            if (!started) return@run failureOf(BlueLibError.OperationRejected("writeDescriptor was rejected"))
            deferred.await()
        }
    }

    override fun subscribe(service: BluetoothUuid, characteristic: BluetoothUuid): Flow<ByteArray> {
        val info = resolve(service, characteristic)
        val channelKey = key(service, characteristic)

        if (info == null || !info.isSubscribable) {
            val error = if (info == null) {
                serviceOrCharacteristicError(service, characteristic)
            } else {
                BlueLibError.CharacteristicNotNotifiable(characteristic, info.properties)
            }
            diagnostics.error(error, deviceId)
            return kotlinx.coroutines.flow.flow { throw io.github.cybersafetyid.bluelib.domain.error.BlueLibException(error) }
        }

        val flow = notifications.getOrPut(channelKey) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = NOTIFICATION_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        }

        sessionScope.launch {
            // Writing the CCCD descriptor arms notifications; remember it for reconnect.
            val cccd = info.clientCharacteristicConfiguration
            if (cccd != null) {
                val value = if (io.github.cybersafetyid.bluelib.domain.model.GattProperty.isNotifiable(info.properties)) {
                    NOTIFY_VALUE
                } else {
                    INDICATE_VALUE
                }
                subscriptions[channelKey] = value
                writeDescriptor(service, characteristic, BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION, value)
            }
        }

        return flow.asSharedFlow()
    }

    override suspend fun requestConnectionPriority(priority: ConnectionPriority): BlueLibResult<Unit> {
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))
        if (priority == ConnectionPriority.DCK && !ApiLevel.isAtLeast(34)) {
            return failureOf(
                BlueLibError.FeatureUnsupported("CONNECTION_PRIORITY_DCK", ApiLevel.current, 34),
            )
        }
        return queue.run("requestConnectionPriority") {
            val deferred = CompletableDeferred<BlueLibResult<Unit>>()
            pendingPriority = deferred
            val started = launchOnPlatform { platformGatt.requestConnectionPriority(GattMapping.priorityOf(priority)) }
            if (!started) {
                return@run failureOf(BlueLibError.OperationRejected("requestConnectionPriority was rejected"))
            }
            // Android reports the priority change through onConnectionStateChange on some stacks and
            // not at all on others, so resolve immediately instead of waiting forever.
            pendingPriority?.complete(successOf(Unit))
            successOf(Unit)
        }
    }

    override suspend fun requestPhy(phy: Phy, coding: PhyCoding?): BlueLibResult<Phy> {
        if (!ApiLevel.isAtLeast(26)) {
            return failureOf(BlueLibError.FeatureUnsupported("LE_2M_PHY", ApiLevel.current, 26))
        }
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))
        return requestPhyApi26(platformGatt, phy, coding)
    }

    @RequiresApi(26)
    private suspend fun requestPhyApi26(platformGatt: BluetoothGatt, phy: Phy, coding: PhyCoding?): BlueLibResult<Phy> =
        queue.run("setPreferredPhy") {
            val deferred = CompletableDeferred<BlueLibResult<Phy>>()
            pendingPhyWrite = deferred
            launchOnPlatform {
                platformGatt.setPreferredPhy(phy.mask, phy.mask, GattMapping.phyOptionOf(coding))
                true
            }
            deferred.await().onSuccess { granted -> lastKnownPhy = granted }
        }

    override suspend fun readPhy(): BlueLibResult<List<Phy>> {
        if (!ApiLevel.isAtLeast(26)) {
            return failureOf(BlueLibError.FeatureUnsupported("LE_2M_PHY", ApiLevel.current, 26))
        }
        val platformGatt = gatt ?: return failureOf(BlueLibError.Closed("GattSession"))
        return queue.run("readPhy") {
            launchOnPlatform {
                platformGatt.readPhy()
                true
            }
            // `onPhyRead` is unreliable on several OEM stacks; report the last known PHY instead.
            successOf(listOf(lastKnownPhy))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        machine.onEvent(ConnectionEvent.CLOSED)
        mutableState.value = ConnectionState.CLOSED
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        notifications.clear()
        subscriptions.clear()
        connected.cancel()
    }

    // --- Internals ---------------------------------------------------------------------------

    private fun transition(event: ConnectionEvent, status: io.github.cybersafetyid.bluelib.domain.error.GattStatus?, error: BlueLibError?) {
        val previous = machine.state
        val next = runCatching { machine.onEvent(event) }.getOrElse { previous }
        mutableState.value = next
        if (next != previous) {
            diagnostics.connectionStateChanged(deviceId, previous, next)
        }
        if (error != null && !closed) {
            diagnostics.error(error, deviceId)
        }
        if (status != null && !status.isSuccess && !closed) {
            diagnostics.error(BlueLibError.GattOperationFailed("onConnectionStateChange", status, deviceId), deviceId)
        }
    }

    private fun prepareForDiscovery() {
        discovery?.cancel()
        pendingDiscovery = null
    }

    private fun requestDiscovery(platformGatt: BluetoothGatt) {
        transition(ConnectionEvent.SERVICES_DISCOVERING, null, null)
        launchOnPlatformAsync { platformGatt.discoverServices() }
    }

    private fun restoreSubscriptions() {
        if (subscriptions.isEmpty()) return
        val platformGatt = gatt ?: return
        val entries = subscriptions.entries.toList()
        launchOnPlatformAsync {
            entries.forEach { (channelKey, value) ->
                val parts = channelKey.split('|')
                if (parts.size != 2) return@forEach
                val serviceUuid = BluetoothUuid.parseOrNull(parts[0]) ?: return@forEach
                val characteristicUuid = BluetoothUuid.parseOrNull(parts[1]) ?: return@forEach
                val descriptor = gatt?.let { current ->
                    resolveDescriptorOn(current, serviceUuid, characteristicUuid, BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION)
                } ?: return@forEach
                runCatching { writeDescriptor(platformGatt, descriptor, value) }
            }
        }
    }

    private fun completeRead(status: Int, value: ByteArray) {
        val deferred = pendingRead ?: return
        pendingRead = null
        deferred.complete(
            if (status == BluetoothGatt.GATT_SUCCESS) {
                successOf(value.copyOf())
            } else {
                failureOf(BlueLibError.GattOperationFailed("readCharacteristic", GattMapping.statusOf(status), deviceId))
            },
        )
    }

    private fun completeWrite(status: Int) {
        val deferred = pendingWrite ?: return
        pendingWrite = null
        deferred.complete(
            if (status == BluetoothGatt.GATT_SUCCESS) {
                successOf(Unit)
            } else {
                failureOf(BlueLibError.GattOperationFailed("writeCharacteristic", GattMapping.statusOf(status), deviceId))
            },
        )
    }

    private fun completeDescriptorRead(status: Int, value: ByteArray) {
        val deferred = pendingDescriptorRead ?: return
        pendingDescriptorRead = null
        deferred.complete(
            if (status == BluetoothGatt.GATT_SUCCESS) {
                successOf(value.copyOf())
            } else {
                failureOf(BlueLibError.GattOperationFailed("readDescriptor", GattMapping.statusOf(status), deviceId))
            },
        )
    }

    private fun completeDescriptorWrite(status: Int) {
        val deferred = pendingDescriptorWrite ?: return
        pendingDescriptorWrite = null
        deferred.complete(
            if (status == BluetoothGatt.GATT_SUCCESS) {
                successOf(Unit)
            } else {
                failureOf(BlueLibError.GattOperationFailed("writeDescriptor", GattMapping.statusOf(status), deviceId))
            },
        )
    }

    private fun deliverNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val key = characteristicKey(characteristic)
        val channel = notifications[key] ?: return
        channel.tryEmit(value.copyOf())
    }

    private fun characteristicKey(characteristic: BluetoothGattCharacteristic): String {
        val serviceUuid = runCatching { characteristic.service?.uuid }.getOrNull() ?: return ""
        return "${BluetoothUuid.of(serviceUuid)}|${BluetoothUuid.of(characteristic.uuid)}"
    }

    private fun key(service: BluetoothUuid, characteristic: BluetoothUuid): String = "$service|$characteristic"

    private fun resolvePlatformCharacteristic(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
    ): BluetoothGattCharacteristic? {
        val platformGatt = gatt ?: return null
        val platformService = platformGatt.getService(service.uuid) ?: return null
        return platformService.getCharacteristic(characteristic.uuid)
    }

    private fun resolve(service: BluetoothUuid, characteristic: BluetoothUuid): GattCharacteristicInfo? =
        resolvePlatformCharacteristic(service, characteristic)?.let { GattMapping.characteristicOf(it) }

    private fun resolveDescriptor(
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
    ): BluetoothGattDescriptor? {
        val platformGatt = gatt ?: return null
        val platformService = platformGatt.getService(service.uuid) ?: return null
        val platformCharacteristic = platformService.getCharacteristic(characteristic.uuid) ?: return null
        return platformCharacteristic.getDescriptor(descriptor.uuid)
    }

    private fun serviceOrCharacteristicError(service: BluetoothUuid, characteristic: BluetoothUuid): BlueLibError {
        val known = mutableProfile.value
        return if (known?.service(service) == null) {
            BlueLibError.ServiceNotFound(service, known?.serviceUuids.orEmpty())
        } else {
            BlueLibError.CharacteristicNotFound(service, characteristic)
        }
    }

    /**
     * `readCharacteristic` kept its `boolean` return type in every API level, unlike the writes, so no
     * API branch is needed for the return value — only for the value carriers of the callbacks.
     */
    private fun readCharacteristic(platformGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean =
        platformGatt.readCharacteristic(characteristic)

    private fun writeCharacteristic(
        platformGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = if (ApiLevel.isAtLeast(33)) {
        platformGatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = writeType
            characteristic.value = value
            platformGatt.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptor(platformGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (ApiLevel.isAtLeast(33)) {
            platformGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                platformGatt.writeDescriptor(descriptor)
            }
        }

    private suspend fun writeLong(
        platformGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): BlueLibResult<Unit> {
        PayloadSegmenter.validateWrite(value, WriteMode.LONG, mutableMtu.value)
        val chunks = PayloadSegmenter.segment(value, mutableMtu.value, WriteMode.LONG)
        chunks.forEach { chunk ->
            val result = writeSingle(platformGatt, characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (result.isFailure) return result
        }
        return successOf(Unit)
    }

    private suspend fun writeSingle(
        platformGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int,
    ): BlueLibResult<Unit> {
        val deferred = CompletableDeferred<BlueLibResult<Unit>>()
        pendingWrite = deferred
        val started = launchOnPlatform { writeCharacteristic(platformGatt, characteristic, chunk, writeType) }
        if (!started) return failureOf(BlueLibError.OperationRejected("writeCharacteristic was rejected"))
        return deferred.await()
    }

    /** Runs a platform call on the BlueLib dispatcher, returning `false` when it could not be started. */
    private suspend fun launchOnPlatform(block: () -> Boolean): Boolean =
        if (dispatcher != null) withContext(dispatcher) { block() } else block()

    /**
     * Fire-and-forget variant for work that originates inside a platform callback rather than a
     * caller coroutine: discovery and re-subscription. These must not block the binder thread, and
     * there is nobody to return a value to, so failures are reported through the diagnostics sink.
     */
    private fun launchOnPlatformAsync(block: () -> Unit) {
        val scope = if (dispatcher != null) sessionScope + dispatcher else sessionScope
        scope.launch {
            runCatching { block() }.onFailure { throwable ->
                diagnostics.error(BlueLibError.Unexpected("platform call failed", throwable), deviceId)
            }
        }
    }

    /** Resolves a descriptor for a specific (possibly stale) platform object, used when re-arming. */
    private fun resolveDescriptorOn(
        platformGatt: BluetoothGatt,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        descriptor: BluetoothUuid,
    ): BluetoothGattDescriptor? {
        val platformService = runCatching { platformGatt.getService(service.uuid) }.getOrNull() ?: return null
        val platformCharacteristic = platformService.getCharacteristic(characteristic.uuid) ?: return null
        return platformCharacteristic.getDescriptor(descriptor.uuid)
    }

    private companion object {
        /** Notifications buffered per characteristic before old values are dropped. */
        const val NOTIFICATION_BUFFER = 32

        /** CCCD value that enables notifications. */
        val NOTIFY_VALUE = byteArrayOf(0x01, 0x00)

        /** CCCD value that enables indications. */
        val INDICATE_VALUE = byteArrayOf(0x02, 0x00)
    }
}
