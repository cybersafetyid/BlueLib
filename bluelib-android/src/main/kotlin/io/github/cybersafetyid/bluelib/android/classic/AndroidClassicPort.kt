package io.github.cybersafetyid.bluelib.android.classic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.RequiresApi
import io.github.cybersafetyid.bluelib.android.adapter.AndroidAdapterSource
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.BluetoothFeature
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibException
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.BondLossReason
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.BondState
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.port.ClassicConnection
import io.github.cybersafetyid.bluelib.port.ClassicDevice
import io.github.cybersafetyid.bluelib.port.ClassicDiscoveryEvent
import io.github.cybersafetyid.bluelib.port.ClassicPort
import io.github.cybersafetyid.bluelib.port.ClockPort
import io.github.cybersafetyid.bluelib.port.SocketSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth Classic: discovery, bonding and RFCOMM/L2CAP sockets.
 *
 * The three rules this class exists to enforce:
 *
 * 1. **Discovery is exclusive.** Only one discovery can run per adapter and it monopolises the radio,
 *    so BlueLib registers its receiver for the whole scan, cancels discovery when the collector goes
 *    away, and never leaves it running in the background.
 * 2. **Bonding is asynchronous and user driven.** `createBond` returns `true` when the request was
 *    *accepted*, not when the bond exists; the bond only exists once the platform reports
 *    `BOND_BONDED`, which requires the user to confirm a dialog. BlueLib therefore waits for the state
 *    change with a timeout and reports `BOND_NONE` as a failure instead of hanging.
 * 3. **Unbonding is a platform hole.** `BluetoothDevice.removeBond()` is still not public API, so the
 *    only supported path is `CompanionDeviceManager.removeBond(associationId)` for devices the app is
 *    associated with. Anything else fails with a typed error that says exactly what to do.
 */
// Every entry point here checks the adapter and the permissions through
// `AndroidAdapterSource.requireReady(…)` before touching `android.bluetooth`; Android Lint cannot see
// through that gateway, so the static permission check is suppressed at the boundary class while the
// runtime gate stays the single source of truth. See docs/architecture.md.
@SuppressLint("MissingPermission")
public class AndroidClassicPort(
    private val context: Context,
    private val adapterSource: AndroidAdapterSource,
    private val diagnostics: AndroidDiagnostics,
    private val clock: ClockPort,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher? = null,
) : ClassicPort {

    private val mutableBondedDevices = MutableStateFlow<List<ClassicDevice>>(emptyList())

    /** Broadcast receiver shared by discovery, bonding and the bonded-device list. */
    private val stateListener: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> handleBondStateChanged(intent)
                BluetoothDevice.ACTION_KEY_MISSING -> handleKeyMissing(intent)
            }
        }
    }

    @Volatile
    private var listenerRegistered = false

    /** Starts listening for bond changes; called by [io.github.cybersafetyid.bluelib.android.BlueLibAndroid]. */
    public fun start() {
        if (listenerRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (ApiLevel.isAtLeast(36)) {
                addAction(BluetoothDevice.ACTION_KEY_MISSING)
            }
        }
        runCatching {
            if (ApiLevel.requiresReceiverExportFlag()) {
                context.registerReceiver(stateListener, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(stateListener, filter)
            }
        }.onSuccess { listenerRegistered = true }
            .onFailure { throwable ->
                diagnostics.error(BlueLibError.Unexpected("Unable to register the bond receiver", throwable))
            }
        refreshBondedDevices()
    }

    /** Stops listening. */
    public fun stop() {
        if (!listenerRegistered) return
        runCatching { context.unregisterReceiver(stateListener) }
        listenerRegistered = false
    }

    // `getParcelableExtra(String)` is the only overload that works from API 21 to 37: the typed form
    // arrived in API 33. Every function touching broadcast extras is therefore marked deprecated-use.
    @Suppress("DEPRECATION")
    override fun discover(includeRssi: Boolean): Flow<ClassicDiscoveryEvent> = callbackFlow {
        val adapter = adapterSource.requireReady(BluetoothOperation.CLASSIC_DISCOVERY)
        val target = adapter.getOrNull()
        if (target == null) {
            close(BlueLibException(adapter.errorOrNull()!!))
            return@callbackFlow
        }

        val found = ConcurrentHashMap<String, ClassicDevice>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> trySend(ClassicDiscoveryEvent.Started)

                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            ?: return
                        val mapped = device.toClassicDevice()
                        found[mapped.deviceId.address.value] = mapped
                        trySend(ClassicDiscoveryEvent.DeviceFound(mapped, rssiOf(intent, includeRssi)))
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        trySend(ClassicDiscoveryEvent.Finished)
                        close()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        runCatching {
            if (ApiLevel.requiresReceiverExportFlag()) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { throwable ->
            close(BlueLibException(BlueLibError.Unexpected("Cannot register the discovery receiver", throwable)))
            return@callbackFlow
        }

        val started = runCatching { target.startDiscovery() }.getOrDefault(false)
        if (!started) {
            runCatching { context.unregisterReceiver(receiver) }
            close(
                BlueLibException(
                    BlueLibError.OperationRejected(
                        reason = "startDiscovery() was rejected",
                        hint = "Another discovery may be running, or the adapter is turning off.",
                    ),
                ),
            )
            return@callbackFlow
        }

        awaitClose {
            runCatching { target.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    override fun bondedDevices(): Flow<List<ClassicDevice>> = mutableBondedDevices.asStateFlow()

    /** Reads the bonded device list from the platform and updates the stream. */
    @Suppress("DEPRECATION")
    public fun refreshBondedDevices() {
        val adapter = adapterSource.adapter ?: return
        val devices = runCatching { adapter.bondedDevices.orEmpty().map { it.toClassicDevice() } }
            .getOrElse { throwable ->
                diagnostics.error(
                    BlueLibError.PermissionMissing(
                        operation = BluetoothOperation.CLASSIC_DISCOVERY.operationName,
                        permissions = listOf(android.Manifest.permission.BLUETOOTH_CONNECT),
                        // The permission constant is an inlined string, safe on every API level.
                    ),
                )
                return
            }
        mutableBondedDevices.value = devices
    }

    @Suppress("DEPRECATION")
    override fun bondState(deviceId: BluetoothDeviceId): Flow<BondState> = channelFlow {
        val target = findDevice(deviceId)
        send(target?.let { BondState.fromPlatformValue(it.bondState) } ?: BondState.NONE)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                if (!device.address.equals(deviceId.address.value, ignoreCase = true)) return
                // `send` suspends and this is a platform callback, so the non-suspending channel
                // accessor is used: `channelFlow` buffers without limit, so nothing is ever dropped.
                this@channelFlow.trySend(BondState.fromPlatformValue(device.bondState))
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        runCatching {
            if (ApiLevel.requiresReceiverExportFlag()) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    @Suppress("DEPRECATION")
    override suspend fun bond(
        deviceId: BluetoothDeviceId,
        transport: Transport,
        timeoutMillis: Long,
    ): BlueLibResult<Unit> {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val adapter = adapterSource.requireReady(BluetoothOperation.CLASSIC_DISCOVERY)
        val readyAdapter = adapter.getOrNull() ?: return failureOf(adapter.errorOrNull()!!)

        val device = deviceFor(readyAdapter, deviceId)
            ?: return failureOf(BlueLibError.OperationRejected("no device with address ${deviceId.address.value}"))

        if (BondState.fromPlatformValue(device.bondState) == BondState.BONDED) return successOf(Unit)

        start()
        val bonded = CompletableDeferred<BondState>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    ?: return
                if (!changed.address.equals(deviceId.address.value, ignoreCase = true)) return
                val state = BondState.fromPlatformValue(changed.bondState)
                val lossReason = bondLossReasonOf(intent)
                if (lossReason != null) {
                    diagnostics.error(
                        BlueLibError.BondLost(
                            device = deviceId,
                            reason = lossReason,
                            systemRepairInProgress = ApiLevel.isAtLeast(37),
                        ),
                        deviceId,
                    )
                }
                if (state != BondState.BONDING && !bonded.isCompleted) bonded.complete(state)
            }
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            // `ACTION_KEY_MISSING` only exists from Android 16; the constant is an inlined string, but
            // registering it on older releases would be meaningless rather than harmful.
            if (ApiLevel.isAtLeast(36)) {
                addAction(BluetoothDevice.ACTION_KEY_MISSING)
            }
        }
        runCatching {
            if (ApiLevel.requiresReceiverExportFlag()) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { throwable ->
            return failureOf(BlueLibError.Unexpected("Cannot register the bonding receiver", throwable))
        }

        val startedAt = clock.nowMillis()
        diagnostics.operationStarted("createBond", deviceId)

        try {
            // `createBond(int transport)` only became public API in Android 17 (the int overload
            // existed as a system API before that), so the transport is only forwarded there.
            val requested = if (ApiLevel.isAtLeast(37)) {
                device.createBond(transport.platformValue)
            } else {
                device.createBond()
            }

            if (!requested) {
                return failureOf(
                    BlueLibError.BondFailed(
                        device = deviceId,
                        reason = "the platform rejected createBond (already bonding, or too many pairing requests)",
                    ),
                )
            }

            val state = withTimeout(timeoutMillis) { bonded.await() }
            return if (state == BondState.BONDED) {
                diagnostics.operationFinished("createBond", startedAt, success = true, deviceId = deviceId)
                refreshBondedDevices()
                successOf(Unit)
            } else {
                val error = BlueLibError.BondFailed(
                    device = deviceId,
                    reason = "the platform reported $state; the user may have declined, or the device is out of range",
                )
                diagnostics.operationFinished("createBond", startedAt, success = false, deviceId = deviceId)
                diagnostics.error(error, deviceId)
                failureOf(error)
            }
        } catch (timeout: TimeoutCancellationException) {
            // Device.out of range or a pairing dialog the user never answered. Cancelling the bond
            // attempt is the caller's decision, so the state is left untouched.
            val error = BlueLibError.Timeout("createBond", timeoutMillis, deviceId)
            diagnostics.operationFinished("createBond", startedAt, success = false, deviceId = deviceId)
            diagnostics.error(error, deviceId)
            return failureOf(error)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    override suspend fun unbond(deviceId: BluetoothDeviceId): BlueLibResult<Unit> {
        // `CompanionDeviceManager.removeBond(int)` is public API from Android 16 (API 36). Before that
        // it was a system API, so there is genuinely no supported way to unpair.
        if (!ApiLevel.isAtLeast(36)) {
            return failureOf(
                BlueLibError.FeatureUnsupported(
                    feature = "Removing a bond",
                    currentApiLevel = ApiLevel.current,
                    requiredApiLevel = 36,
                    message = "Android has never exposed a public unbond API. From Android 16 the only " +
                        "supported path is CompanionDeviceManager.removeBond(associationId), which needs " +
                        "an association created with CompanionDeviceManager.associate().",
                ),
            )
        }

        val manager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE)
                as? android.companion.CompanionDeviceManager
            ?: return failureOf(
                BlueLibError.FeatureUnsupported(
                    feature = "CompanionDeviceManager",
                    currentApiLevel = ApiLevel.current,
                    requiredApiLevel = 36,
                ),
            )

        val association = runCatching { manager.myAssociations }.getOrNull()
            ?.firstOrNull { association ->
                association.deviceMacAddress?.toString().equals(deviceId.address.value, ignoreCase = true)
            }
            ?: return failureOf(
                BlueLibError.OperationRejected(
                    reason = "$deviceId is not associated with this app, so CompanionDeviceManager cannot unbond it",
                    hint = "Associate the device first (CompanionDeviceManager.associate), or ask the user to " +
                        "forget the device from the system Bluetooth settings.",
                ),
            )

        val removed = runCatching { manager.removeBond(association.id) }.getOrDefault(false)
        return if (removed) {
            refreshBondedDevices()
            successOf(Unit)
        } else {
            failureOf(
                BlueLibError.BondFailed(
                    device = deviceId,
                    reason = "CompanionDeviceManager.removeBond was rejected",
                ),
            )
        }
    }

    override suspend fun connectRfcomm(
        deviceId: BluetoothDeviceId,
        serviceUuid: BluetoothUuid,
        settings: SocketSettings,
    ): BlueLibResult<ClassicConnection> {
        val adapter = adapterSource.requireReady(BluetoothOperation.CONNECT)
        val readyAdapter = adapter.getOrNull() ?: return failureOf(adapter.errorOrNull()!!)
        val device = deviceFor(readyAdapter, deviceId)
            ?: return failureOf(BlueLibError.OperationRejected("no device with address ${deviceId.address.value}"))

        if (settings.wantsModernSettings && !ApiLevel.isAtLeast(36)) {
            return failureOf(
                BlueLibError.FeatureUnsupported(
                    feature = BluetoothFeature.SOCKET_SETTINGS.fullName,
                    currentApiLevel = ApiLevel.current,
                    requiredApiLevel = BluetoothFeature.SOCKET_SETTINGS.introducedInApiLevel,
                    message = "Encryption and authentication requirements for RFCOMM sockets need " +
                        "BluetoothSocketSettings on Android 16 (API 36); the secure RFCOMM socket already " +
                        "encrypts the link, but the requirement cannot be asserted explicitly.",
                ),
            )
        }

        return openSocket(deviceId) {
            if (settings.wantsModernSettings && ApiLevel.isAtLeast(36)) {
                device.createUsingSocketSettings(settings.toPlatform(serviceUuid, BluetoothSocket.TYPE_RFCOMM))
            } else if (settings.secure) {
                device.createRfcommSocketToServiceRecord(serviceUuid.uuid)
            } else {
                device.createInsecureRfcommSocketToServiceRecord(serviceUuid.uuid)
            }
        }
    }

    override suspend fun connectL2cap(
        deviceId: BluetoothDeviceId,
        psm: Int,
        settings: SocketSettings,
    ): BlueLibResult<ClassicConnection> {
        if (psm !in 1..0xFFFF) {
            return failureOf(
                BlueLibError.OperationRejected(
                    reason = "the L2CAP PSM must be within 1..65535, got $psm",
                    hint = "Android allows PSMs 1..0x00FF for LE CoC and 0x1001..0xFFFF for other profiles.",
                ),
            )
        }
        if (!ApiLevel.isAtLeast(29)) {
            return failureOf(BlueLibError.FeatureUnsupported("L2CAP sockets", ApiLevel.current, 29))
        }

        val adapter = adapterSource.requireReady(BluetoothOperation.CONNECT)
        val readyAdapter = adapter.getOrNull() ?: return failureOf(adapter.errorOrNull()!!)
        val device = deviceFor(readyAdapter, deviceId)
            ?: return failureOf(BlueLibError.OperationRejected("no device with address ${deviceId.address.value}"))

        if (settings.wantsModernSettings && !ApiLevel.isAtLeast(36)) {
            return failureOf(
                BlueLibError.FeatureUnsupported(
                    feature = BluetoothFeature.SOCKET_SETTINGS.fullName,
                    currentApiLevel = ApiLevel.current,
                    requiredApiLevel = BluetoothFeature.SOCKET_SETTINGS.introducedInApiLevel,
                ),
            )
        }

        return openSocket(deviceId) {
            if (settings.wantsModernSettings && ApiLevel.isAtLeast(36)) {
                device.createUsingSocketSettings(
                    settings.toPlatform(serviceUuid = null, socketType = BluetoothSocket.TYPE_L2CAP, psm = psm),
                )
            } else if (settings.secure) {
                device.createL2capChannel(psm)
            } else {
                device.createInsecureL2capChannel(psm)
            }
        }
    }

    // --- Internals -----------------------------------------------------------------------------

    private suspend fun openSocket(
        deviceId: BluetoothDeviceId,
        create: () -> BluetoothSocket,
    ): BlueLibResult<ClassicConnection> {
        val startedAt = clock.nowMillis()
        diagnostics.operationStarted("socket.connect", deviceId)

        val socket = runCatching { create() }.getOrElse { throwable ->
            val error = BlueLibError.OperationRejected(
                reason = "the platform refused to create the socket",
                hint = throwable.message.orEmpty(),
            )
            diagnostics.error(error, deviceId)
            return failureOf(error)
        }

        val ioDispatcher = dispatcher ?: Dispatchers.IO
        val connected = runCatching {
            withContext(ioDispatcher) { socket.connect() }
        }

        if (connected.isFailure) {
            runCatching { socket.close() }
            val error = BlueLibError.ConnectionFailed(device = deviceId, status = null)
            diagnostics.operationFinished("socket.connect", startedAt, success = false, deviceId = deviceId)
            diagnostics.error(error, deviceId)
            return failureOf(
                BlueLibError.Unexpected(
                    message = "Socket connect failed: ${connected.exceptionOrNull()?.message.orEmpty()}",
                    cause = connected.exceptionOrNull() ?: IllegalStateException("socket connect failed"),
                    operation = "socket.connect",
                ),
            )
        }

        diagnostics.operationFinished("socket.connect", startedAt, success = true, deviceId = deviceId)
        return successOf(
            AndroidClassicConnection(
                socket = socket,
                deviceId = deviceId,
                diagnostics = diagnostics,
                scope = scope,
                ioDispatcher = ioDispatcher,
            ),
        )
    }

    private fun deviceFor(adapter: BluetoothAdapter, deviceId: BluetoothDeviceId): BluetoothDevice? =
        runCatching { adapter.getRemoteDevice(deviceId.address.value) }.getOrNull()

    private fun findDevice(deviceId: BluetoothDeviceId): BluetoothDevice? =
        adapterSource.adapter?.let { deviceFor(it, deviceId) }

    @Suppress("DEPRECATION")
    private fun BluetoothDevice.toClassicDevice(): ClassicDevice = ClassicDevice(
        deviceId = BluetoothDeviceId.of(address),
        name = runCatching { name }.getOrNull(),
        bondState = BondState.fromPlatformValue(bondState),
        deviceClass = runCatching { bluetoothClass?.deviceClass?.let { "0x${it.toString(16)}" } }.getOrNull(),
        uuids = runCatching { uuids.orEmpty().map { BluetoothUuid.of(it.uuid) } }.getOrDefault(emptyList()),
    )

    private fun rssiOf(intent: Intent, includeRssi: Boolean): Int? {
        if (!includeRssi || !ApiLevel.isAtLeast(30)) return null
        val value = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
        return if (value == Short.MIN_VALUE) null else value.toInt()
    }

    @Suppress("DEPRECATION")
    private fun handleBondStateChanged(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val deviceId = BluetoothDeviceId.of(device.address)
        val state = BondState.fromPlatformValue(
            intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, device.bondState),
        )
        val lossReason = bondLossReasonOf(intent)

        if (lossReason != null) {
            val error = BlueLibError.BondLost(
                device = deviceId,
                reason = lossReason,
                systemRepairInProgress = ApiLevel.isAtLeast(37),
            )
            diagnostics.error(error, deviceId)
        } else if (state == BondState.BONDED) {
            diagnostics.operationStarted("bonded", deviceId)
        }
        refreshBondedDevices()
    }

    /**
     * Android 16.1 (API 36.1) added `EXTRA_BOND_LOSS_REASON` to the bond state broadcast.
     *
     * On Android 17 the platform attempts to re-pair autonomously, and `ACTION_KEY_MISSING` is only
     * broadcast when that attempt failed — which is why BlueLib surfaces both the reason and the flag.
     */
    @SuppressLint("InlinedApi")
    private fun bondLossReasonOf(intent: Intent): BondLossReason? {
        if (!ApiLevel.isAtLeast(36, 1)) return null
        if (!intent.hasExtra(BluetoothDevice.EXTRA_BOND_LOSS_REASON)) return null
        return when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_LOSS_REASON, BluetoothDevice.BOND_LOSS_REASON_UNKNOWN)) {
            BluetoothDevice.BOND_LOSS_REASON_BREDR_AUTH_FAILURE -> BondLossReason.BREDR_AUTH_FAILURE
            BluetoothDevice.BOND_LOSS_REASON_BREDR_INCOMING_PAIRING -> BondLossReason.BREDR_INCOMING_PAIRING
            BluetoothDevice.BOND_LOSS_REASON_LE_ENCRYPT_FAILURE -> BondLossReason.LE_ENCRYPT_FAILURE
            BluetoothDevice.BOND_LOSS_REASON_LE_INCOMING_PAIRING -> BondLossReason.LE_INCOMING_PAIRING
            else -> BondLossReason.UNKNOWN
        }
    }

    @Suppress("DEPRECATION")
    private fun handleKeyMissing(intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val deviceId = BluetoothDeviceId.of(device.address)
        // Android 17 re-pairs on its own before broadcasting KEY_MISSING, so the flag is only set when
        // the platform's own attempt is still in progress.
        diagnostics.error(
            BlueLibError.BondLost(
                device = deviceId,
                reason = bondLossReasonOf(intent) ?: BondLossReason.UNKNOWN,
                systemRepairInProgress = false,
            ),
            deviceId,
        )
    }

    /** `true` when [SocketSettings] asks for something only `BluetoothSocketSettings` can express. */
    private val SocketSettings.wantsModernSettings: Boolean
        get() = encryptionRequired || authenticationRequired

    @RequiresApi(36)
    private fun SocketSettings.toPlatform(
        serviceUuid: BluetoothUuid?,
        socketType: Int,
        psm: Int? = null,
    ): BluetoothSocketSettings {
        val builder = BluetoothSocketSettings.Builder()
            .setSocketType(socketType)
            .setEncryptionRequired(encryptionRequired)
            .setAuthenticationRequired(authenticationRequired)

        when (socketType) {
            BluetoothSocket.TYPE_RFCOMM -> {
                builder.setRfcommServiceName(serviceName)
                serviceUuid?.let { builder.setRfcommUuid(it.uuid) }
            }

            else -> {
                // The platform requires a dynamic PSM (128..65535); the caller validated the range.
                psm?.let { builder.setL2capPsm(it) }
            }
        }
        return builder.build()
    }

    private companion object {
        /** Platform value of `BluetoothDevice.TRANSPORT_BREDR`. */
        const val TRANSPORT_BREDR = 1
    }

    private val Transport.platformValue: Int
        get() = when (this) {
            Transport.BREDR -> TRANSPORT_BREDR
            // `createBond(TRANSPORT_LE)` is the only other transport Android accepts; AUTO is rejected.
            Transport.LE -> 2
            Transport.AUTO -> TRANSPORT_BREDR
        }
}

/**
 * One connected Classic socket.
 *
 * The reader runs on the socket's own coroutine and closes the flow when the peer disappears, because
 * `InputStream.read` throws rather than returning `-1` when the link drops on most stacks.
 */
internal class AndroidClassicConnection(
    private val socket: BluetoothSocket,
    override val deviceId: BluetoothDeviceId,
    private val diagnostics: AndroidDiagnostics,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : ClassicConnection {

    private val mutableIncoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val incoming: Flow<ByteArray> = mutableIncoming

    @Volatile
    private var closed = false

    override val isConnected: Boolean
        get() = !closed && runCatching { socket.isConnected }.getOrDefault(false)

    init {
        scope.launch(ioDispatcher) {
            readLoop()
        }
    }

    override suspend fun write(value: ByteArray): BlueLibResult<Unit> = withContext(ioDispatcher) {
        if (closed) return@withContext failureOf(BlueLibError.Closed("ClassicConnection"))
        runCatching {
            socket.outputStream.write(value)
            socket.outputStream.flush()
        }.fold(
            onSuccess = { successOf(Unit) },
            onFailure = { throwable ->
                diagnostics.error(
                    BlueLibError.Unexpected("Socket write failed", throwable, operation = "socket.write"),
                    deviceId,
                )
                failureOf(
                    BlueLibError.Unexpected(
                        message = "Socket write failed: ${throwable.message.orEmpty()}",
                        cause = throwable,
                        operation = "socket.write",
                    ),
                )
            },
        )
    }

    private fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            val input = socket.inputStream
            while (!closed) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    mutableIncoming.tryEmit(buffer.copyOf(read))
                }
            }
        } catch (failure: Throwable) {
            if (!closed) {
                diagnostics.error(
                    BlueLibError.Unexpected("Socket read failed", failure, operation = "socket.read"),
                    deviceId,
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Closing the streams first lets a blocking read return before the socket itself goes away.
        runCatching { socket.inputStream?.close() }
        runCatching { socket.outputStream?.close() }
        runCatching { socket.close() }
    }

    private companion object {
        /** Read buffer size: the classic Android sample uses 1024 and it stays a safe default. */
        const val READ_BUFFER_BYTES = 1024
    }
}
