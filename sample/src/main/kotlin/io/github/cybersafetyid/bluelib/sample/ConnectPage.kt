package io.github.cybersafetyid.bluelib.sample

import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import com.google.android.material.chip.Chip
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.ConnectionPriority
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import io.github.cybersafetyid.bluelib.domain.model.GattCharacteristicInfo
import io.github.cybersafetyid.bluelib.domain.model.GattProfile
import io.github.cybersafetyid.bluelib.domain.model.GattProperty
import io.github.cybersafetyid.bluelib.domain.model.GattServiceInfo
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.PhyCoding
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import io.github.cybersafetyid.bluelib.port.GattConnectRequest
import io.github.cybersafetyid.bluelib.port.GattSession
import io.github.cybersafetyid.bluelib.port.SocketSettings
import io.github.cybersafetyid.bluelib.sample.databinding.ItemCharacteristicBinding
import io.github.cybersafetyid.bluelib.sample.databinding.ItemServiceHeaderBinding
import io.github.cybersafetyid.bluelib.sample.databinding.PageConnectBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** GATT client and Bluetooth Classic sockets for the selected device, plus a messenger terminal. */
class ConnectPage(private val b: PageConnectBinding, private val host: SampleHost) {

    private val blueLib get() = host.blueLib
    private val context get() = b.root.context
    private val terminal = TerminalController(b.terminal, host)

    private var session: GattSession? = null
    private val sessionJobs = mutableListOf<Job>()
    private val subscriptions = mutableMapOf<String, Job>()

    /** `true` while the terminal talks to a GATT characteristic rather than a Classic socket. */
    private var terminalOnGatt = false

    private val target: BluetoothDeviceId? get() = host.selected.value?.id

    init {
        host.scope.launch {
            host.selected.collect { device ->
                b.targetName.text = device?.displayName ?: context.getString(R.string.target_none)
                b.targetAddress.text = device?.let { "${it.address} · ${it.source.label}" } ?: context.getString(R.string.target_hint)
            }
        }
        b.manualMac.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) selectManual()
            false
        }
        b.btnGattConnect.setOnClickListener { connectGatt() }
        b.btnGattDisconnect.setOnClickListener { disconnectGatt() }
        b.btnDiscover.setOnClickListener { discover() }
        b.gattPriority.setOnCheckedStateChangeListener { _, ids -> ids.firstOrNull()?.let(::requestPriority) }
        b.btnReadPhy.setOnClickListener { readPhy() }
        b.btnPhy2m.setOnClickListener { requestPhy(Phy.LE_2M, null) }
        b.btnPhyCoded.setOnClickListener { requestPhy(Phy.LE_CODED, PhyCoding.S8) }
        b.btnRfcomm.setOnClickListener { connectRfcomm() }
        b.btnL2cap.setOnClickListener { connectL2cap() }
        setGattControls(connected = false)
        terminal.onClosed = { terminalOnGatt = false }
    }

    private fun selectManual() {
        val text = b.manualMac.text?.toString()?.trim().orEmpty()
        runCatching { BluetoothDeviceId.of(text) }
            .onSuccess { host.select(DeviceItem(it, null, DeviceSource.MANUAL)) }
            .onFailure { host.say("'$text' is not a MAC address (AA:BB:CC:DD:EE:FF)") }
    }

    private fun requireTarget(operation: BluetoothOperation): BluetoothDeviceId? {
        val id = target
        if (id == null) {
            host.say("Pick a device on the Scan tab or type its MAC address first.")
            return null
        }
        if (!blueLib.hasPermissionFor(operation)) {
            host.askForPermission("Connecting needs the Nearby devices permission.")
            return null
        }
        return id
    }

    // --- GATT client ------------------------------------------------------------------------

    private fun connectGatt() {
        val id = requireTarget(BluetoothOperation.CONNECT) ?: return
        disconnectGatt()
        b.gattState.text = context.getString(R.string.gatt_connecting, id.address.value)
        b.btnGattConnect.isEnabled = false
        host.busy(true)
        host.scope.launch {
            val request = GattConnectRequest(
                autoConnect = b.gattAutoConnect.isChecked,
                mtu = blueLib.config.defaultMtu,
                timeoutMillis = blueLib.config.connectTimeoutMillis,
            )
            blueLib.connect(id, request)
                .onSuccess { attachSession(it) }
                .onFailure { error ->
                    host.log.add("GATT connect failed ${error.describe()}")
                    b.gattState.text = error.message
                    b.btnGattConnect.isEnabled = true
                }
            host.busy(false)
        }
    }

    private fun attachSession(newSession: GattSession) {
        session = newSession
        host.log.add("GATT connected to ${newSession.deviceId.address.value}")
        setGattControls(connected = true)
        sessionJobs += host.scope.launch {
            combine(newSession.state, newSession.mtu) { state, mtu -> state to mtu }.collect { (state, mtu) ->
                b.gattState.text = context.getString(R.string.gatt_state, state.name, mtu)
                if (state == ConnectionState.CLOSED) setGattControls(connected = false)
            }
        }
        sessionJobs += host.scope.launch {
            newSession.profile.collect { profile -> profile?.let(::renderProfile) }
        }
    }

    private fun disconnectGatt() {
        val current = session ?: return
        if (terminalOnGatt) terminal.close()
        subscriptions.values.forEach { it.cancel() }
        subscriptions.clear()
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        current.close()
        session = null
        b.serviceList.removeAllViews()
        b.gattState.setText(R.string.gatt_disconnected)
        host.log.add("GATT disconnected from ${current.deviceId.address.value}")
        setGattControls(connected = false)
    }

    private fun setGattControls(connected: Boolean) {
        b.btnGattConnect.isEnabled = !connected
        b.btnGattDisconnect.isEnabled = connected
        b.btnDiscover.isEnabled = connected
        listOf(b.btnReadPhy, b.btnPhy2m, b.btnPhyCoded).forEach { it.isEnabled = connected }
        for (i in 0 until b.gattPriority.childCount) b.gattPriority.getChildAt(i).isEnabled = connected
    }

    private fun discover() {
        val current = session ?: return
        host.busy(true)
        host.scope.launch {
            current.discoverServices()
                .onSuccess { host.log.add("Discovered ${it.services.size} services") }
                .onFailure { host.say(it.message) }
            host.busy(false)
        }
    }

    private fun requestPriority(chipId: Int) {
        val current = session ?: return
        val priority = when (chipId) {
            b.priorityLow.id -> ConnectionPriority.LOW
            b.priorityHigh.id -> ConnectionPriority.HIGH
            else -> ConnectionPriority.BALANCED
        }
        host.scope.launch {
            current.requestConnectionPriority(priority)
                .onSuccess { host.log.add("Connection priority → $priority") }
                .onFailure { host.say(it.message) }
        }
    }

    private fun readPhy() {
        val current = session ?: return
        host.scope.launch {
            current.readPhy()
                .onSuccess { host.say("PHY (tx, rx): ${it.joinToString()}") }
                .onFailure { host.say(it.message) }
        }
    }

    private fun requestPhy(phy: Phy, coding: PhyCoding?) {
        val current = session ?: return
        host.scope.launch {
            current.requestPhy(phy, coding)
                .onSuccess { host.say("PHY switched to $it") }
                .onFailure { host.say(it.message) }
        }
    }

    private fun renderProfile(profile: GattProfile) {
        b.serviceList.removeAllViews()
        val inflater = LayoutInflater.from(context)
        profile.services.forEach { service ->
            ItemServiceHeaderBinding.inflate(inflater, b.serviceList, true).root.text =
                context.getString(R.string.service_header, service.uuid.label(), service.characteristics.size)
            service.characteristics.forEach { characteristic ->
                val row = ItemCharacteristicBinding.inflate(inflater, b.serviceList, true)
                bindCharacteristic(row, service, characteristic)
            }
        }
    }

    private fun bindCharacteristic(row: ItemCharacteristicBinding, service: GattServiceInfo, characteristic: GattCharacteristicInfo) {
        row.charName.text = characteristic.uuid.label()
        row.charMeta.text = context.getString(R.string.characteristic_meta, characteristic.uuid.toString(), propertyNames(characteristic.properties))
        fun action(label: String, checkable: Boolean = false, onClick: (Chip) -> Unit) {
            val chip = Chip(context).apply {
                text = label
                isCheckable = checkable
                setOnClickListener { onClick(this) }
            }
            row.charActions.addView(chip)
        }
        val key = "${service.uuid}/${characteristic.uuid}/${characteristic.instanceId}"
        if (characteristic.isReadable) action("Read") { read(row, service.uuid, characteristic.uuid) }
        if (characteristic.isWritable) action("Write") { write(service.uuid, characteristic) }
        if (characteristic.isSubscribable) {
            action(if (GattProperty.isNotifiable(characteristic.properties)) "Notify" else "Indicate", checkable = true) { chip ->
                if (chip.isChecked) subscribe(key, row, service.uuid, characteristic.uuid) else subscriptions.remove(key)?.cancel()
            }
        }
        if (characteristic.isWritable || characteristic.isSubscribable) {
            action("Messenger") { useAsMessenger(service.uuid, characteristic) }
        }
    }

    private fun read(row: ItemCharacteristicBinding, service: BluetoothUuid, characteristic: BluetoothUuid) {
        val current = session ?: return
        host.scope.launch {
            current.read(service, characteristic)
                .onSuccess { showValue(row, it) }
                .onFailure { host.say(it.message) }
        }
    }

    private fun write(service: BluetoothUuid, characteristic: GattCharacteristicInfo) {
        val current = session ?: return
        val value = runCatching { parseWriteValue(b.gattWriteValue.text?.toString().orEmpty()) }
            .getOrElse { host.say("Invalid value: ${it.message}"); return }
        host.scope.launch {
            current.write(service, characteristic.uuid, value, writeModeFor(characteristic))
                .onSuccess { host.log.add("Wrote ${value.preview()} to ${characteristic.uuid.label()}") }
                .onFailure { host.say(it.message) }
        }
    }

    private fun subscribe(key: String, row: ItemCharacteristicBinding, service: BluetoothUuid, characteristic: BluetoothUuid) {
        val current = session ?: return
        subscriptions[key] = host.scope.launch {
            try {
                current.subscribe(service, characteristic).collect { showValue(row, it) }
            } catch (failure: Exception) {
                if (failure !is kotlinx.coroutines.CancellationException) host.say(failure.message ?: "Subscription failed")
            }
        }
    }

    private fun useAsMessenger(service: BluetoothUuid, characteristic: GattCharacteristicInfo) {
        val current = session ?: return
        val messenger = blueLib.createGattMessenger(
            session = current,
            serviceUuid = service,
            characteristicUuid = characteristic.uuid,
            writeMode = writeModeFor(characteristic),
            framer = terminal.framer(),
        )
        terminal.attach("GATT ${characteristic.uuid.label()}", messenger)
        terminalOnGatt = true
        host.say("Terminal attached to ${characteristic.uuid.label()}")
    }

    private fun writeModeFor(characteristic: GattCharacteristicInfo): WriteMode =
        if (characteristic.properties and GattProperty.WRITE != 0) WriteMode.WITH_RESPONSE else WriteMode.WITHOUT_RESPONSE

    private fun showValue(row: ItemCharacteristicBinding, value: ByteArray) {
        row.charValue.visibility = View.VISIBLE
        row.charValue.text = value.preview()
    }

    // --- Classic sockets --------------------------------------------------------------------

    private fun connectRfcomm() {
        val id = requireTarget(BluetoothOperation.CONNECT) ?: return
        val uuid = BluetoothUuid.parseOrNull(b.rfcommUuid.text?.toString()?.trim())
            ?: return host.say("Enter a service UUID, e.g. the SPP UUID")
        host.busy(true)
        host.scope.launch {
            blueLib.connectRfcomm(id, uuid, SocketSettings(secure = b.rfcommSecure.isChecked))
                .onSuccess { connection ->
                    host.log.add("RFCOMM connected to ${connection.endpoint}")
                    terminalOnGatt = false
                    terminal.attach("RFCOMM ${connection.endpoint}", blueLib.createClassicMessenger(connection, terminal.framer()))
                }
                .onFailure { error ->
                    host.log.add("RFCOMM failed ${error.describe()}")
                    host.say(error.message)
                }
            host.busy(false)
        }
    }

    private fun connectL2cap() {
        val id = requireTarget(BluetoothOperation.CONNECT) ?: return
        val psm = b.l2capPsm.text?.toString()?.toIntOrNull() ?: return host.say("Enter the PSM the peer listens on")
        host.busy(true)
        host.scope.launch {
            blueLib.connectL2cap(id, psm)
                .onSuccess { connection ->
                    host.log.add("L2CAP connected to ${connection.endpoint} psm=$psm")
                    terminalOnGatt = false
                    terminal.attach("L2CAP ${connection.endpoint}", blueLib.createMessenger(connection, terminal.framer()))
                }
                .onFailure { error ->
                    host.log.add("L2CAP failed ${error.describe()}")
                    host.say(error.message)
                }
            host.busy(false)
        }
    }

    fun close() {
        terminal.close()
        disconnectGatt()
    }
}
