package io.github.cybersafetyid.bluelib.transport

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.port.ByteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ByteConnection] over a blocking `InputStream`/`OutputStream` pair.
 *
 * Bluetooth sockets, TCP sockets and serial device files all expose the same two streams, so one read
 * loop serves them all. The loop runs on [ioDispatcher] and closes the connection when the peer goes
 * away, because most stacks throw from `read` instead of returning `-1` when the link drops.
 *
 * Incoming chunks are not replayed: collect [incoming] before writing a request that expects a reply.
 */
public open class StreamConnection(
    override val endpoint: String,
    private val input: InputStream,
    private val output: OutputStream,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Receives read and write failures, typically wired to diagnostics. */
    private val onError: (BlueLibError) -> Unit = {},
    /** Releases the resource behind the streams (socket, file); called once on [close]. */
    private val release: () -> Unit = {},
) : ByteConnection {

    private val mutableIncoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = INCOMING_BUFFER_CHUNKS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val writeLock = Mutex()
    private val closed = AtomicBoolean(false)

    override val incoming: Flow<ByteArray> = mutableIncoming.asSharedFlow()

    override val isConnected: Boolean
        get() = !closed.get()

    init {
        scope.launch(ioDispatcher) { readLoop() }
    }

    override suspend fun write(value: ByteArray): BlueLibResult<Unit> {
        if (closed.get()) return failureOf(BlueLibError.Closed(endpoint))
        // Concurrent writers would otherwise interleave the bytes of two framed messages.
        return writeLock.withLock {
            withContext(ioDispatcher) {
                try {
                    output.write(value)
                    output.flush()
                    successOf(Unit)
                } catch (failure: Exception) {
                    val error = BlueLibError.Unexpected("Write to $endpoint failed", failure, operation = "stream.write")
                    onError(error)
                    failureOf(error)
                }
            }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) mutableIncoming.tryEmit(buffer.copyOf(read))
            }
        } catch (failure: Exception) {
            if (!closed.get()) {
                onError(BlueLibError.Unexpected("Read from $endpoint failed", failure, operation = "stream.read"))
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the streams first lets a blocking read return before the resource itself goes away.
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { release() }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 4096
        const val INCOMING_BUFFER_CHUNKS = 64
    }
}
