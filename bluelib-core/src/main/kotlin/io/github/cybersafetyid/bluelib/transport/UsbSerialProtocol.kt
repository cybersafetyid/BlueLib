package io.github.cybersafetyid.bluelib.transport

import io.github.cybersafetyid.bluelib.BlueLibInternal
import io.github.cybersafetyid.bluelib.domain.model.Parity
import io.github.cybersafetyid.bluelib.domain.model.SerialSettings
import io.github.cybersafetyid.bluelib.domain.model.StopBits

/**
 * USB serial chip families BlueLib can drive without root or kernel drivers.
 *
 * Together they cover nearly every USB to RS-232/RS-485/TTL cable and Arduino-class board. Prolific
 * PL2303 is not supported yet.
 */
public enum class UsbSerialDriver {
    /** USB CDC-ACM class devices: Arduino Uno/Mega, STM32, ESP32-S2/S3, CH9102, most modern modems. */
    CDC_ACM,

    /** Silicon Labs CP210x. */
    CP210X,

    /** FTDI FT232R/FT230X/FT231X (and FT232H/FT2232H at baud rates up to 3 Mbaud). */
    FTDI,

    /** WCH CH340/CH341. */
    CH34X,

    /** No line settings: plain bulk IN/OUT endpoints of a vendor device (USB printers, custom firmware). */
    RAW_BULK,
}

/** One USB control transfer; returns the transferred byte count, or a negative value on failure. */
@BlueLibInternal
public fun interface UsbControl {
    public fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?): Int
}

/**
 * Chip specific configuration sequences, ported from the Linux kernel drivers (`cdc-acm`, `cp210x`,
 * `ftdi_sio`, `ch341`). Pure functions over [UsbControl] so they are verified without hardware.
 */
@BlueLibInternal
public object UsbSerialProtocol {

    private const val CLASS_INTERFACE_OUT = 0x21
    private const val VENDOR_DEVICE_OUT = 0x40
    private const val VENDOR_INTERFACE_OUT = 0x41
    private const val VENDOR_DEVICE_IN = 0xC0

    /** FTDI packets start with two modem status bytes that are not payload. */
    public const val FTDI_STATUS_BYTES: Int = 2

    /** Picks the driver for a device, or `null` when BlueLib cannot drive it. */
    public fun detect(vendorId: Int, productId: Int, hasCdcDataInterface: Boolean, hasBulkPair: Boolean): UsbSerialDriver? =
        when {
            vendorId == 0x0403 -> UsbSerialDriver.FTDI
            vendorId == 0x10C4 -> UsbSerialDriver.CP210X
            // WCH also ships CDC-ACM chips (CH9102, CH343) under the same vendor id.
            vendorId == 0x1A86 && productId in CH34X_PRODUCT_IDS -> UsbSerialDriver.CH34X
            vendorId == 0x067B -> null // PL2303 needs a vendor init sequence BlueLib does not implement yet.
            hasCdcDataInterface -> UsbSerialDriver.CDC_ACM
            hasBulkPair -> UsbSerialDriver.RAW_BULK
            else -> null
        }

    /**
     * Applies [settings] and raises DTR/RTS (Arduino-class boards only start talking once DTR is set).
     *
     * [index] is the interface number for CDC-ACM and CP210x, and the FTDI port index (0 on single
     * port chips, interface + 1 on multi port chips, flagged by [ftdiMultiPort]).
     *
     * @return `null` on success, otherwise why the device refused.
     */
    public fun configure(
        driver: UsbSerialDriver,
        control: UsbControl,
        settings: SerialSettings,
        index: Int,
        ftdiMultiPort: Boolean = false,
    ): String? {
        val steps = Steps(control)
        when (driver) {
            UsbSerialDriver.RAW_BULK -> Unit
            UsbSerialDriver.CDC_ACM -> {
                steps.run("SET_LINE_CODING", CLASS_INTERFACE_OUT, 0x20, 0, index, cdcLineCoding(settings))
                steps.run("SET_CONTROL_LINE_STATE", CLASS_INTERFACE_OUT, 0x22, 0x0003, index)
            }
            UsbSerialDriver.CP210X -> {
                steps.run("IFC_ENABLE", VENDOR_INTERFACE_OUT, 0x00, 0x0001, index)
                steps.run("SET_BAUDRATE", VENDOR_INTERFACE_OUT, 0x1E, 0, index, littleEndian32(settings.baudRate))
                steps.run("SET_LINE_CTL", VENDOR_INTERFACE_OUT, 0x03, cp210xLineControl(settings), index)
                steps.run("SET_MHS", VENDOR_INTERFACE_OUT, 0x07, 0x0303, index)
            }
            UsbSerialDriver.FTDI -> {
                val divisor = ftdiBaudDivisor(settings.baudRate)
                    ?: return "FTDI cannot run at ${settings.baudRate} baud (supported: 184..3000000)"
                val baudIndex = if (ftdiMultiPort) ((divisor shr 16) shl 8) or index else divisor shr 16
                steps.run("RESET", VENDOR_DEVICE_OUT, 0x00, 0, index)
                steps.run("SET_MODEM_CTRL", VENDOR_DEVICE_OUT, 0x01, 0x0303, index)
                steps.run("SET_FLOW_CTRL", VENDOR_DEVICE_OUT, 0x02, 0, index)
                steps.run("SET_BAUD_RATE", VENDOR_DEVICE_OUT, 0x03, divisor and 0xFFFF, baudIndex)
                steps.run("SET_DATA", VENDOR_DEVICE_OUT, 0x04, ftdiDataValue(settings), index)
            }
            UsbSerialDriver.CH34X -> {
                val baud = ch34xBaudValue(settings.baudRate)
                    ?: return "CH34x cannot run at ${settings.baudRate} baud"
                val version = ByteArray(2)
                if (control.transfer(VENDOR_DEVICE_IN, 0x5F, 0, 0, version) < 0) return "READ_VERSION was rejected by the device"
                val chipVersion = version[0].toInt() and 0xFF
                steps.run("SERIAL_INIT", VENDOR_DEVICE_OUT, 0xA1, 0, 0)
                // Without bit 7, newer chips hold received bytes until a full 32 byte packet is buffered.
                steps.run("WRITE_BAUD", VENDOR_DEVICE_OUT, 0x9A, 0x1312, if (chipVersion > 0x27) baud or 0x80 else baud)
                // Chips before version 0x30 have no LCR register and are fixed at 8N1.
                if (chipVersion >= 0x30) steps.run("WRITE_LCR", VENDOR_DEVICE_OUT, 0x9A, 0x2518, ch34xLcr(settings))
                steps.run("MODEM_CTRL", VENDOR_DEVICE_OUT, 0xA4, 0xFF9F, 0) // active low: DTR + RTS on
            }
        }
        return steps.failure
    }

    /** Copies payload out of a bulk read, dropping [statusBytes] at the start of every [packetSize] packet. */
    public fun stripStatusBytes(data: ByteArray, length: Int, packetSize: Int, statusBytes: Int): ByteArray {
        if (statusBytes == 0) return data.copyOf(length)
        val out = ByteArray(length)
        var written = 0
        var packetStart = 0
        while (packetStart < length) {
            val packetEnd = minOf(packetStart + packetSize, length)
            val payloadStart = packetStart + statusBytes
            if (payloadStart < packetEnd) {
                System.arraycopy(data, payloadStart, out, written, packetEnd - payloadStart)
                written += packetEnd - payloadStart
            }
            packetStart += packetSize
        }
        return out.copyOf(written)
    }

    internal fun cdcLineCoding(settings: SerialSettings): ByteArray =
        littleEndian32(settings.baudRate) + byteArrayOf(
            settings.stopBits.ordinal.toByte(), // ONE=0, ONE_POINT_FIVE=1, TWO=2
            settings.parity.ordinal.toByte(), // NONE, ODD, EVEN, MARK, SPACE = 0..4
            settings.dataBits.toByte(),
        )

    internal fun cp210xLineControl(settings: SerialSettings): Int =
        (settings.dataBits shl 8) or (settings.parity.ordinal shl 4) or settings.stopBits.ordinal

    internal fun ftdiDataValue(settings: SerialSettings): Int =
        settings.dataBits or (settings.parity.ordinal shl 8) or (settings.stopBits.ordinal shl 11)

    /** FT232BM-style divisor on the 3 MHz base clock: 14 integer bits plus an eighths fraction. */
    internal fun ftdiBaudDivisor(baud: Int): Int? {
        if (baud !in 184..3_000_000) return null
        val eighths = ((24_000_000L + baud / 2) / baud).toInt()
        var divisor = (eighths shr 3) or (FTDI_FRACTION_CODES[eighths and 7] shl 14)
        if (divisor == 1) divisor = 0 else if (divisor == 0x4001) divisor = 1 // 3 Mbaud and 2 Mbaud special cases
        return divisor
    }

    /** CH34x prescaler/divisor register value, a direct port of `ch341_get_divisor`. */
    internal fun ch34xBaudValue(requested: Int): Int? {
        val clock = 48_000_000L
        fun clockDivider(prescaler: Int, factor: Int): Long = 1L shl (12 - 3 * prescaler - factor)
        val speed = requested.toLong().coerceIn(46L, 3_000_000L)

        var factor = 1
        var prescaler = 3
        while (prescaler >= 0 && speed <= clock / (clockDivider(prescaler, 1) * 512)) prescaler--
        if (prescaler < 0) return null

        var clockDiv = clockDivider(prescaler, factor)
        var divisor = clock / (clockDiv * speed)
        if (divisor < 9 || divisor > 255) {
            divisor /= 2
            clockDiv *= 2
            factor = 0
        }
        if (divisor < 2) return null
        if (16 * clock / (clockDiv * divisor) - 16 * speed >= 16 * speed - 16 * clock / (clockDiv * (divisor + 1))) divisor++
        if (factor == 1 && divisor % 2 == 0L) {
            divisor /= 2
            factor = 0
        }
        return ((0x100 - divisor.toInt()) shl 8) or (factor shl 2) or prescaler
    }

    internal fun ch34xLcr(settings: SerialSettings): Int {
        val enableRxTx = 0xC0
        val wordLength = settings.dataBits - 5
        val parity = when (settings.parity) {
            Parity.NONE -> 0x00
            Parity.ODD -> 0x08
            Parity.EVEN -> 0x18
            Parity.MARK -> 0x28
            Parity.SPACE -> 0x38
        }
        val stop = if (settings.stopBits == StopBits.ONE) 0 else 0x04
        return enableRxTx or parity or stop or wordLength
    }

    private fun littleEndian32(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private val FTDI_FRACTION_CODES = intArrayOf(0, 3, 2, 4, 1, 5, 6, 7)
    private val CH34X_PRODUCT_IDS = setOf(0x7523, 0x5523, 0x7522, 0xE523)

    private class Steps(private val control: UsbControl) {
        var failure: String? = null

        fun run(name: String, requestType: Int, request: Int, value: Int, index: Int, data: ByteArray? = null) {
            if (failure != null) return
            if (control.transfer(requestType, request, value, index, data) < 0) failure = "$name was rejected by the device"
        }
    }
}
