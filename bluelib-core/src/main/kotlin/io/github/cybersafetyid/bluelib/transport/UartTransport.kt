package io.github.cybersafetyid.bluelib.transport

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.Parity
import io.github.cybersafetyid.bluelib.domain.model.SerialSettings
import io.github.cybersafetyid.bluelib.domain.model.StopBits
import io.github.cybersafetyid.bluelib.port.ByteConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Native UART transport: a serial device file such as `/dev/ttyS1` or `/dev/ttyMSM1`.
 *
 * This is the RS-232/RS-485 port built into POS terminals, kiosks and industrial Android boards. Phones
 * have no such port; use a USB serial adapter there. The device file must be readable and writable by
 * the app, which normally needs a vendor or rooted image (`chmod 666`, SELinux policy).
 *
 * Line settings are applied with the system `stty` tool (toybox on Android, coreutils on Linux).
 */
public object UartTransport {

    /**
     * Opens [path] and applies [settings].
     *
     * Pass `configure = false` when the board vendor already configured the port, or when `stty` is
     * not available on the image.
     */
    public suspend fun open(
        path: String,
        scope: CoroutineScope,
        settings: SerialSettings = SerialSettings(),
        configure: Boolean = true,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onError: (BlueLibError) -> Unit = {},
    ): BlueLibResult<ByteConnection> {
        // Only device files: this must never become a way to write arbitrary files.
        if (!path.startsWith("/dev/") || path.contains("..")) {
            return failureOf(BlueLibError.OperationRejected("'$path' is not a device file", "expected /dev/tty*"))
        }
        val file = File(path)
        if (!file.exists()) {
            return failureOf(BlueLibError.LinkFailed(path, "device file does not exist", isRetryable = false))
        }
        if (!file.canRead() || !file.canWrite()) {
            return failureOf(
                BlueLibError.LinkFailed(
                    endpoint = path,
                    reason = "no read/write permission; the image must grant access to the app",
                    isRetryable = false,
                ),
            )
        }
        if (configure) {
            val args = sttyArguments(settings)
                ?: return failureOf(
                    BlueLibError.OperationRejected("$settings cannot be set on a native UART", "use NONE/ODD/EVEN parity and 1 or 2 stop bits"),
                )
            val failure = runStty(path, args, ioDispatcher)
            if (failure != null) return failureOf(failure)
        }

        return try {
            val input = FileInputStream(file)
            val output = FileOutputStream(file)
            // ponytail: a read blocked on a tty is not woken by close(); the reader thread exits on the
            // next received byte. A JNI termios port with VMIN/VTIME would fix it if leaks ever matter.
            successOf(StreamConnection(path, input, output, scope, ioDispatcher, onError))
        } catch (failure: IOException) {
            failureOf(BlueLibError.LinkFailed(path, failure.message ?: "open failed", failure))
        }
    }

    /** `stty` arguments for [settings], or `null` when Linux ttys cannot express them. */
    internal fun sttyArguments(settings: SerialSettings): List<String>? {
        val parity = when (settings.parity) {
            Parity.NONE -> listOf("-parenb")
            Parity.ODD -> listOf("parenb", "parodd")
            Parity.EVEN -> listOf("parenb", "-parodd")
            Parity.MARK, Parity.SPACE -> return null
        }
        val stop = when (settings.stopBits) {
            StopBits.ONE -> "-cstopb"
            StopBits.TWO -> "cstopb"
            StopBits.ONE_POINT_FIVE -> return null
        }
        return listOf(settings.baudRate.toString(), "cs${settings.dataBits}", stop) + parity + listOf("raw", "-echo", "clocal")
    }

    private suspend fun runStty(path: String, args: List<String>, ioDispatcher: CoroutineDispatcher): BlueLibError? =
        try {
            val (exit, output) = runInterruptible(ioDispatcher) {
                val process = ProcessBuilder(listOf("stty", "-F", path) + args).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor() to text
            }
            if (exit == 0) null else BlueLibError.LinkFailed(path, "stty exited with $exit: ${output.trim()}", isRetryable = false)
        } catch (failure: IOException) {
            BlueLibError.LinkFailed(path, "stty is not available (${failure.message}); pass configure = false", failure, isRetryable = false)
        }
}
