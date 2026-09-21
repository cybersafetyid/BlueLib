package io.github.cybersafetyid.bluelib.port

import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.GattServerConnection
import io.github.cybersafetyid.bluelib.domain.model.GattServerRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `GattServer.respond` is a default method on an interface, which is exactly the kind of thing that
 * silently breaks when the signature changes: nothing in the library itself is forced to call it.
 */
class GattServerRespondTest {

    private class RecordingServer : GattServer {
        var lastRequestId: Int? = null
        var lastStatus: Int? = null
        var lastOffset: Int? = null
        var lastValue: ByteArray? = null

        override val connections: StateFlow<List<GattServerConnection>> =
            MutableStateFlow(emptyList())

        override val requests: Flow<GattServerRequest> = emptyFlow()

        override suspend fun notify(
            deviceId: BluetoothDeviceId,
            service: BluetoothUuid,
            characteristic: BluetoothUuid,
            value: ByteArray,
            confirm: Boolean,
        ): BlueLibResult<Unit> = successOf(Unit)

        override suspend fun sendResponse(
            deviceId: BluetoothDeviceId,
            requestId: Int,
            status: Int,
            offset: Int,
            value: ByteArray?,
        ): BlueLibResult<Unit> {
            lastRequestId = requestId
            lastStatus = status
            lastOffset = offset
            lastValue = value
            return successOf(Unit)
        }

        override suspend fun requestMtu(deviceId: BluetoothDeviceId, mtu: Int): BlueLibResult<Int> =
            successOf(mtu)

        override fun close() = Unit
    }

    @Test
    fun `answers with the ATT success status and echoes the request offset`() = runTest {
        val server = RecordingServer()
        val request = GattServerRequest.ReadCharacteristic(
            deviceId = BluetoothDeviceId.of("AA:BB:CC:DD:EE:FF"),
            requestId = 42,
            offset = 7,
            service = BluetoothUuid.HEART_RATE,
            characteristic = BluetoothUuid.fromShort(0x2A37),
        )

        server.respond(request, byteArrayOf(0x01))

        assertEquals(42, server.lastRequestId)
        assertEquals(0, server.lastStatus)
        assertEquals(7, server.lastOffset)
        assertEquals(listOf<Byte>(0x01), server.lastValue?.toList())
    }

    @Test
    fun `an answer without a value stays a status only response`() = runTest {
        val server = RecordingServer()
        val request = GattServerRequest.WriteCharacteristic(
            deviceId = BluetoothDeviceId.of("AA:BB:CC:DD:EE:FF"),
            requestId = 7,
            offset = 0,
            service = BluetoothUuid.HEART_RATE,
            characteristic = BluetoothUuid.fromShort(0x2A39),
            value = byteArrayOf(0x01, 0x00),
            responseNeeded = true,
            preparedWrite = false,
        )

        server.respond(request)

        assertEquals(7, server.lastRequestId)
        assertEquals(0, server.lastStatus)
        assertNull(server.lastValue)
    }
}
