package io.github.cybersafetyid.bluelib.domain.state

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState

/** Events that drive a GATT connection forward. */
public enum class ConnectionEvent {
    /** A connect request was issued. */
    CONNECT_REQUESTED,

    /** `onConnectionStateChange(STATE_CONNECTED)`. */
    CONNECTED,

    /** Service discovery started. */
    SERVICES_DISCOVERING,

    /** Service discovery completed successfully. */
    SERVICES_READY,

    /** The platform reported a GATT service change (`onServiceChanged`, Android 12+). */
    SERVICE_CHANGED,

    /** The caller asked to disconnect. */
    DISCONNECT_REQUESTED,

    /** `onConnectionStateChange(STATE_DISCONNECTED)`. */
    DISCONNECTED,

    /** The reconnect supervisor scheduled another attempt. */
    RECONNECT_SCHEDULED,

    /** `close()` was called; the state machine is terminal afterwards. */
    CLOSED,
}

/**
 * Guards the lifecycle of one GATT connection.
 *
 * Android is chatty: it reports `STATE_CONNECTED` more than once, reports a disconnect while a
 * connect is pending, and reports a disconnect *after* the caller closed the `BluetoothGatt`.
 * Instead of scattering `if` checks across the platform layer, every transition goes through this
 * machine, which:
 *
 * * accepts repeated events that do not change the state (idempotent no-ops, so out-of-order
 *   callbacks cannot corrupt the model),
 * * rejects genuinely impossible transitions with
 *   [BlueLibValidationException.IllegalState], making a bug visible in tests instead of a hang in
 *   production.
 */
public class ConnectionStateMachine(initialState: ConnectionState = ConnectionState.DISCONNECTED) {

    /** Current state. */
    public var state: ConnectionState = initialState
        private set

    private val history = mutableListOf<ConnectionState>(initialState)

    /** States the connection has passed through, oldest first. Used by diagnostics. */
    public val stateHistory: List<ConnectionState>
        get() = history.toList()

    /** `true` when [event] can be applied in the current state. */
    public fun canHandle(event: ConnectionEvent): Boolean =
        resolve(event) != null || event == ConnectionEvent.CLOSED

    /**
     * Applies [event] and returns the resulting state.
     *
     * @throws BlueLibValidationException.IllegalState when the transition is not allowed.
     */
    public fun onEvent(event: ConnectionEvent): ConnectionState {
        val next = resolve(event)
            ?: throw BlueLibValidationException.IllegalState(
                stateMachine = "ConnectionStateMachine",
                from = state.name,
                to = event.name,
                hint = buildString {
                    append("Events allowed in $state: ")
                    append(allowedEvents.getValue(state).joinToString { it.name })
                    if (state == ConnectionState.CLOSED) {
                        append(". CLOSED is terminal: create a new connection object instead of reusing one.")
                    }
                },
            )
        if (next != state) {
            state = next
            history += next
        }
        return state
    }

    /** `true` when the connection can be used for GATT operations right now. */
    public val isOperational: Boolean
        get() = state == ConnectionState.READY || state == ConnectionState.DISCOVERING_SERVICES

    /** `true` when the state machine reached its terminal state. */
    public val isClosed: Boolean
        get() = state == ConnectionState.CLOSED

    private fun resolve(event: ConnectionEvent): ConnectionState? {
        val allowed = allowedEvents.getValue(state)
        if (event !in allowed) return null
        // Repeat events that map to the current state are accepted as no-ops.
        return transitionTargets[state]?.get(event) ?: state
    }

    private companion object {
        private val allowedEvents: Map<ConnectionState, Set<ConnectionEvent>> = mapOf(
            ConnectionState.DISCONNECTED to setOf(
                ConnectionEvent.CONNECT_REQUESTED,
                ConnectionEvent.CLOSED,
            ),
            ConnectionState.CONNECTING to setOf(
                ConnectionEvent.CONNECTED,
                ConnectionEvent.DISCONNECTED,
                ConnectionEvent.DISCONNECT_REQUESTED,
                ConnectionEvent.CLOSED,
            ),
            ConnectionState.CONNECTED to setOf(
                ConnectionEvent.CONNECTED,
                ConnectionEvent.SERVICES_DISCOVERING,
                ConnectionEvent.SERVICE_CHANGED,
                ConnectionEvent.DISCONNECT_REQUESTED,
                ConnectionEvent.DISCONNECTED,
                ConnectionEvent.RECONNECT_SCHEDULED,
                ConnectionEvent.CLOSED,
            ),
            ConnectionState.DISCOVERING_SERVICES to setOf(
                ConnectionEvent.SERVICES_READY,
                ConnectionEvent.SERVICES_DISCOVERING,
                ConnectionEvent.SERVICE_CHANGED,
                ConnectionEvent.DISCONNECT_REQUESTED,
                ConnectionEvent.DISCONNECTED,
                ConnectionEvent.CLOSED,
            ),
            ConnectionState.READY to setOf(
                ConnectionEvent.SERVICE_CHANGED,
                ConnectionEvent.DISCONNECT_REQUESTED,
                ConnectionEvent.DISCONNECTED,
                ConnectionEvent.RECONNECT_SCHEDULED,
                ConnectionEvent.CLOSED,
            ),
            ConnectionState.DISCONNECTING to setOf(
                ConnectionEvent.DISCONNECTED,
                ConnectionEvent.RECONNECT_SCHEDULED,
                ConnectionEvent.CLOSED,
            ),
            ConnectionState.RECONNECTING to setOf(
                ConnectionEvent.CONNECT_REQUESTED,
                ConnectionEvent.DISCONNECTED,
                ConnectionEvent.CLOSED,
            ),
            ConnectionState.CLOSED to setOf(
                ConnectionEvent.CLOSED,
            ),
        )

        private val transitionTargets: Map<ConnectionState, Map<ConnectionEvent, ConnectionState>> = mapOf(
            ConnectionState.DISCONNECTED to mapOf(
                ConnectionEvent.CONNECT_REQUESTED to ConnectionState.CONNECTING,
                ConnectionEvent.CLOSED to ConnectionState.CLOSED,
            ),
            ConnectionState.CONNECTING to mapOf(
                ConnectionEvent.CONNECTED to ConnectionState.CONNECTED,
                ConnectionEvent.DISCONNECTED to ConnectionState.DISCONNECTED,
                ConnectionEvent.DISCONNECT_REQUESTED to ConnectionState.DISCONNECTING,
                ConnectionEvent.CLOSED to ConnectionState.CLOSED,
            ),
            ConnectionState.CONNECTED to mapOf(
                ConnectionEvent.SERVICES_DISCOVERING to ConnectionState.DISCOVERING_SERVICES,
                ConnectionEvent.SERVICE_CHANGED to ConnectionState.DISCOVERING_SERVICES,
                ConnectionEvent.DISCONNECT_REQUESTED to ConnectionState.DISCONNECTING,
                ConnectionEvent.DISCONNECTED to ConnectionState.DISCONNECTED,
                ConnectionEvent.RECONNECT_SCHEDULED to ConnectionState.RECONNECTING,
                ConnectionEvent.CLOSED to ConnectionState.CLOSED,
            ),
            ConnectionState.DISCOVERING_SERVICES to mapOf(
                ConnectionEvent.SERVICES_READY to ConnectionState.READY,
                ConnectionEvent.SERVICE_CHANGED to ConnectionState.DISCOVERING_SERVICES,
                ConnectionEvent.DISCONNECT_REQUESTED to ConnectionState.DISCONNECTING,
                ConnectionEvent.DISCONNECTED to ConnectionState.DISCONNECTED,
                ConnectionEvent.CLOSED to ConnectionState.CLOSED,
            ),
            ConnectionState.READY to mapOf(
                ConnectionEvent.SERVICE_CHANGED to ConnectionState.DISCOVERING_SERVICES,
                ConnectionEvent.DISCONNECT_REQUESTED to ConnectionState.DISCONNECTING,
                ConnectionEvent.DISCONNECTED to ConnectionState.DISCONNECTED,
                ConnectionEvent.RECONNECT_SCHEDULED to ConnectionState.RECONNECTING,
                ConnectionEvent.CLOSED to ConnectionState.CLOSED,
            ),
            ConnectionState.DISCONNECTING to mapOf(
                ConnectionEvent.DISCONNECTED to ConnectionState.DISCONNECTED,
                ConnectionEvent.RECONNECT_SCHEDULED to ConnectionState.RECONNECTING,
                ConnectionEvent.CLOSED to ConnectionState.CLOSED,
            ),
            ConnectionState.RECONNECTING to mapOf(
                ConnectionEvent.CONNECT_REQUESTED to ConnectionState.CONNECTING,
                ConnectionEvent.DISCONNECTED to ConnectionState.DISCONNECTED,
                ConnectionEvent.CLOSED to ConnectionState.CLOSED,
            ),
            ConnectionState.CLOSED to emptyMap(),
        )
    }
}
