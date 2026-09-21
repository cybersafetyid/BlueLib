package io.github.cybersafetyid.bluelib.domain.messenger

import io.github.cybersafetyid.bluelib.domain.codec.DataCodec
import io.github.cybersafetyid.bluelib.domain.codec.DataEncoding
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * High-level unified API for sending and receiving data over connected Bluetooth sessions.
 */
public interface BluetoothMessenger : AutoCloseable {

    /** Sends a text string message using [encoding] (default: UTF-8). */
    public suspend fun sendText(text: String, encoding: DataEncoding = DataEncoding.UTF8): BlueLibResult<Unit>

    /** Sends a hexadecimal string message (e.g. `"48656C6C6F"`). */
    public suspend fun sendHex(hexString: String): BlueLibResult<Unit>

    /** Sends a binary bit string message (e.g. `"0100100001100101"`). */
    public suspend fun sendBinary(binaryString: String): BlueLibResult<Unit>

    /** Sends a Base64 string message (e.g. `"SGVsbG8="`). */
    public suspend fun sendBase64(base64String: String, urlSafe: Boolean = false): BlueLibResult<Unit>

    /** Sends raw bytes. */
    public suspend fun sendBytes(bytes: ByteArray): BlueLibResult<Unit>

    /** Stream of incoming messages decoded as text strings. */
    public fun incomingText(encoding: DataEncoding = DataEncoding.UTF8): Flow<String> =
        incomingBytes.map { DataCodec.decodeText(it, encoding) }

    /** Stream of incoming messages decoded as Hex strings. */
    public fun incomingHex(separator: String = ""): Flow<String> =
        incomingBytes.map { DataCodec.decodeHex(it, separator) }

    /** Stream of incoming messages decoded as binary bit strings. */
    public fun incomingBinary(formatSpaces: Boolean = false): Flow<String> =
        incomingBytes.map { DataCodec.decodeBinary(it, formatSpaces) }

    /** Stream of incoming messages decoded as Base64 strings. */
    public fun incomingBase64(urlSafe: Boolean = false): Flow<String> =
        incomingBytes.map { DataCodec.encodeBase64(it, urlSafe) }

    /** Stream of raw incoming byte arrays. */
    public val incomingBytes: Flow<ByteArray>
}
