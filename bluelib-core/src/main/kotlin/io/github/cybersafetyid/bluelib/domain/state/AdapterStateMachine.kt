package io.github.cybersafetyid.bluelib.domain.state

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.BondState

/** State of the local Bluetooth adapter, matching `BluetoothAdapter.STATE_*`. */
public enum class AdapterState {
    UNKNOWN,
    OFF,
    TURNING_ON,
    ON,
    TURNING_OFF,
    ;

    /** `true` when the adapter is fully usable. */
    public val isOn: Boolean
        get() = this == ON

    /** `true` while the adapter is in a transient state. */
    public val isTransitioning: Boolean
        get() = this == TURNING_ON || this == TURNING_OFF
}

/**
 * Tracks the adapter state reported through `ACTION_STATE_CHANGED`.
 *
 * The machine tolerates the sloppy broadcast ordering that some vendors produce (for example a
 * `TURNING_ON` broadcast after the adapter already reported `ON`) while still making impossible
 * transitions like `OFF → TURNING_OFF` visible through [issues].
 */
public class AdapterStateMachine(initialState: AdapterState = AdapterState.UNKNOWN) {

    /** Current adapter state. */
    public var state: AdapterState = initialState
        private set

    private val recordedIssues = mutableListOf<String>()

    /** Transitions the platform reported that do not make sense; useful for OEM bug reports. */
    public val issues: List<String>
        get() = recordedIssues.toList()

    /** Applies a new state reported by the platform and returns the resulting state. */
    public fun update(newState: AdapterState): AdapterState {
        val allowed = allowedTransitions.getValue(state)
        if (newState != state && newState !in allowed) {
            recordedIssues += "Rejected transition ${state.name} → ${newState.name}"
        } else {
            state = newState
        }
        return state
    }

    /** Applies a transition strictly, throwing when the platform reports nonsense. */
    public fun updateStrict(newState: AdapterState): AdapterState {
        val allowed = allowedTransitions.getValue(state)
        if (newState != state && newState !in allowed) {
            throw BlueLibValidationException.IllegalState(
                stateMachine = "AdapterStateMachine",
                from = state.name,
                to = newState.name,
                hint = "Allowed transitions from $state: ${allowed.joinToString { it.name }}.",
            )
        }
        state = newState
        return state
    }

    private companion object {
        private val allowedTransitions: Map<AdapterState, Set<AdapterState>> = mapOf(
            AdapterState.UNKNOWN to AdapterState.entries.toSet(),
            AdapterState.OFF to setOf(AdapterState.TURNING_ON, AdapterState.UNKNOWN),
            AdapterState.TURNING_ON to setOf(AdapterState.ON, AdapterState.OFF),
            AdapterState.ON to setOf(AdapterState.TURNING_OFF, AdapterState.UNKNOWN),
            AdapterState.TURNING_OFF to setOf(AdapterState.OFF, AdapterState.ON),
        )
    }
}

/** Events that drive bonding. */
public enum class BondEvent {
    /** `createBond()` was called. */
    BOND_REQUESTED,

    /** `BOND_BONDING`. */
    BONDING_STARTED,

    /** `BOND_BONDED`. */
    BONDED,

    /** `BOND_NONE` after a successful bond: the bond was lost or removed. */
    BOND_REMOVED,

    /** `BOND_NONE` while bonding was in progress: pairing failed. */
    BOND_FAILED,

    /** Android 16.1+ reported the reason through `EXTRA_BOND_LOSS_REASON`. */
    BOND_LOSS_REASON_REPORTED,

    /** The device was unpaired from the system UI. */
    UNPAIRED,
}

/** Guards the bond lifecycle, including the Android 16/16.1/17 bond-loss semantics. */
public class BondStateMachine(initialState: BondState = BondState.NONE) {

    /** Current bond state. */
    public var state: BondState = initialState
        private set

    private val recordedEvents = mutableListOf<BondEvent>()

    /** Events seen so far, oldest first. */
    public val events: List<BondEvent>
        get() = recordedEvents.toList()

    /**
     * Applies [event].
     *
     * `BOND_REMOVED` and `UNPAIRED` both lead to [BondState.NONE]; `BOND_FAILED` also returns to
     * [BondState.NONE] but records the failure so callers can distinguish "was never bonded" from
     * "pairing just failed".
     */
    public fun onEvent(event: BondEvent): BondState {
        recordedEvents += event
        state = when (event) {
            BondEvent.BOND_REQUESTED, BondEvent.BONDING_STARTED -> BondState.BONDING
            BondEvent.BONDED -> BondState.BONDED
            BondEvent.BOND_REMOVED, BondEvent.BOND_FAILED, BondEvent.UNPAIRED -> BondState.NONE
            BondEvent.BOND_LOSS_REASON_REPORTED -> state
        }
        return state
    }

    /** `true` when the last recorded event was a pairing failure. */
    public val lastAttemptFailed: Boolean
        get() = recordedEvents.lastOrNull() == BondEvent.BOND_FAILED
}
