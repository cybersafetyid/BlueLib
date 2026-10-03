package io.github.cybersafetyid.bluelib.transport

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.port.ByteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Options for a TCP client connection. */
public data class TcpSettings(
    /** Upper bound for DNS lookup plus the TCP handshake. */
    val connectTimeoutMillis: Int = 10_000,
    /** Lets the OS detect a dead peer on long idle links (cash drawers, PLC polling). */
    val keepAlive: Boolean = true,
    /** Disables Nagle so short commands leave immediately instead of waiting up to 200 ms. */
    val noDelay: Boolean = true,
)

/**
 * TCP/IP client transport.
 *
 * Covers anything reachable over Ethernet (RJ45) or Wi-Fi: raw-port printers (9100), Modbus TCP (502),
 * serial device servers that bridge RS-232/RS-485 to a TCP port, ESP32/ESP8266 firmware, and custom
 * protocols. The Android app needs the `android.permission.INTERNET` permission.
 */
public object TcpTransport {

    /** Opens a TCP connection to [host]:[port]; the read loop runs in [scope] until the link closes. */
    public suspend fun connect(
        host: String,
        port: Int,
        scope: CoroutineScope,
        settings: TcpSettings = TcpSettings(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onError: (BlueLibError) -> Unit = {},
    ): BlueLibResult<ByteConnection> {
        val endpoint = "tcp://$host:$port"
        if (host.isBlank() || port !in 1..65_535 || settings.connectTimeoutMillis < 0) {
            return failureOf(
                BlueLibError.OperationRejected("invalid TCP endpoint '$endpoint'", "host must be set, port 1..65535, timeout >= 0"),
            )
        }

        val socket = Socket()
        return try {
            runInterruptible(ioDispatcher) {
                socket.tcpNoDelay = settings.noDelay
                socket.keepAlive = settings.keepAlive
                // The address is resolved here, on the IO thread, because DNS lookups block.
                socket.connect(InetSocketAddress(host, port), settings.connectTimeoutMillis)
            }
            successOf(
                StreamConnection(
                    endpoint = endpoint,
                    input = socket.getInputStream(),
                    output = socket.getOutputStream(),
                    scope = scope,
                    ioDispatcher = ioDispatcher,
                    onError = onError,
                    release = socket::close,
                ),
            )
        } catch (failure: IOException) {
            runCatching { socket.close() }
            failureOf(BlueLibError.LinkFailed(endpoint, failure.message ?: failure.javaClass.simpleName, failure))
        } catch (cancelled: Throwable) {
            runCatching { socket.close() }
            throw cancelled
        }
    }
}
