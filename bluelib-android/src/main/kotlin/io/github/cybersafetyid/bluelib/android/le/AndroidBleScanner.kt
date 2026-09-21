package io.github.cybersafetyid.bluelib.android.le

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import io.github.cybersafetyid.bluelib.android.adapter.AndroidAdapterSource
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.ScanFailureReason
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.ScanLostReason
import io.github.cybersafetyid.bluelib.domain.model.ScanObservation
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.policy.ScanQuotaGovernor
import io.github.cybersafetyid.bluelib.port.BleScanPort
import io.github.cybersafetyid.bluelib.port.ClockPort
import io.github.cybersafetyid.bluelib.port.DiagnosticEvent
import io.github.cybersafetyid.bluelib.port.ScanEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LE scanner built on `BluetoothLeScanner`.
 *
 * Behaviour that matters in production:
 *
 * * **Guard order**: permissions → adapter → capability → quota. A missing permission fails before
 *   the platform is touched, so applications never have to catch `SecurityException`.
 * * **Scan quota**: Android silently blocks an app that starts more than five scans in 30 seconds, so
 *   every start goes through [ScanQuotaGovernor] first.
 * * **Auto stop**: every scan has a deadline, because "never scan in a loop" is the most effective
 *   battery rule there is.
 * * **Presence tracking**: a device that goes quiet is reported as [ScanEvent.Lost] after
 *   [ScanRequest.DEFAULT_LOST_AFTER_MILLIS], which is what most UIs actually want instead of an
 *   ever growing device list.
 * * **Software filters**: name prefixes (and anything else Android cannot express) are applied in
 *   software rather than being silently ignored.
 * * **Single scan**: a second `scan()` while one is running fails with
 *   [BlueLibError.ScanAlreadyActive] instead of silently replacing the first callback.
 */
// `scan` verifies the adapter state and `BLUETOOTH_SCAN` through `requireReady` before calling the
// platform scanner, and reports failures as `ScanEvent.Failed`; Lint cannot follow that gate.
@SuppressLint("MissingPermission")
public class AndroidBleScanner(
    private val adapterSource: AndroidAdapterSource,
    private val diagnostics: AndroidDiagnostics,
    private val clock: ClockPort,
    private val quota: ScanQuotaGovernor,
    private val dispatcher: CoroutineDispatcher? = null,
) : BleScanPort {

    private val scanning = AtomicBoolean(false)

    override val isScanning: Boolean
        get() = scanning.get()

    /** One live scan: the platform scanner, its callback and the presence bookkeeping. */
    private class ActiveScan(
        val scanner: BluetoothLeScanner,
        val callback: ScanCallback,
        val request: ScanRequest,
        val seen: MutableMap<String, Pair<ScanObservation, Long>> = mutableMapOf(),
    )

    override fun scan(request: ScanRequest): Flow<ScanEvent> {
        val flow = callbackFlow {
            val startedAt = clock.nowMillis()
            diagnostics.operationStarted(OPERATION)

            val opened = open(request, scope = this)
            val active = opened.getOrNull()
            if (active == null) {
                trySend(ScanEvent.Failed(opened.errorOrNull() ?: unknownFailure()))
                awaitClose { scanning.set(false) }
                return@callbackFlow
            }

            val ticker = launch { presenceLoop(active, scope = this@callbackFlow) }
            val stopper = request.autoStopAfterMillis?.let { deadline ->
                launch {
                    delay(deadline)
                    close()
                }
            }

            awaitClose {
                ticker.cancel()
                stopper?.cancel()
                close(active, startedAt)
            }
        }

        return dispatcher?.let { flow.flowOn(it) } ?: flow
    }

    /** Runs every pre-flight check and starts the platform scan. */
    private fun open(request: ScanRequest, scope: ProducerScope<ScanEvent>): BlueLibResult<ActiveScan> {
        val operation = if (request.allowBackgroundScan) {
            BluetoothOperation.BACKGROUND_SCAN
        } else {
            BluetoothOperation.SCAN
        }

        val adapter = adapterSource.requireReady(operation)
        val readyAdapter = adapter.getOrNull()
            ?: return failureOf(adapter.errorOrNull() ?: BlueLibError.AdapterUnavailable())

        if (!scanning.compareAndSet(false, true)) {
            return failureOf(BlueLibError.ScanAlreadyActive)
        }

        val scanner = readyAdapter.bluetoothLeScanner
        if (scanner == null) {
            scanning.set(false)
            return failureOf(
                BlueLibError.FeatureUnsupported(
                    feature = "LOW_ENERGY",
                    currentApiLevel = adapterSource.apiLevel,
                    requiredApiLevel = 18,
                    message = "This device does not expose a BLE scanner.",
                ),
            )
        }

        when (val decision = quota.acquire()) {
            is ScanQuotaGovernor.Decision.Throttled -> {
                scanning.set(false)
                diagnostics.emit(
                    DiagnosticEvent.ScanThrottled(
                        retryAfterMillis = decision.retryAfterMillis,
                        timestampMillis = clock.nowMillis(),
                    ),
                )
                return failureOf(BlueLibError.ScanThrottled(decision.retryAfterMillis))
            }

            ScanQuotaGovernor.Decision.Allowed -> Unit
        }

        val seen = mutableMapOf<String, Pair<ScanObservation, Long>>()
        val callback = callbackFor(request, seen, scope)
        val filters = ScanMapping.filtersFor(request)
        val settings = ScanMapping.settingsFor(request)

        val started = runCatching { scanner.startScan(filters, settings, callback) }
        val throwable = started.exceptionOrNull()
        if (throwable != null) {
            scanning.set(false)
            diagnostics.error(BlueLibError.Unexpected("startScan was rejected", throwable))
            return failureOf(
                BlueLibError.Unexpected("startScan was rejected by the platform", throwable, operation = OPERATION),
            )
        }

        return successOf(ActiveScan(scanner, callback, request, seen))
    }

    private fun callbackFor(
        request: ScanRequest,
        seen: MutableMap<String, Pair<ScanObservation, Long>>,
        scope: ProducerScope<ScanEvent>,
    ): ScanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            deliver(request, seen, result, scope)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { deliver(request, seen, it, scope) }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = ScanMapping.failureReason(errorCode)
            if (reason == ScanFailureReason.SCANNING_TOO_FREQUENTLY) {
                quota.recordPlatformThrottle()
            }
            val error = BlueLibError.ScanFailed(reason, errorCode)
            diagnostics.error(error)
            scope.trySend(ScanEvent.Failed(error))
            scope.close()
        }
    }

    private fun deliver(
        request: ScanRequest,
        seen: MutableMap<String, Pair<ScanObservation, Long>>,
        result: ScanResult,
        scope: ProducerScope<ScanEvent>,
    ) {
        val observation = runCatching { ScanMapping.observationOf(result, clock.nowMillis()) }
            .getOrElse { throwable ->
                diagnostics.error(BlueLibError.Unexpected("Parsing a scan result failed", throwable))
                return
            }

        if (!ScanMapping.matchesSoftwareFilters(request, observation)) return

        val key = observation.deviceId.address.value
        val previous = seen[key]
        seen[key] = observation to clock.nowMillis()

        // Repeat reports with an unchanged RSSI add noise without information; only report changes.
        if (previous == null || previous.first.rssi != observation.rssi) {
            scope.trySend(ScanEvent.Observed(observation))
        }
    }

    private suspend fun presenceLoop(active: ActiveScan, scope: ProducerScope<ScanEvent>) {
        while (CoroutineScope(scope.coroutineContext).isActive) {
            delay(PRESENCE_TICK_MILLIS)
            val now = clock.nowMillis()
            val stale = active.seen.filterValues { (_, lastSeen) ->
                now - lastSeen >= ScanRequest.DEFAULT_LOST_AFTER_MILLIS
            }
            stale.forEach { (key, entry) ->
                active.seen.remove(key)
                scope.trySend(ScanEvent.Lost(entry.first, ScanLostReason.NOT_SEEN_FOR_A_WHILE))
            }
        }
    }

    private fun close(active: ActiveScan, startedAt: Long) {
        runCatching { active.scanner.stopScan(active.callback) }
        scanning.set(false)
        diagnostics.operationFinished(OPERATION, startedAtMillis = startedAt, success = true)
    }

    /** Releases the scanner state when the owning `BlueLib` instance is closed. */
    public fun release() {
        scanning.set(false)
    }

    private fun unknownFailure(): BlueLibError = BlueLibError.Unexpected(
        message = "The scan could not be started",
        cause = IllegalStateException("no adapter"),
        operation = OPERATION,
    )

    public companion object {
        /** How often the presence tracker looks for devices that went quiet. */
        internal const val PRESENCE_TICK_MILLIS: Long = 1_000L

        private const val OPERATION = "scan"
    }
}
