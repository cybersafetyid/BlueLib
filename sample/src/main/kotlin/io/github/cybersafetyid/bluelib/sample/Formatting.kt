package io.github.cybersafetyid.bluelib.sample

import io.github.cybersafetyid.bluelib.domain.codec.DataCodec
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.GattProperty
import io.github.cybersafetyid.bluelib.port.DiagnosticEvent

/** Payload format chosen in a terminal or a write field. */
enum class PayloadFormat { TEXT, HEX, BASE64 }

/** Nordic UART Service: the de-facto "serial port over BLE", used by the sample's GATT server. */
object NordicUart {
    val SERVICE: BluetoothUuid = BluetoothUuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val RX: BluetoothUuid = BluetoothUuid.parse("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val TX: BluetoothUuid = BluetoothUuid.parse("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
}

private val KNOWN_UUIDS = mapOf(
    0x1800 to "Generic Access",
    0x1801 to "Generic Attribute",
    0x180A to "Device Information",
    0x180D to "Heart Rate",
    0x180F to "Battery",
    0x1810 to "Blood Pressure",
    0x1816 to "Cycling Speed and Cadence",
    0x181D to "Weight Scale",
    0x2A00 to "Device Name",
    0x2A01 to "Appearance",
    0x2A04 to "Connection Parameters",
    0x2A05 to "Service Changed",
    0x2A19 to "Battery Level",
    0x2A24 to "Model Number",
    0x2A25 to "Serial Number",
    0x2A26 to "Firmware Revision",
    0x2A27 to "Hardware Revision",
    0x2A28 to "Software Revision",
    0x2A29 to "Manufacturer Name",
    0x2A37 to "Heart Rate Measurement",
    0x2A9D to "Weight Measurement",
)

/** Human name for well known UUIDs, `0xABCD` for other SIG UUIDs, the full UUID otherwise. */
fun BluetoothUuid.label(): String = when (this) {
    NordicUart.SERVICE -> "Nordic UART Service"
    NordicUart.RX -> "UART RX (write)"
    NordicUart.TX -> "UART TX (notify)"
    else -> shortValue?.let { KNOWN_UUIDS[it] ?: "0x%04X".format(it) } ?: toString()
}

fun propertyNames(properties: Int): String = buildList {
    if (properties and GattProperty.READ != 0) add("READ")
    if (properties and GattProperty.WRITE != 0) add("WRITE")
    if (properties and GattProperty.WRITE_NO_RESPONSE != 0) add("WRITE_NR")
    if (properties and GattProperty.NOTIFY != 0) add("NOTIFY")
    if (properties and GattProperty.INDICATE != 0) add("INDICATE")
}.joinToString(" · ")

fun BlueLibError.describe(): String = "[$code] $message"

/** `48 65 6C 6C 6F  "Hello"` — hex plus printable text. */
fun ByteArray.preview(): String {
    if (isEmpty()) return "(empty)"
    val text = String(this, Charsets.UTF_8).map { if (it.isISOControl() && it != '\n') '·' else it }.joinToString("")
    return "${DataCodec.decodeHex(this, " ")}  \"$text\""
}

fun ByteArray.render(format: PayloadFormat): String = when (format) {
    PayloadFormat.TEXT -> DataCodec.decodeText(this).trimEnd('\r', '\n')
    PayloadFormat.HEX -> DataCodec.decodeHex(this, " ")
    PayloadFormat.BASE64 -> DataCodec.encodeBase64(this)
}

/** Parses a write field: `0x01A2` (or `01 A2` after `0x`) as hex, anything else as UTF-8 text. */
fun parseWriteValue(input: String): ByteArray {
    val trimmed = input.trim()
    return if (trimmed.startsWith("0x", ignoreCase = true)) {
        DataCodec.encodeHex(trimmed.substring(2).replace(" ", ""))
    } else {
        DataCodec.encodeText(input)
    }
}

/** One-line description of a diagnostic event for the log. */
fun DiagnosticEvent.summary(): String = when (this) {
    is DiagnosticEvent.AdapterStateChanged -> "adapter $previous → $current"
    is DiagnosticEvent.ConnectionStateChanged -> "${deviceId.address.value} $previous → $current"
    is DiagnosticEvent.OperationStarted -> "▸ $operation ${deviceId?.address?.value.orEmpty()}"
    is DiagnosticEvent.OperationFinished -> "◂ $operation ${if (success) "ok" else "failed"} in ${durationMillis}ms"
    is DiagnosticEvent.ErrorReported -> "error ${error.describe()}"
    is DiagnosticEvent.ScanThrottled -> "scan throttled, retry in ${retryAfterMillis}ms"
    is DiagnosticEvent.CapabilityProbed -> "capability ${feature.name}=${if (supported) "yes" else "no"}"
}
