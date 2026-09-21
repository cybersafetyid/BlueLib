package io.github.cybersafetyid.bluelib.domain.messenger

import io.github.cybersafetyid.bluelib.domain.codec.DataCodec
import io.github.cybersafetyid.bluelib.domain.codec.DataEncoding
import io.github.cybersafetyid.bluelib.domain.codec.MessageFramer
import io.github.cybersafetyid.bluelib.domain.codec.RawFramer
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import io.github.cybersafetyid.bluelib.port.GattSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf

/**
 * [BluetoothMessenger] implementation over a BLE [GattSession].
 */
public class GattMessenger(
    public val session: GattSession,
    public val serviceUuid: BluetoothUuid,
    public val characteristicUuid: BluetoothUuid,
    public val writeMode: WriteMode = WriteMode.WITH_RESPONSE,
    public val framer: MessageFramer = RawFramer,
) : BluetoothMessenger {

    override suspend fun sendText(text: String, encoding: DataEncoding): BlueLibResult<Unit> = runCatchingResult {
        sendBytes(DataCodec.encodeText(text, encoding))
    }

    override suspend fun sendHex(hexString: String): BlueLibResult<Unit> = runCatchingResult {
        sendBytes(DataCodec.encodeHex(hexString))
    }

    override suspend fun sendBinary(binaryString: String): BlueLibResult<Unit> = runCatchingResult {
        sendBytes(DataCodec.encodeBinary(binaryString))
    }

    override suspend fun sendBase64(base64String: String, urlSafe: Boolean): BlueLibResult<Unit> = runCatchingResult {
        sendBytes(DataCodec.decodeBase64(base64String))
    }

    override suspend fun sendBytes(bytes: ByteArray): BlueLibResult<Unit> {
        val framed = framer.frame(bytes)
        return session.write(
            service = serviceUuid,
            characteristic = characteristicUuid,
            value = framed,
            mode = writeMode,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val incomingBytes: Flow<ByteArray> = session.subscribe(serviceUuid, characteristicUuid)
        .flatMapConcat { chunk ->
            flowOf(*framer.parseIncoming(chunk).toTypedArray())
        }

    override fun close() {
        framer.reset()
    }

    private inline fun runCatchingResult(block: () -> BlueLibResult<Unit>): BlueLibResult<Unit> {
        return try {
            block()
        } catch (e: BlueLibValidationException) {
            failureOf(BlueLibError.OperationRejected(e.message ?: "Invalid payload", e.toString()))
        } catch (e: Exception) {
            failureOf(BlueLibError.Unexpected("Failed to encode payload", e))
        }
    }
}
