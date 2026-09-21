package io.github.cybersafetyid.bluelib.domain.messenger

import io.github.cybersafetyid.bluelib.domain.codec.DelimiterFramer
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.ConnectionPriority
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import io.github.cybersafetyid.bluelib.domain.model.GattProfile
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.PhyCoding
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import io.github.cybersafetyid.bluelib.port.ClassicConnection
import io.github.cybersafetyid.bluelib.port.GattSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessengerTest {

    private val serviceUuid = BluetoothUuid.parse("0000180f-0000-1000-8000-00805f9b34fb")
    private val charUuid = BluetoothUuid.parse("00002a19-0000-1000-8000-00805f9b34fb")

    @Test
    fun `GattMessenger sends and receives text, hex, binary, and base64`() = runTest {
        val session = TestGattSession()
        val messenger = GattMessenger(
            session = session,
            serviceUuid = serviceUuid,
            characteristicUuid = charUuid,
            framer = DelimiterFramer.LINE_FEED,
        )

        // Send Text
        val resText = messenger.sendText("Hello")
        assertTrue(resText is BlueLibResult.Success)
        assertContentEquals("Hello\n".toByteArray(Charsets.UTF_8), session.lastWrittenValue)

        // Send Hex
        val resHex = messenger.sendHex("48656C6C6F")
        assertTrue(resHex is BlueLibResult.Success)
        assertContentEquals("Hello\n".toByteArray(Charsets.UTF_8), session.lastWrittenValue)

        // Send Base64
        val resBase64 = messenger.sendBase64("SGVsbG8=")
        assertTrue(resBase64 is BlueLibResult.Success)
        assertContentEquals("Hello\n".toByteArray(Charsets.UTF_8), session.lastWrittenValue)

        // Receive Text
        session.incomingNotifications.tryEmit("World\n".toByteArray(Charsets.UTF_8))
        val receivedText = messenger.incomingText().first()
        assertEquals("World", receivedText)

        messenger.close()
    }

    @Test
    fun `ClassicMessenger sends and receives text over socket connection`() = runTest {
        val connection = TestClassicConnection()
        val messenger = ClassicMessenger(
            connection = connection,
            framer = DelimiterFramer.LINE_FEED,
        )

        val res = messenger.sendText("SocketData")
        assertTrue(res is BlueLibResult.Success)
        assertContentEquals("SocketData\n".toByteArray(Charsets.UTF_8), connection.lastWrittenValue)

        connection.incomingFlow.tryEmit("IncomingText\n".toByteArray(Charsets.UTF_8))
        val text = messenger.incomingText().first()
        assertEquals("IncomingText", text)

        messenger.close()
    }

    private class TestGattSession : GattSession {
        override val deviceId: BluetoothDeviceId = BluetoothDeviceId.of("11:22:33:44:55:66")
        override val state: StateFlow<ConnectionState> get() = error("Not needed")
        override val profile: StateFlow<GattProfile?> get() = error("Not needed")
        override val mtu: StateFlow<Int> get() = error("Not needed")

        var lastWrittenValue: ByteArray? = null
        val incomingNotifications = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 16)

        override suspend fun discoverServices(): BlueLibResult<GattProfile> = successOf(GattProfile())
        override suspend fun read(service: BluetoothUuid, characteristic: BluetoothUuid): BlueLibResult<ByteArray> = successOf(ByteArray(0))
        override suspend fun write(
            service: BluetoothUuid,
            characteristic: BluetoothUuid,
            value: ByteArray,
            mode: WriteMode,
        ): BlueLibResult<Unit> {
            lastWrittenValue = value
            return successOf(Unit)
        }

        override suspend fun readDescriptor(service: BluetoothUuid, characteristic: BluetoothUuid, descriptor: BluetoothUuid): BlueLibResult<ByteArray> = successOf(ByteArray(0))
        override suspend fun writeDescriptor(service: BluetoothUuid, characteristic: BluetoothUuid, descriptor: BluetoothUuid, value: ByteArray): BlueLibResult<Unit> = successOf(Unit)
        override fun subscribe(service: BluetoothUuid, characteristic: BluetoothUuid): Flow<ByteArray> = incomingNotifications
        override suspend fun requestConnectionPriority(priority: ConnectionPriority): BlueLibResult<Unit> = successOf(Unit)
        override suspend fun requestPhy(phy: Phy, coding: PhyCoding?): BlueLibResult<Phy> = successOf(phy)
        override suspend fun readPhy(): BlueLibResult<List<Phy>> = successOf(listOf(Phy.LE_1M))
        override fun close() {}
    }

    private class TestClassicConnection : ClassicConnection {
        override val deviceId: BluetoothDeviceId = BluetoothDeviceId.of("11:22:33:44:55:66")
        override val isConnected: Boolean = true
        var lastWrittenValue: ByteArray? = null
        val incomingFlow = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 16)

        override val incoming: Flow<ByteArray> get() = incomingFlow
        override suspend fun write(value: ByteArray): BlueLibResult<Unit> {
            lastWrittenValue = value
            return successOf(Unit)
        }
        override fun close() {}
    }
}
