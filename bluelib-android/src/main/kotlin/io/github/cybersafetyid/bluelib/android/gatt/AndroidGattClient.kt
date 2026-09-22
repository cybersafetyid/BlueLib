package io.github.cybersafetyid.bluelib.android.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.content.Context
import androidx.annotation.RequiresApi
import io.github.cybersafetyid.bluelib.BlueLibConfig
import io.github.cybersafetyid.bluelib.android.adapter.AndroidAdapterSource
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.ConnectionPriority
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.port.ClockPort
import io.github.cybersafetyid.bluelib.port.GattClientPort
import io.github.cybersafetyid.bluelib.port.GattConnectRequest
import io.github.cybersafetyid.bluelib.port.GattSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the set of live GATT connections and the connect procedure.
 *
 * BlueLib allows **one session per device**, because Android's controller has a hard limit on
 * simultaneous LE connections and a second `connectGatt` on the same device creates a second
 * `BluetoothGatt` object competing for the same link (the classic source of status 133). Asking for
 * the same device twice therefore returns the live session, which is what applications almost always
 * want.
 *
 * Connect uses the newest platform entry point available:
 *
 * * Android 17 (API 37) adds `BluetoothGattConnectionSettings` (transport, auto connect, automatic
 *   MTU, opportunistic) — BlueLib uses it and keeps the legacy overloads for 26–36 and 21–25.
 * * `autoConnect` stays off unless requested, because the platform reports no failure while it is on.
 */
// `connect` verifies the adapter state and `BLUETOOTH_CONNECT` through `requireReady` before any
// platform call, which Android Lint cannot follow statically.
@SuppressLint("MissingPermission")
public class AndroidGattClient(
    private val context: Context,
    private val adapterSource: AndroidAdapterSource,
    private val diagnostics: AndroidDiagnostics,
    private val clock: ClockPort,
    private val sessionScope: CoroutineScope,
    private val config: BlueLibConfig,
    private val dispatcher: CoroutineDispatcher? = null,
) : GattClientPort {

    private val sessions = ConcurrentHashMap<String, AndroidGattSession>()

    override val openSessions: List<BluetoothDeviceId>
        get() = sessions.values.asSequence().filter { it.state.value != ConnectionState.CLOSED }.map { it.deviceId }.toList()

    /** Sessions still usable, for diagnostics. */
    public val activeSessionCount: Int
        get() = openSessions.size

    override suspend fun connect(
        deviceId: BluetoothDeviceId,
        request: GattConnectRequest,
    ): BlueLibResult<GattSession> {
        sessions[deviceId.address.value]?.let { existing ->
            if (existing.state.value != ConnectionState.CLOSED) return successOf(existing)
            sessions.remove(deviceId.address.value)
        }

        val adapter = adapterSource.requireReady(BluetoothOperation.CONNECT)
        val readyAdapter = adapter.getOrNull() ?: return failureOf(adapter.errorOrNull()!!)

        val device = runCatching { readyAdapter.getRemoteDevice(deviceId.address.value) }.getOrElse { throwable ->
            return failureOf(
                BlueLibError.OperationRejected(
                    reason = "the address ${deviceId.address.value} is not valid for this adapter",
                    hint = throwable.message.orEmpty(),
                ),
            )
        }

        val session = AndroidGattSession(
            deviceId = deviceId,
            request = request,
            diagnostics = diagnostics,
            clock = clock,
            sessionScope = sessionScope,
            dispatcher = dispatcher,
            retryPolicy = config.gattRetryPolicy,
            operationTimeoutMillis = config.operationTimeoutMillis,
        )

        val startedAt = clock.nowMillis()
        diagnostics.operationStarted("connectGatt", deviceId)

        val platformGatt = runCatching { openPlatformConnection(device, request, session.callback) }
            .getOrElse { throwable ->
                val error = BlueLibError.Unexpected("connectGatt was rejected", throwable, operation = "connectGatt")
                diagnostics.error(error, deviceId)
                return failureOf(error)
            }

        session.attach(platformGatt)

        val connected = try {
            withTimeout(request.timeoutMillis.milliseconds) { session.awaitConnected() }
        } catch (_: TimeoutCancellationException) {
            failureOf(BlueLibError.Timeout("connectGatt", request.timeoutMillis, deviceId))
        }

        if (connected.isFailure) {
            val error = connected.errorOrNull()!!
            diagnostics.operationFinished("connectGatt", startedAt, success = false, deviceId = deviceId)
            diagnostics.error(error, deviceId)
            session.close()
            return failureOf(error)
        }

        sessions[deviceId.address.value] = session

        request.mtu?.let { target ->
            session.negotiateMtu(target).onFailure { diagnostics.error(it, deviceId) }
        }
        if (request.connectionPriority != ConnectionPriority.BALANCED) {
            session.requestConnectionPriority(request.connectionPriority)
        }
        if (request.preferHighThroughputPhy || config.preferHighThroughputPhy) {
            session.requestPhy(Phy.LE_2M)
        }
        if (request.discoverServices) {
            session.awaitServices().onFailure { diagnostics.error(it, deviceId) }
        }

        diagnostics.operationFinished("connectGatt", startedAt, success = true, deviceId = deviceId)
        return successOf(session)
    }

    /** Disconnects and forgets every session; called from `BlueLib.close()`. */
    public fun closeAll() {
        sessions.values.toList().forEach { session ->
            runCatching { session.close() }
        }
        sessions.clear()
    }

    @Suppress("DEPRECATION")
    private suspend fun openPlatformConnection(
        device: BluetoothDevice,
        request: GattConnectRequest,
        callback: BluetoothGattCallback,
    ): BluetoothGatt {
        val transport = GattMapping.transportOf(request.transport)

        return when {
            ApiLevel.isAtLeast(37) -> withContext(dispatcherOrDirect()) {
                connectWithSettings(device, request, transport, callback)
                    ?: throw IllegalStateException("connectGatt(BluetoothGattConnectionSettings) returned null")
            }

            // The PHY aware overload exists from Android 8.0 and is the only one that accepts a PHY
            // mask; the transport aware overload is Android 6.0, and Android 5.0/5.1 only have the
            // three argument form, which picks the transport itself.
            ApiLevel.isAtLeast(26) -> withContext(dispatcherOrDirect()) {
                device.connectGatt(context, request.autoConnect, callback, transport, Phy.LE_1M.mask)
            }

            ApiLevel.isAtLeast(23) -> withContext(dispatcherOrDirect()) {
                device.connectGatt(context, request.autoConnect, callback, transport)
            }

            else -> withContext(dispatcherOrDirect()) {
                device.connectGatt(context, request.autoConnect, callback)
            }
        }
    }

    /** API 37 returns a nullable `BluetoothGatt`; the caller turns `null` into a typed error. */
    @RequiresApi(37)
    private fun connectWithSettings(
        device: BluetoothDevice,
        request: GattConnectRequest,
        transport: Int,
        callback: BluetoothGattCallback,
    ): BluetoothGatt? {
        val settings = BluetoothGattConnectionSettings.Builder()
            .setTransport(transport)
            .setAutoConnectEnabled(request.autoConnect)
            .setAutomaticMtuEnabled(request.mtu != null)
            .setOpportunisticEnabled(request.opportunistic)
            .build()
        return device.connectGatt(settings, dispatcherOrDirect().asExecutor(), callback)
    }

    private fun dispatcherOrDirect(): CoroutineDispatcher =
        dispatcher ?: kotlinx.coroutines.Dispatchers.Default
}
