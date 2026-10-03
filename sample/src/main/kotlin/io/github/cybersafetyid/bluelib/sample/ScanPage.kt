package io.github.cybersafetyid.bluelib.sample

import android.view.LayoutInflater
import android.view.View
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.AutoPairFilter
import io.github.cybersafetyid.bluelib.domain.model.BluetoothAddress
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.BondState
import io.github.cybersafetyid.bluelib.domain.model.ScanMode
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.port.ClassicDiscoveryEvent
import io.github.cybersafetyid.bluelib.port.ScanEvent
import io.github.cybersafetyid.bluelib.sample.databinding.ItemDeviceBinding
import io.github.cybersafetyid.bluelib.sample.databinding.PageScanBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** BLE scanning, Classic discovery, paired devices, manual pairing and auto pairing. */
class ScanPage(private val b: PageScanBinding, private val host: SampleHost) {

    private val blueLib get() = host.blueLib
    private val context get() = b.root.context

    private val devices = linkedMapOf<String, DeviceItem>()
    private val rows = mutableMapOf<String, ItemDeviceBinding>()
    private var bondedAddresses = emptySet<String>()
    private var discoveryJob: Job? = null
    private var bondedJob: Job? = null

    private val source: DeviceSource
        get() = when (b.scanSource.checkedButtonId) {
            b.sourceClassic.id -> DeviceSource.CLASSIC
            b.sourceBonded.id -> DeviceSource.BONDED
            else -> DeviceSource.BLE
        }

    init {
        b.scanSource.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) onSourceChanged() }
        b.btnScan.setOnClickListener { if (discoveryJob?.isActive == true) stopDiscovery() else startDiscovery() }
        b.apUseRssi.setOnCheckedChangeListener { _, _ -> renderRssiLabel() }
        b.apRssi.addOnChangeListener { _, _, _ -> renderRssiLabel() }
        b.btnAutoPair.setOnClickListener { autoPair() }
        b.apHeader.setOnClickListener {
            val open = b.apBody.visibility != View.VISIBLE
            b.apBody.visibility = if (open) View.VISIBLE else View.GONE
        }
        renderRssiLabel()
        renderList()
        watchBondedDevices()
    }

    /** Called when the permission dialog closes, so the paired list can be read now. */
    fun onPermissionsChanged() = watchBondedDevices()

    // --- Discovery --------------------------------------------------------------------------

    private fun onSourceChanged() {
        stopDiscovery()
        clearList()
        b.bleOptions.visibility = if (source == DeviceSource.BLE) View.VISIBLE else View.GONE
        if (source == DeviceSource.BONDED) {
            b.btnScan.visibility = View.GONE
            showBonded()
        } else {
            b.btnScan.visibility = View.VISIBLE
        }
    }

    private fun startDiscovery() {
        when (source) {
            DeviceSource.BLE -> startBleScan()
            DeviceSource.CLASSIC -> startClassicDiscovery()
            else -> Unit
        }
    }

    private fun startBleScan() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.SCAN)) {
            host.askForPermission("Scanning needs the Nearby devices permission.")
            return
        }
        val request = try {
            ScanRequest(
                namePrefix = b.scanNamePrefix.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                scanMode = when (b.scanMode.checkedChipId) {
                    b.modeLowPower.id -> ScanMode.LOW_POWER
                    b.modeBalanced.id -> ScanMode.BALANCED
                    else -> ScanMode.LOW_LATENCY
                },
                autoStopAfterMillis = 20_000L,
            )
        } catch (invalid: BlueLibValidationException) {
            host.say(invalid.message ?: "Invalid scan settings")
            return
        }
        clearList()
        setScanning(true)
        host.log.add("BLE scan started (${request.scanMode}, prefix=${request.namePrefix ?: "-"})")
        discoveryJob = host.scope.launch {
            try {
                blueLib.scan(request).collect { event ->
                    when (event) {
                        is ScanEvent.Observed -> {
                            val o = event.observation
                            upsert(
                                DeviceItem(
                                    id = o.deviceId,
                                    name = o.deviceName,
                                    source = DeviceSource.BLE,
                                    rssi = o.rssi,
                                    connectable = o.isConnectable,
                                    serviceCount = o.serviceUuids.size,
                                ),
                            )
                        }
                        is ScanEvent.Lost -> devices[event.observation.deviceId.address.value]?.let { upsert(it.copy(lost = true)) }
                        is ScanEvent.Failed -> {
                            host.log.add("Scan failed ${event.error.describe()}")
                            host.say(event.error.message)
                        }
                    }
                }
            } finally {
                host.log.add("BLE scan stopped, ${devices.size} devices")
                setScanning(false)
            }
        }
    }

    private fun startClassicDiscovery() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.CLASSIC_DISCOVERY)) {
            host.askForPermission("Classic discovery needs the Nearby devices permission.")
            return
        }
        clearList()
        setScanning(true)
        discoveryJob = host.scope.launch {
            try {
                blueLib.discoverClassic().collect { event ->
                    when (event) {
                        ClassicDiscoveryEvent.Started -> host.log.add("Classic discovery started")
                        is ClassicDiscoveryEvent.DeviceFound -> upsert(
                            DeviceItem(
                                id = event.device.deviceId,
                                name = event.device.name,
                                source = DeviceSource.CLASSIC,
                                rssi = event.rssi,
                                bondState = event.device.bondState,
                            ),
                        )
                        ClassicDiscoveryEvent.Finished -> {
                            host.log.add("Classic discovery finished, ${devices.size} devices")
                            discoveryJob?.cancel()
                        }
                        is ClassicDiscoveryEvent.Failed -> {
                            host.log.add("Classic discovery failed ${event.error.describe()}")
                            host.say(event.error.message)
                        }
                    }
                }
            } finally {
                setScanning(false)
            }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        blueLib.releaseScanner()
        setScanning(false)
    }

    private fun setScanning(active: Boolean) {
        host.busy(active)
        b.btnScan.setText(if (active) R.string.btn_stop_scan else R.string.btn_start_scan)
    }

    // --- Paired devices ---------------------------------------------------------------------

    private fun watchBondedDevices() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.CONNECT)) return
        bondedJob?.cancel()
        bondedJob = host.scope.launch {
            blueLib.bondedDevices()
                .catch { host.log.add("Paired list unavailable: ${it.message}") }
                .collect { list ->
                    bondedAddresses = list.map { it.deviceId.address.value }.toSet()
                    if (source == DeviceSource.BONDED) {
                        showBonded(list.map { DeviceItem(it.deviceId, it.name, DeviceSource.BONDED, bondState = BondState.BONDED) })
                    } else {
                        devices.values.toList().forEach { upsert(it) } // refresh the Pair/Unpair buttons
                    }
                }
        }
    }

    private fun showBonded(list: List<DeviceItem>? = null) {
        if (!blueLib.hasPermissionFor(BluetoothOperation.CONNECT)) {
            host.askForPermission("Reading paired devices needs the Nearby devices permission.")
            return
        }
        if (list == null) {
            watchBondedDevices()
            return
        }
        clearList()
        list.forEach { upsert(it) }
    }

    // --- Pairing ----------------------------------------------------------------------------

    private fun pair(device: DeviceItem) {
        if (!blueLib.hasPermissionFor(BluetoothOperation.CONNECT)) {
            host.askForPermission("Pairing needs the Nearby devices permission.")
            return
        }
        val transport = if (device.source == DeviceSource.BLE) Transport.LE else Transport.BREDR
        host.log.add("Pairing ${device.address} over $transport…")
        upsert(device.copy(bondState = BondState.BONDING))
        host.busy(true)
        host.scope.launch {
            blueLib.bond(device.id, transport)
                .onSuccess {
                    host.log.add("Paired with ${device.address}")
                    host.say("Paired with ${device.displayName}")
                    upsert(device.copy(bondState = BondState.BONDED))
                }
                .onFailure { error ->
                    host.log.add("Pairing failed ${error.describe()}")
                    host.say(error.message)
                    upsert(device.copy(bondState = BondState.NONE))
                }
            host.busy(false)
        }
    }

    private fun unpair(device: DeviceItem) {
        host.scope.launch {
            blueLib.classicPort.unbond(device.id)
                .onSuccess { host.say("Removed bond with ${device.displayName}") }
                .onFailure { error ->
                    host.log.add("Unpair failed ${error.describe()}")
                    // Android has no public unbond API before 16; point to the system settings instead.
                    host.message(error.message, "Settings" to {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    })
                }
        }
    }

    private fun autoPair() {
        val filter = try {
            AutoPairFilter(
                targetAddress = b.apMac.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { BluetoothAddress.parse(it) },
                deviceName = b.apName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                namePrefix = b.apPrefix.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                serviceUuids = listOfNotNull(BluetoothUuid.parseOrNull(b.apService.text?.toString()?.trim()?.takeIf { it.isNotEmpty() })),
                manufacturerId = b.apManufacturer.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseInt),
                minRssi = if (b.apUseRssi.isChecked) b.apRssi.value.toInt() else null,
                transport = when (b.apTransport.checkedButtonId) {
                    b.transportLe.id -> Transport.LE
                    b.transportBredr.id -> Transport.BREDR
                    else -> Transport.AUTO
                },
            )
        } catch (invalid: Exception) {
            b.apResult.text = invalid.message ?: "Invalid filter"
            return
        }
        val needed = if (filter.transport == Transport.BREDR) BluetoothOperation.CLASSIC_DISCOVERY else BluetoothOperation.SCAN
        if (!blueLib.hasPermissionFor(needed) || !blueLib.hasPermissionFor(BluetoothOperation.CONNECT)) {
            host.askForPermission("Auto pair needs the Nearby devices permission.")
            return
        }
        stopDiscovery()
        b.btnAutoPair.isEnabled = false
        b.apResult.setText(R.string.autopair_running)
        host.busy(true)
        host.log.add("Auto pair started: $filter")
        host.scope.launch {
            blueLib.autoPair(filter, timeoutMillis = 30_000L)
                .onSuccess { deviceId ->
                    b.apResult.text = context.getString(R.string.autopair_success, deviceId.address.value)
                    host.log.add("Auto pair succeeded with ${deviceId.address.value}")
                    val known = devices[deviceId.address.value]
                    host.select(DeviceItem(deviceId, known?.name, known?.source ?: DeviceSource.MANUAL, bondState = BondState.BONDED))
                }
                .onFailure { error ->
                    b.apResult.text = error.message
                    host.log.add("Auto pair failed ${error.describe()}")
                }
            b.btnAutoPair.isEnabled = true
            host.busy(false)
        }
    }

    private fun renderRssiLabel() {
        b.apRssi.isEnabled = b.apUseRssi.isChecked
        b.apRssiLabel.text = if (b.apUseRssi.isChecked) {
            context.getString(R.string.autopair_rssi, b.apRssi.value.toInt())
        } else {
            context.getString(R.string.autopair_rssi_off)
        }
    }

    // --- List -------------------------------------------------------------------------------

    private fun clearList() {
        devices.clear()
        rows.clear()
        b.deviceList.removeAllViews()
        renderList()
    }

    private fun upsert(device: DeviceItem) {
        val merged = if (device.address in bondedAddresses && device.bondState == BondState.NONE) {
            device.copy(bondState = BondState.BONDED)
        } else {
            device
        }
        devices[merged.address] = merged
        val row = rows.getOrPut(merged.address) {
            ItemDeviceBinding.inflate(LayoutInflater.from(context), b.deviceList, false).also { b.deviceList.addView(it.root) }
        }
        bindRow(row, merged)
        renderList()
    }

    private fun bindRow(row: ItemDeviceBinding, device: DeviceItem) {
        row.deviceName.text = device.displayName
        row.deviceAddress.text = device.address
        row.deviceRssi.text = device.rssi?.let { context.getString(R.string.rssi_dbm, it) }.orEmpty()
        row.deviceMeta.text = buildList {
            add(device.source.label)
            if (device.source == DeviceSource.BLE) add(if (device.connectable) "connectable" else "not connectable")
            if (device.serviceCount > 0) add("${device.serviceCount} services")
            if (device.source != DeviceSource.BONDED) {
                add(
                    when (device.bondState) {
                        BondState.BONDED -> "paired"
                        BondState.BONDING -> "pairing…"
                        BondState.NONE -> "not paired"
                    },
                )
            }
            if (device.lost) add("out of range")
        }.joinToString(" · ")
        row.root.alpha = if (device.lost) 0.5f else 1f
        row.devicePair.isEnabled = device.bondState != BondState.BONDING
        row.devicePair.setText(if (device.bondState == BondState.BONDED) R.string.btn_unpair else R.string.btn_pair)
        row.devicePair.setOnClickListener { if (device.bondState == BondState.BONDED) unpair(device) else pair(device) }
        row.deviceUse.setOnClickListener {
            stopDiscovery() // scanning while connecting slows the connection down on most controllers
            host.select(device)
            host.openConnectPage()
        }
    }

    private fun renderList() {
        b.deviceCount.text = context.getString(R.string.devices_count, devices.size)
        b.deviceEmpty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun parseInt(text: String): Int =
        if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toInt(16) else text.toInt()
}
