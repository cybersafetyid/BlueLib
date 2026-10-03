package io.github.cybersafetyid.bluelib.port

import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import kotlinx.coroutines.flow.Flow

/**
 * A connected, bidirectional byte stream, independent of the transport behind it.
 *
 * Bluetooth Classic sockets, TCP sockets, USB serial adapters and native UARTs all reduce to "bytes
 * in, bytes out", which is why every messenger and framer in BlueLib works on this one type.
 */
public interface ByteConnection : AutoCloseable {
    /** Human readable remote endpoint, e.g. `tcp://192.168.1.10:9100`, `usb:/dev/bus/usb/001/002`, `/dev/ttyS1`. */
    public val endpoint: String

    /** Incoming bytes as they arrive; chunk boundaries are arbitrary, use a framer to rebuild messages. */
    public val incoming: Flow<ByteArray>

    /** Writes bytes to the link. */
    public suspend fun write(value: ByteArray): BlueLibResult<Unit>

    /** `true` while the link is usable. */
    public val isConnected: Boolean
}
