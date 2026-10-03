package io.github.cybersafetyid.bluelib.sample

import io.github.cybersafetyid.bluelib.BlueLib
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BondState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a device in the sample came from. */
enum class DeviceSource(val label: String) { BLE("BLE"), CLASSIC("Classic"), BONDED("Paired"), MANUAL("Manual") }

/** A device the user can pair with or connect to. */
data class DeviceItem(
    val id: BluetoothDeviceId,
    val name: String?,
    val source: DeviceSource,
    val rssi: Int? = null,
    val bondState: BondState = BondState.NONE,
    val connectable: Boolean = true,
    val serviceCount: Int = 0,
    val lost: Boolean = false,
) {
    val address: String get() = id.address.value
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Unknown device"
}

/** Everything the pages share: the library, the log, the selected device and a few UI hooks. */
class SampleHost(
    val blueLib: BlueLib,
    val scope: CoroutineScope,
    val log: Logbook,
    /** Shows a short message; [action] adds a button such as "Grant". */
    val message: (text: String, action: Pair<String, () -> Unit>?) -> Unit,
    val busy: (Boolean) -> Unit,
    val requestPermissions: () -> Unit,
    val openConnectPage: () -> Unit,
) {
    private val mutableSelected = MutableStateFlow<DeviceItem?>(null)

    /** Device chosen on the Scan page (or typed by hand) for the Connect page. */
    val selected: StateFlow<DeviceItem?> = mutableSelected.asStateFlow()

    fun select(device: DeviceItem) {
        mutableSelected.value = device
        log.add("Selected ${device.displayName} (${device.address})")
    }

    fun say(text: String) = message(text, null)

    /** Message with a "Grant" button that opens the permission dialog. */
    fun askForPermission(text: String) = message(text, "Grant" to requestPermissions)
}

/** In-memory log shown in the log sheet; newest entry first. */
class Logbook(private val capacity: Int = 400) {
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val mutableLines = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> = mutableLines.asStateFlow()

    /** When `true`, every BlueLib DiagnosticEvent is appended too. */
    @Volatile
    var includeDiagnostics: Boolean = false

    fun add(message: String) {
        android.util.Log.i("BlueLibSample", message)
        val line = "${format.format(Date())}  $message"
        mutableLines.update { (listOf(line) + it).take(capacity) }
    }

    fun clear() {
        mutableLines.value = emptyList()
    }
}
