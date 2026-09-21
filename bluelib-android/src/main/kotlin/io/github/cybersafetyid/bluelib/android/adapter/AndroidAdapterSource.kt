package io.github.cybersafetyid.bluelib.android.adapter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.compat.PlatformCapabilities
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.android.permission.PermissionGateway
import io.github.cybersafetyid.bluelib.domain.BluetoothFeature
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.state.AdapterState
import io.github.cybersafetyid.bluelib.port.AdapterAvailabilityPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the `BluetoothAdapter` handle and the adapter state stream.
 *
 * Interesting details this class encodes:
 *
 * * `getSystemService(BluetoothManager::class.java)` needs API 23, so the string based lookup is used
 *   to keep Android 5.0 working.
 * * Reading `isEnabled` requires `BLUETOOTH_CONNECT` from Android 12, and OEM stacks throw even when
 *   the permission is present; both cases are mapped to a typed error instead of crashing.
 * * `ACTION_STATE_CHANGED` must be registered as a **not exported** receiver on Android 13+ (the
 *   platform throws otherwise), and BlueLib never calls the deprecated `enable()`/`disable()`.
 */
// Every adapter read is wrapped so a missing `BLUETOOTH_CONNECT` becomes a typed error instead of a
// `SecurityException`, which is why the static permission check is suppressed at this boundary.
@SuppressLint("MissingPermission")
public class AndroidAdapterSource(
    private val context: Context,
    private val diagnostics: AndroidDiagnostics,
    private val permissions: PermissionGateway,
) : AdapterAvailabilityPort {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    /** The platform adapter, or `null` on devices without Bluetooth. */
    public val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val capabilities = PlatformCapabilities(adapter, context.packageManager)

    private val mutableAdapterState = MutableStateFlow(currentStateOrUnknown())

    override val adapterState: StateFlow<String> = mutableAdapterState.asStateFlow()

    override val isAdapterAvailable: Boolean
        get() = adapter != null

    override val apiLevel: Int
        get() = ApiLevel.current

    override val minorApiLevel: Int
        get() = ApiLevel.currentMinor

    override fun isFeatureSupported(feature: BluetoothFeature): Boolean = capabilities.capabilityOf(feature)

    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val previous = mutableAdapterState.value
            val mapped = mapPlatformState(newState)
            mutableAdapterState.value = mapped.name
            diagnostics.adapterStateChanged(previous = previous, current = mapped.name)
        }
    }

    /** Starts listening for adapter state changes. Safe to call more than once. */
    public fun start() {
        if (receiverRegistered || adapter == null) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        runCatching {
            if (ApiLevel.requiresReceiverExportFlag()) {
                context.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(stateReceiver, filter)
            }
        }.onFailure { throwable ->
            diagnostics.error(
                BlueLibError.Unexpected("Unable to register the adapter state receiver", throwable),
            )
        }.onSuccess { receiverRegistered = true }
    }

    /** Stops listening. Safe to call more than once. */
    public fun stop() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(stateReceiver) }
        receiverRegistered = false
    }

    /** Current [AdapterState], refreshed from the platform. */
    public fun currentState(): AdapterState = mapPlatformState(
        runCatching { adapter?.state ?: BluetoothAdapter.ERROR }.getOrDefault(BluetoothAdapter.ERROR),
    )

    /** Refreshes the cached state and returns it. */
    public fun refresh(): AdapterState {
        val state = currentState()
        mutableAdapterState.value = state.name
        return state
    }

    /**
     * Verifies the adapter exists, is on and that [operation] is permitted.
     *
     * Returns a [BlueLibError] instead of throwing, so callers can translate it into a `Flow`
     * failure or a UI message.
     */
    public fun requireReady(operation: BluetoothOperation): BlueLibResult<BluetoothAdapter> {
        val target = adapter ?: return failureOf(BlueLibError.AdapterUnavailable())

        permissions.requireOrFailure(operation).let { permissionResult ->
            if (permissionResult.isFailure) return failureOf(permissionResult.errorOrNull()!!)
        }

        val enabled = runCatching { target.isEnabled }
            .getOrElse { throwable ->
                // On Android 12+ reading isEnabled without BLUETOOTH_CONNECT throws SecurityException.
                return failureOf(
                    BlueLibError.PermissionMissing(
                        operation = operation.operationName,
                        permissions = listOf("android.permission.BLUETOOTH_CONNECT"),
                    ),
                )
            }
        if (!enabled) return failureOf(BlueLibError.BluetoothDisabled)

        return successOf(target)
    }

    /** Requests the user to enable Bluetooth through the system dialog. */
    public fun enableRequestIntent(): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun currentStateOrUnknown(): String = mapPlatformState(
        runCatching { adapter?.state ?: BluetoothAdapter.ERROR }.getOrDefault(BluetoothAdapter.ERROR),
    ).name

    private fun mapPlatformState(platformState: Int): AdapterState = when (platformState) {
        BluetoothAdapter.STATE_OFF -> AdapterState.OFF
        BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
        BluetoothAdapter.STATE_ON -> AdapterState.ON
        BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
        else -> AdapterState.UNKNOWN
    }
}
