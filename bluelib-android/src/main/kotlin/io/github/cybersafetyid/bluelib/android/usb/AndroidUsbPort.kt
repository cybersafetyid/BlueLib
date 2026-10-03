package io.github.cybersafetyid.bluelib.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.github.cybersafetyid.bluelib.BlueLibInternal
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.SerialSettings
import io.github.cybersafetyid.bluelib.port.ByteConnection
import io.github.cybersafetyid.bluelib.transport.StreamConnection
import io.github.cybersafetyid.bluelib.transport.UsbControl
import io.github.cybersafetyid.bluelib.transport.UsbSerialDriver
import io.github.cybersafetyid.bluelib.transport.UsbSerialProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume

/** A USB device attached to the phone, as seen through USB host mode (OTG). */
public data class UsbDeviceInfo(
    /** Stable for as long as the device stays attached, e.g. `/dev/bus/usb/001/002`. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val manufacturerName: String?,
    /** Driver BlueLib would use, or `null` when it cannot talk to this device. */
    val driver: UsbSerialDriver?,
    /** `true` when the user already granted access to this device. */
    val hasPermission: Boolean,
)

/**
 * USB host adapter: USB serial cables (RS-232/RS-485/TTL), Arduino-class boards and raw bulk devices.
 *
 * No root and no kernel drivers: the chip protocols live in [UsbSerialProtocol] and run over
 * `UsbDeviceConnection` control and bulk transfers.
 */
@OptIn(BlueLibInternal::class)
public class AndroidUsbPort(
    private val context: Context,
    private val diagnostics: AndroidDiagnostics,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** USB devices attached right now; empty when the device has no USB host support. */
    public fun devices(): List<UsbDeviceInfo> = usbManager?.deviceList?.values.orEmpty().map { it.toInfo() }

    /**
     * Opens [deviceName] with [settings], asking the user for access first when needed.
     *
     * [driver] overrides auto detection, e.g. to force [UsbSerialDriver.CDC_ACM] on a clone chip.
     */
    public suspend fun open(
        deviceName: String,
        settings: SerialSettings = SerialSettings(),
        driver: UsbSerialDriver? = null,
    ): BlueLibResult<ByteConnection> {
        val endpoint = "usb:$deviceName"
        val manager = usbManager ?: return failureOf(BlueLibError.LinkFailed(endpoint, "this device has no USB host support", isRetryable = false))
        val device = manager.deviceList[deviceName]
            ?: return failureOf(BlueLibError.LinkFailed(endpoint, "device is not attached"))
        val chosen = driver ?: device.toInfo().driver
            ?: return failureOf(BlueLibError.LinkFailed(endpoint, "no driver for ${device.vendorId.hex()}:${device.productId.hex()}", isRetryable = false))

        if (!manager.hasPermission(device) && !requestPermission(manager, device)) {
            return failureOf(BlueLibError.LinkFailed(endpoint, "the user denied USB access"))
        }

        val layout = InterfaceLayout.of(device, chosen)
            ?: return failureOf(BlueLibError.LinkFailed(endpoint, "no bulk IN/OUT endpoint pair for $chosen", isRetryable = false))

        diagnostics.operationStarted("usb.open")
        return withContext(ioDispatcher) {
            val usb = manager.openDevice(device)
                ?: return@withContext failureOf(BlueLibError.LinkFailed(endpoint, "openDevice returned null"))
            if (!layout.interfaces.all { usb.claimInterface(it, true) }) {
                usb.close()
                return@withContext failureOf(BlueLibError.LinkFailed(endpoint, "interface is claimed by another app or driver"))
            }
            val control = UsbControl { type, request, value, index, data ->
                usb.controlTransfer(type, request, value, index, data, data?.size ?: 0, CONTROL_TIMEOUT_MS)
            }
            val failure = UsbSerialProtocol.configure(chosen, control, settings, layout.configIndex, layout.ftdiMultiPort)
            if (failure != null) {
                layout.interfaces.forEach { usb.releaseInterface(it) }
                usb.close()
                val error = BlueLibError.LinkFailed(endpoint, failure, isRetryable = false)
                diagnostics.error(error)
                return@withContext failureOf(error)
            }
            successOf(
                StreamConnection(
                    endpoint = endpoint,
                    input = UsbBulkInputStream(
                        usb = usb,
                        endpoint = layout.inEndpoint,
                        statusBytes = if (chosen == UsbSerialDriver.FTDI) UsbSerialProtocol.FTDI_STATUS_BYTES else 0,
                        isAttached = { manager.deviceList.containsKey(deviceName) },
                    ),
                    output = UsbBulkOutputStream(usb, layout.outEndpoint),
                    scope = scope,
                    ioDispatcher = ioDispatcher,
                    onError = { diagnostics.error(it) },
                    release = {
                        layout.interfaces.forEach { runCatching { usb.releaseInterface(it) } }
                        usb.close()
                    },
                ),
            )
        }
    }

    private suspend fun requestPermission(manager: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { continuation ->
            val action = "${context.packageName}.bluelib.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                @Suppress("DEPRECATION")
                override fun onReceive(context: Context, intent: Intent) {
                    val answered = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.action != action || answered?.deviceName != device.deviceName) return
                    runCatching { context.unregisterReceiver(this) }
                    if (continuation.isActive) continuation.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            if (ApiLevel.requiresReceiverExportFlag()) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(action))
            }
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // The system adds the device and the answer as extras, so the intent must be mutable (API 31+),
            // and a mutable PendingIntent must be explicit (API 34+).
            val flags = if (ApiLevel.isAtLeast(31)) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(action).setPackage(context.packageName)
            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }

    private fun UsbDevice.toInfo(): UsbDeviceInfo {
        val interfaces = (0 until interfaceCount).map { getInterface(it) }
        return UsbDeviceInfo(
            deviceName = deviceName,
            vendorId = vendorId,
            productId = productId,
            productName = runCatching { productName }.getOrNull(),
            manufacturerName = runCatching { manufacturerName }.getOrNull(),
            driver = UsbSerialProtocol.detect(
                vendorId = vendorId,
                productId = productId,
                hasCdcDataInterface = interfaces.any { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA },
                hasBulkPair = interfaces.any { bulkPair(it) != null },
            ),
            hasPermission = usbManager?.hasPermission(this) == true,
        )
    }

    /** Which interfaces to claim and which endpoints carry data, per driver. */
    private class InterfaceLayout(
        val interfaces: List<UsbInterface>,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
        val configIndex: Int,
        val ftdiMultiPort: Boolean,
    ) {
        companion object {
            fun of(device: UsbDevice, driver: UsbSerialDriver): InterfaceLayout? {
                val all = (0 until device.interfaceCount).map { device.getInterface(it) }
                // ponytail: always the first port of multi-port chips (FT2232, CP2105); add a port index when needed.
                val data = if (driver == UsbSerialDriver.CDC_ACM) {
                    all.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && bulkPair(it) != null }
                } else {
                    all.firstOrNull { bulkPair(it) != null }
                } ?: return null
                val (input, output) = bulkPair(data) ?: return null
                val cdcControl = all.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
                val multiPort = device.interfaceCount > 1
                return InterfaceLayout(
                    interfaces = listOfNotNull(cdcControl.takeIf { driver == UsbSerialDriver.CDC_ACM }, data),
                    inEndpoint = input,
                    outEndpoint = output,
                    configIndex = when (driver) {
                        UsbSerialDriver.CDC_ACM -> (cdcControl ?: data).id
                        UsbSerialDriver.FTDI -> if (multiPort) data.id + 1 else 0
                        else -> data.id
                    },
                    ftdiMultiPort = driver == UsbSerialDriver.FTDI && multiPort,
                )
            }
        }
    }

    private companion object {
        const val CONTROL_TIMEOUT_MS = 1_000

        fun bulkPair(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            val endpoints = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }
                .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
            return input to output
        }

        fun Int.hex(): String = "0x%04X".format(this)
    }
}

/**
 * Bulk IN endpoint as an `InputStream`, so USB links reuse [StreamConnection]'s read loop.
 *
 * Callers must read at least one packet ([UsbEndpoint.getMaxPacketSize]) at a time; StreamConnection
 * reads 4 KiB.
 */
@OptIn(BlueLibInternal::class)
internal class UsbBulkInputStream(
    private val usb: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
    private val statusBytes: Int,
    private val isAttached: () -> Boolean,
) : InputStream() {
    @Volatile
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(endpoint.maxPacketSize)
        val read = read(one, 0, one.size)
        return if (read < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val packetSize = endpoint.maxPacketSize
        // Whole packets only, capped at the 16 KiB per-transfer limit of Android before 9.
        val transfer = (minOf(len, MAX_TRANSFER_BYTES) / packetSize * packetSize).coerceAtLeast(packetSize)
        val scratch = ByteArray(transfer)
        while (!closed) {
            val read = usb.bulkTransfer(endpoint, scratch, transfer, READ_TIMEOUT_MS)
            if (read > 0) {
                val payload = UsbSerialProtocol.stripStatusBytes(scratch, read, packetSize, statusBytes)
                if (payload.isEmpty()) continue
                System.arraycopy(payload, 0, b, off, payload.size)
                return payload.size
            }
            // ponytail: bulkTransfer reports both timeout and unplug as -1, so an idle link polls the device
            // list every READ_TIMEOUT_MS; switch to UsbRequest.queue if that ever shows up in traces.
            if (read < 0 && !isAttached()) throw IOException("device was unplugged")
        }
        return -1
    }

    override fun close() {
        closed = true
    }
}

/** Bulk OUT endpoint as an `OutputStream`. */
internal class UsbBulkOutputStream(
    private val usb: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        val end = off + len
        while (offset < end) {
            val written = usb.bulkTransfer(endpoint, b, offset, minOf(MAX_TRANSFER_BYTES, end - offset), WRITE_TIMEOUT_MS)
            if (written <= 0) throw IOException("USB write timed out after $WRITE_TIMEOUT_MS ms")
            offset += written
        }
    }
}

private const val READ_TIMEOUT_MS = 200
private const val WRITE_TIMEOUT_MS = 5_000
private const val MAX_TRANSFER_BYTES = 16_384
