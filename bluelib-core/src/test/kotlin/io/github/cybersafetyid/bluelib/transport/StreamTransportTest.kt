package io.github.cybersafetyid.bluelib.transport

import io.github.cybersafetyid.bluelib.domain.codec.DelimiterFramer
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.messenger.StreamMessenger
import io.github.cybersafetyid.bluelib.domain.model.Parity
import io.github.cybersafetyid.bluelib.domain.model.SerialSettings
import io.github.cybersafetyid.bluelib.domain.model.StopBits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class StreamTransportTest {

    @Test
    fun `TCP messenger exchanges framed text with a real socket`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        ServerSocket(0).use { server ->
            // Echo server: answers every line with "ACK:<line>".
            thread {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val line = reader.readLine()
                    client.getOutputStream().write("ACK:$line\n".toByteArray())
                    client.getOutputStream().flush()
                }
            }

            val connection = TcpTransport.connect("127.0.0.1", server.localPort, scope).getOrThrow()
            val messenger = StreamMessenger(connection, DelimiterFramer(byteArrayOf(0x0A)))
            withTimeout(5_000) {
                val reply = async { messenger.incomingText().first() }
                yield()
                assertIs<BlueLibResult.Success<Unit>>(messenger.sendText("PING"))
                assertEquals("ACK:PING", reply.await())
            }
            messenger.close()
            assertFalse(connection.isConnected)
        }
        scope.cancel()
    }

    @Test
    fun `TCP rejects invalid endpoints and reports refused connections`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        val invalid = TcpTransport.connect("127.0.0.1", 70_000, scope)
        assertIs<BlueLibError.OperationRejected>(invalid.errorOrNull())

        val port = ServerSocket(0).use { it.localPort } // closed again: nothing listens there
        val refused = TcpTransport.connect("127.0.0.1", port, scope)
        assertIs<BlueLibError.LinkFailed>(refused.errorOrNull())
        scope.cancel()
    }

    @Test
    fun `UART refuses paths outside dev and maps settings to stty`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO)
        assertIs<BlueLibError.OperationRejected>(UartTransport.open("/data/local/tmp/x", scope).errorOrNull())
        assertIs<BlueLibError.OperationRejected>(UartTransport.open("/dev/../etc/passwd", scope).errorOrNull())
        scope.cancel()

        assertEquals(
            listOf("19200", "cs7", "cstopb", "parenb", "-parodd", "raw", "-echo", "clocal"),
            UartTransport.sttyArguments(SerialSettings(19_200, 7, StopBits.TWO, Parity.EVEN)),
        )
        assertNull(UartTransport.sttyArguments(SerialSettings(parity = Parity.MARK)))
    }
}
