package io.github.cybersafetyid.bluelib.android.diagnostics

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import io.github.cybersafetyid.bluelib.port.ClockPort
import io.github.cybersafetyid.bluelib.port.DiagnosticEvent
import io.github.cybersafetyid.bluelib.port.DiagnosticsPort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Diagnostics bus.
 *
 * The buffer is bounded and drops the oldest events when a slow collector falls behind: losing an
 * old trace line is acceptable, blocking a Bluetooth callback on an analytics consumer is not.
 */
public class AndroidDiagnostics(
    private val clock: ClockPort,
    private val enabled: Boolean = true,
    replay: Int = 0,
) : DiagnosticsPort {

    private val mutableEvents = MutableSharedFlow<DiagnosticEvent>(
        replay = replay,
        extraBufferCapacity = DEFAULT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<DiagnosticEvent> = mutableEvents.asSharedFlow()

    /** Same as [events], typed as a [SharedFlow] for callers that need `replayCache`. */
    public val sharedEvents: SharedFlow<DiagnosticEvent>
        get() = mutableEvents.asSharedFlow()

    override fun emit(event: DiagnosticEvent) {
        if (!enabled) return
        // tryEmit never suspends, which matters because emit() is called from platform callbacks.
        mutableEvents.tryEmit(event)
    }

    /** Convenience: adapter state transition. */
    public fun adapterStateChanged(previous: String, current: String) {
        emit(
            DiagnosticEvent.AdapterStateChanged(
                previous = previous,
                current = current,
                timestampMillis = clock.nowMillis(),
            ),
        )
    }

    /** Convenience: connection state transition. */
    public fun connectionStateChanged(deviceId: BluetoothDeviceId, previous: ConnectionState, current: ConnectionState) {
        emit(
            DiagnosticEvent.ConnectionStateChanged(
                deviceId = deviceId,
                previous = previous,
                current = current,
                timestampMillis = clock.nowMillis(),
            ),
        )
    }

    /** Convenience: an operation started. */
    public fun operationStarted(operation: String, deviceId: BluetoothDeviceId? = null) {
        emit(DiagnosticEvent.OperationStarted(operation, deviceId, clock.nowMillis()))
    }

    /** Convenience: an operation finished. */
    public fun operationFinished(
        operation: String,
        startedAtMillis: Long,
        success: Boolean,
        deviceId: BluetoothDeviceId? = null,
    ) {
        val now = clock.nowMillis()
        emit(DiagnosticEvent.OperationFinished(operation, deviceId, now - startedAtMillis, success, now))
    }

    /** Convenience: a typed error was produced. */
    public fun error(error: BlueLibError, deviceId: BluetoothDeviceId? = null) {
        emit(DiagnosticEvent.ErrorReported(error, deviceId, clock.nowMillis()))
    }

    public companion object {
        /** Events kept for collectors that fall behind. */
        public const val DEFAULT_BUFFER: Int = 64
    }
}
