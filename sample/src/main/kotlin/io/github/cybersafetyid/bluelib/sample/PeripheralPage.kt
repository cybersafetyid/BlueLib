package io.github.cybersafetyid.bluelib.sample

import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.codec.DataCodec
import io.github.cybersafetyid.bluelib.domain.error.BlueLibException
import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.AdvertiseData
import io.github.cybersafetyid.bluelib.domain.model.AdvertiseMode
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingHandle
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingParameters
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingRequest
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.CharacteristicDefinition
import io.github.cybersafetyid.bluelib.domain.model.DescriptorDefinition
import io.github.cybersafetyid.bluelib.domain.model.GattPermission
import io.github.cybersafetyid.bluelib.domain.model.GattProperty
import io.github.cybersafetyid.bluelib.domain.model.GattServerConfig
import io.github.cybersafetyid.bluelib.domain.model.GattServerRequest
import io.github.cybersafetyid.bluelib.domain.model.ServiceDefinition
import io.github.cybersafetyid.bluelib.domain.model.TxPowerLevel
import io.github.cybersafetyid.bluelib.port.GattServer
import io.github.cybersafetyid.bluelib.sample.databinding.PagePeripheralBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Peripheral role: LE advertising and a GATT server exposing the Nordic UART Service, so a second
 * phone running this sample can find, connect and chat with this one.
 */
class PeripheralPage(private val b: PagePeripheralBinding, private val host: SampleHost) {

    private val blueLib get() = host.blueLib
    private val context get() = b.root.context

    private var advertising: AdvertisingHandle? = null
    private var server: GattServer? = null
    private val serverJobs = mutableListOf<Job>()
    private val requestLines = ArrayDeque<String>()

    init {
        b.btnAdvertiseStart.setOnClickListener { startAdvertising() }
        b.btnAdvertiseStop.setOnClickListener { stopAdvertising() }
        b.btnServerOpen.setOnClickListener { openServer() }
        b.btnServerClose.setOnClickListener { closeServer() }
        b.btnServerNotify.setOnClickListener { notifyAll(b.serverNotifyValue.text?.toString().orEmpty()) }
    }

    // --- Advertising ------------------------------------------------------------------------

    private fun startAdvertising() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.ADVERTISE)) {
            host.askForPermission("Advertising needs the Nearby devices permission.")
            return
        }
        if (!blueLib.isAdvertiserAvailable) {
            host.say("This device cannot act as a BLE peripheral.")
            return
        }
        val uuidText = b.advertiseUuid.text?.toString()?.trim().orEmpty()
        val serviceUuid = if (uuidText.isEmpty()) null else BluetoothUuid.parseOrNull(uuidText)
        if (uuidText.isNotEmpty() && serviceUuid == null) {
            host.say("'$uuidText' is not a UUID")
            return
        }
        val extended = b.advertiseExtended.isChecked
        val withName = b.advertiseName.isChecked
        // A 128-bit UUID (18 bytes) plus a device name rarely fits 31 legacy bytes: the name goes into
        // the scan response there. Extended sets have no scan response but 1650 bytes of room.
        val nameInScanResponse = withName && !extended
        val request = try {
            AdvertisingRequest(
                advertiseData = AdvertiseData(
                    serviceUuids = listOfNotNull(serviceUuid),
                    includeDeviceName = withName && extended,
                ),
                scanResponse = if (nameInScanResponse) AdvertiseData(includeDeviceName = true) else null,
                parameters = AdvertisingParameters(
                    scannable = nameInScanResponse,
                    connectable = b.advertiseConnectable.isChecked,
                    mode = when (b.advertiseMode.checkedChipId) {
                        b.advModeLowPower.id -> AdvertiseMode.LOW_POWER
                        b.advModeLatency.id -> AdvertiseMode.LOW_LATENCY
                        else -> AdvertiseMode.BALANCED
                    },
                    txPowerLevel = when (b.advertisePower.checkedChipId) {
                        b.powerUltraLow.id -> TxPowerLevel.ULTRA_LOW
                        b.powerLow.id -> TxPowerLevel.LOW
                        b.powerHigh.id -> TxPowerLevel.HIGH
                        else -> TxPowerLevel.MEDIUM
                    },
                    useExtendedAdvertising = extended,
                ),
            )
        } catch (invalid: BlueLibValidationException) {
            host.say(invalid.message ?: "Invalid advertising data")
            return
        }
        b.btnAdvertiseStart.isEnabled = false
        host.scope.launch {
            try {
                val handle = blueLib.startAdvertising(request)
                advertising = handle
                b.advertiseStatus.text = context.getString(
                    R.string.advertise_running,
                    handle.setId,
                    if (handle.isExtended) "extended" else "legacy",
                    handle.txPowerDbm?.let { "$it dBm" } ?: "default",
                )
                b.btnAdvertiseStop.isEnabled = true
                host.log.add("Advertising started: $handle")
            } catch (failure: BlueLibException) {
                host.log.add("Advertising failed ${failure.error.describe()}")
                host.say(failure.error.message)
                b.btnAdvertiseStart.isEnabled = true
            } catch (invalid: BlueLibValidationException) {
                // The payload budget is checked against this controller's limit: e.g. a 128-bit UUID plus
                // a long device name does not fit a 31 byte legacy advertisement.
                host.say(invalid.message ?: "Advertising payload too large")
                b.btnAdvertiseStart.isEnabled = true
            }
        }
    }

    private fun stopAdvertising() {
        val handle = advertising ?: return
        host.scope.launch {
            blueLib.stopAdvertising(handle)
            advertising = null
            b.advertiseStatus.setText(R.string.advertise_idle)
            b.btnAdvertiseStart.isEnabled = true
            b.btnAdvertiseStop.isEnabled = false
            host.log.add("Advertising stopped")
        }
    }

    // --- GATT server ------------------------------------------------------------------------

    private fun openServer() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.GATT_SERVER)) {
            host.askForPermission("The GATT server needs the Nearby devices permission.")
            return
        }
        b.btnServerOpen.isEnabled = false
        host.scope.launch {
            blueLib.openGattServer(nordicUartConfig())
                .onSuccess { opened ->
                    server = opened
                    host.log.add("GATT server open with the Nordic UART Service")
                    b.btnServerClose.isEnabled = true
                    b.btnServerNotify.isEnabled = true
                    serverJobs += host.scope.launch {
                        opened.connections.collect { list ->
                            b.serverStatus.text = context.getString(
                                R.string.server_open,
                                list.size,
                                list.joinToString { "${it.deviceId.address.value} (MTU ${it.mtu})" }.ifEmpty { "-" },
                            )
                        }
                    }
                    serverJobs += host.scope.launch { opened.requests.collect(::onRequest) }
                }
                .onFailure { error ->
                    host.say(error.message)
                    b.btnServerOpen.isEnabled = true
                }
        }
    }

    /** Requests are answered automatically (autoRespond); this logs them and echoes RX writes on TX. */
    private suspend fun onRequest(request: GattServerRequest) {
        val who = request.deviceId.address.value
        when (request) {
            is GattServerRequest.WriteCharacteristic -> {
                val text = DataCodec.decodeText(request.value)
                appendRequest("$who wrote ${request.characteristic.label()}: ${request.value.preview()}")
                if (request.characteristic == NordicUart.RX) {
                    server?.notify(request.deviceId, NordicUart.SERVICE, NordicUart.TX, DataCodec.encodeText("ECHO: $text"))
                        ?.onSuccess { appendRequest("echoed to $who") }
                        ?.onFailure { appendRequest("echo skipped: ${it.message}") }
                }
            }
            is GattServerRequest.ReadCharacteristic -> appendRequest("$who read ${request.characteristic.label()}")
            // CCCD writes (subscribe/unsubscribe) are handled by BlueLib and never reach this flow.
            is GattServerRequest.WriteDescriptor -> appendRequest("$who wrote a descriptor of ${request.characteristic.label()}")
            else -> appendRequest("$who ${request::class.simpleName}")
        }
    }

    private fun notifyAll(text: String) {
        val current = server ?: return
        val targets = current.connections.value
        if (targets.isEmpty()) {
            host.say("No central is connected yet.")
            return
        }
        host.scope.launch {
            targets.forEach { connection ->
                current.notify(connection.deviceId, NordicUart.SERVICE, NordicUart.TX, DataCodec.encodeText(text))
                    .onSuccess { appendRequest("notified ${connection.deviceId.address.value}: $text") }
                    .onFailure { appendRequest("notify failed: ${it.describe()}") }
            }
        }
    }

    private fun closeServer() {
        serverJobs.forEach { it.cancel() }
        serverJobs.clear()
        server?.close()
        server = null
        b.serverStatus.setText(R.string.server_closed)
        b.btnServerOpen.isEnabled = true
        b.btnServerClose.isEnabled = false
        b.btnServerNotify.isEnabled = false
        host.log.add("GATT server closed")
    }

    private fun appendRequest(line: String) {
        host.log.add("Server: $line")
        requestLines.addFirst(line)
        while (requestLines.size > 12) requestLines.removeLast()
        b.serverRequests.text = requestLines.joinToString("\n")
    }

    fun close() {
        closeServer()
        advertising?.let { handle -> host.scope.launch { blueLib.stopAdvertising(handle) } }
    }

    private fun nordicUartConfig() = GattServerConfig(
        services = listOf(
            ServiceDefinition(
                uuid = NordicUart.SERVICE,
                characteristics = listOf(
                    CharacteristicDefinition(
                        uuid = NordicUart.RX,
                        properties = GattProperty.WRITE or GattProperty.WRITE_NO_RESPONSE,
                        permissions = GattPermission.WRITE,
                    ),
                    CharacteristicDefinition(
                        uuid = NordicUart.TX,
                        properties = GattProperty.NOTIFY or GattProperty.READ,
                        permissions = GattPermission.READ,
                        value = DataCodec.encodeText("BlueLib"),
                        descriptors = listOf(
                            DescriptorDefinition(
                                uuid = BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION,
                                permissions = GattPermission.READ or GattPermission.WRITE,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}
