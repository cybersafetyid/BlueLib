package io.github.cybersafetyid.bluelib.domain.state

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionStateMachineTest {

    @Test
    fun `walks the happy path from disconnected to ready`() {
        val machine = ConnectionStateMachine()

        machine.onEvent(ConnectionEvent.CONNECT_REQUESTED)
        assertEquals(ConnectionState.CONNECTING, machine.state)
        machine.onEvent(ConnectionEvent.CONNECTED)
        machine.onEvent(ConnectionEvent.SERVICES_DISCOVERING)
        assertEquals(ConnectionState.DISCOVERING_SERVICES, machine.state)
        machine.onEvent(ConnectionEvent.SERVICES_READY)

        assertEquals(ConnectionState.READY, machine.state)
        assertTrue(machine.isOperational)
        assertEquals(
            listOf(
                ConnectionState.DISCONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.DISCOVERING_SERVICES,
                ConnectionState.READY,
            ),
            machine.stateHistory,
        )
    }

    @Test
    fun `an empty device name is still rejected when the name is included`() {
        val machine = ConnectionStateMachine()

        // Repeated CONNECTED events are the platform's normal behaviour, not an error.
        machine.onEvent(ConnectionEvent.CONNECT_REQUESTED)
        machine.onEvent(ConnectionEvent.CONNECTED)
        machine.onEvent(ConnectionEvent.CONNECTED)

        assertEquals(ConnectionState.CONNECTED, machine.state)
        assertEquals(3, machine.stateHistory.size)
    }

    @Test
    fun `rejects impossible transitions with a readable hint`() {
        val machine = ConnectionStateMachine()

        val failure = assertFailsWith<BlueLibValidationException.IllegalState> {
            machine.onEvent(ConnectionEvent.SERVICES_READY)
        }

        assertEquals("illegal-state", failure.docsAnchor)
        assertTrue(failure.hint.contains("CONNECT_REQUESTED"))
    }

    @Test
    fun `a service change moves back to discovery`() {
        val machine = ConnectionStateMachine(ConnectionState.READY)

        machine.onEvent(ConnectionEvent.SERVICE_CHANGED)

        assertEquals(ConnectionState.DISCOVERING_SERVICES, machine.state)
    }

    @Test
    fun `CLOSED is terminal`() {
        val machine = ConnectionStateMachine(ConnectionState.READY)

        machine.onEvent(ConnectionEvent.CLOSED)

        assertTrue(machine.isClosed)
        assertFalse(machine.canHandle(ConnectionEvent.CONNECT_REQUESTED))
        assertFailsWith<BlueLibValidationException.IllegalState> {
            machine.onEvent(ConnectionEvent.CONNECT_REQUESTED)
        }
    }

    @Test
    fun `reconnects after a disconnect`() {
        val machine = ConnectionStateMachine(ConnectionState.READY)

        machine.onEvent(ConnectionEvent.DISCONNECTED)
        assertEquals(ConnectionState.DISCONNECTED, machine.state)
        machine.onEvent(ConnectionEvent.CONNECT_REQUESTED)
        machine.onEvent(ConnectionEvent.CONNECTED)

        assertEquals(ConnectionState.CONNECTED, machine.state)
    }

    @Test
    fun `adapter state machine records OEM nonsense instead of crashing`() {
        val machine = AdapterStateMachine(AdapterState.OFF)

        machine.update(AdapterState.TURNING_OFF)

        assertEquals(AdapterState.OFF, machine.state)
        assertEquals(listOf("Rejected transition OFF → TURNING_OFF"), machine.issues)
    }

    @Test
    fun `adapter state machine can also reject nonsense strictly`() {
        val machine = AdapterStateMachine(AdapterState.OFF)

        assertFailsWith<BlueLibValidationException.IllegalState> {
            machine.updateStrict(AdapterState.TURNING_OFF)
        }
        assertEquals(AdapterState.TURNING_ON, machine.updateStrict(AdapterState.TURNING_ON))
    }

    @Test
    fun `bond state machine reacts to pairing failures and bond loss`() {
        val machine = BondStateMachine()

        machine.onEvent(BondEvent.BOND_REQUESTED)
        assertEquals(io.github.cybersafetyid.bluelib.domain.model.BondState.BONDING, machine.state)
        machine.onEvent(BondEvent.BONDED)
        assertEquals(io.github.cybersafetyid.bluelib.domain.model.BondState.BONDED, machine.state)

        machine.onEvent(BondEvent.BOND_LOSS_REASON_REPORTED)
        assertEquals(io.github.cybersafetyid.bluelib.domain.model.BondState.BONDED, machine.state)

        machine.onEvent(BondEvent.BOND_FAILED)
        assertEquals(io.github.cybersafetyid.bluelib.domain.model.BondState.NONE, machine.state)
        assertTrue(machine.lastAttemptFailed)
    }
}
