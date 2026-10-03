package io.github.cybersafetyid.bluelib.domain.model

/** Parity bit setting of a serial line. */
public enum class Parity { NONE, ODD, EVEN, MARK, SPACE }

/** Stop bits of a serial line. */
public enum class StopBits { ONE, ONE_POINT_FIVE, TWO }

/**
 * Line settings of an RS-232/RS-485/TTL serial port, shared by USB serial adapters and native UARTs.
 *
 * The default `9600 8N1` is what most scales, printers, PLCs and barcode readers ship with.
 */
public data class SerialSettings(
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: StopBits = StopBits.ONE,
    val parity: Parity = Parity.NONE,
) {
    init {
        require(baudRate > 0) { "baudRate must be positive, was $baudRate" }
        require(dataBits in 5..8) { "dataBits must be 5..8, was $dataBits" }
    }
}
