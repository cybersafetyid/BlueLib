package io.github.cybersafetyid.bluelib.android.gatt

import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.policy.RetryDecision
import io.github.cybersafetyid.bluelib.domain.policy.RetryPolicy
import io.github.cybersafetyid.bluelib.port.ClockPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Runs GATT operations one at a time per connection.
 *
 * Android does not queue GATT requests: issuing a read while a write is still in flight silently
 * drops the second operation, which is the root cause of a large share of "my BLE library is
 * unreliable" bug reports. This queue:
 *
 * * serialises with a `Mutex` (so the second caller waits instead of vanishing),
 * * applies a deadline to every operation via `withTimeout`,
 * * retries only failures the taxonomy marks retryable, with exponential backoff and jitter,
 * * records timings in the diagnostics stream,
 * * lets `CancellationException` through untouched, so a cancelled coroutine never leaves the GATT
 *   database in an unknown state.
 */
internal class GattOperationQueue(
    private val clock: ClockPort,
    private val diagnostics: AndroidDiagnostics,
    private val operationTimeoutMillis: Long,
    private val retryPolicy: RetryPolicy,
    private val deviceId: BluetoothDeviceId?,
) {
    private val mutex = Mutex()

    /** Name of the operation currently holding the queue, for diagnostics and error messages. */
    @Volatile
    private var inFlight: String? = null

    /** Operation currently running, or `null`. */
    val currentOperation: String?
        get() = inFlight

    /** `true` when an operation is running right now. */
    val isBusy: Boolean
        get() = inFlight != null

    suspend fun <T> run(
        operation: String,
        timeoutMillis: Long = operationTimeoutMillis,
        block: suspend () -> BlueLibResult<T>,
    ): BlueLibResult<T> = mutex.withLock {
        val startedAt = clock.nowMillis()
        inFlight = operation
        diagnostics.operationStarted(operation, deviceId)
        try {
            var attempt = 1
            while (true) {
                val result = execute(operation, timeoutMillis, block)
                val error = result.errorOrNull()
                if (error == null) {
                    diagnostics.operationFinished(operation, startedAt, success = true, deviceId = deviceId)
                    return@withLock result
                }

                when (val decision = retryPolicy.afterFailure(attempt, error)) {
                    is RetryDecision.GiveUp -> {
                        diagnostics.error(error, deviceId)
                        diagnostics.operationFinished(operation, startedAt, success = false, deviceId = deviceId)
                        return@withLock result
                    }

                    is RetryDecision.Retry -> {
                        attempt = decision.attempt
                        delay(decision.delayMillis)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") failureOf(BlueLibError.Cancelled(operation))
        } finally {
            inFlight = null
        }
    }

    private suspend fun <T> execute(
        operation: String,
        timeoutMillis: Long,
        block: suspend () -> BlueLibResult<T>,
    ): BlueLibResult<T> = try {
        withTimeout(timeoutMillis) { block() }
    } catch (timeout: TimeoutCancellationException) {
        failureOf(BlueLibError.Timeout(operation, timeoutMillis, deviceId))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        failureOf(BlueLibError.Unexpected(operation, throwable))
    }
}
