package io.github.cybersafetyid.bluelib.sample

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import io.github.cybersafetyid.bluelib.BlueLib
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.sample.databinding.ActivityMainBinding
import io.github.cybersafetyid.bluelib.sample.databinding.SheetLogBinding
import kotlinx.coroutines.launch

/**
 * BlueLib showcase: one tab per area of the library.
 *
 * * Home — adapter state, runtime permissions, capability report, data codecs.
 * * Scan — BLE scan, Classic discovery, paired devices, manual pairing and auto pairing.
 * * Connect — GATT client (services, read/write/notify, MTU, priority, PHY) and RFCOMM/L2CAP sockets.
 * * Peripheral — LE advertising and a GATT server (Nordic UART Service with echo).
 * * Links — TCP/IP, USB serial and native UART.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var blueLib: BlueLib
    private lateinit var host: SampleHost
    private val log = Logbook()
    private var busyCount = 0

    private lateinit var homePage: HomePage
    private lateinit var scanPage: ScanPage
    private lateinit var connectPage: ConnectPage
    private lateinit var peripheralPage: PeripheralPage
    private lateinit var linksPage: LinksPage

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        log.add("Permission result: ${result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        homePage.refreshPermissions()
        scanPage.onPermissionsChanged()
    }

    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        log.add("Enable Bluetooth dialog closed (result ${it.resultCode})")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        blueLib = BlueLib.create(applicationContext)
        host = SampleHost(
            blueLib = blueLib,
            scope = lifecycleScope,
            log = log,
            message = ::showMessage,
            busy = ::setBusy,
            requestPermissions = ::requestPermissions,
            openConnectPage = { binding.bottomNav.selectedItemId = R.id.nav_connect },
        )

        homePage = HomePage(binding.pageHome, host, ::enableBluetooth)
        scanPage = ScanPage(binding.pageScan, host)
        connectPage = ConnectPage(binding.pageConnect, host)
        peripheralPage = PeripheralPage(binding.pagePeripheral, host)
        linksPage = LinksPage(binding.pageLinks, host)

        binding.bottomNav.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_log) showLog()
            true
        }

        lifecycleScope.launch {
            blueLib.adapterState.collect { binding.toolbar.subtitle = getString(R.string.adapter_state, it) }
        }
        lifecycleScope.launch {
            blueLib.diagnostics.collect { event -> if (log.includeDiagnostics) log.add("◆ ${event.summary()}") }
        }
        log.add("Started ${BlueLib.version} on ${blueLib.capabilities.apiLevelDescription}")
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been changed in system settings while the app was in the background.
        homePage.refreshPermissions()
    }

    private fun showPage(itemId: Int) {
        val pages = mapOf(
            R.id.nav_home to binding.pageHome.root,
            R.id.nav_scan to binding.pageScan.root,
            R.id.nav_connect to binding.pageConnect.root,
            R.id.nav_peripheral to binding.pagePeripheral.root,
            R.id.nav_links to binding.pageLinks.root,
        )
        pages.forEach { (id, view) -> view.visibility = if (id == itemId) View.VISIBLE else View.GONE }
        if (itemId == R.id.nav_links) linksPage.refreshUsb()
    }

    /** Asks for every permission the library reports as missing across all operations. */
    private fun requestPermissions() {
        val missing = BluetoothOperation.entries
            .filter { it != BluetoothOperation.BACKGROUND_SCAN && it != BluetoothOperation.RANGING }
            .flatMap { blueLib.permissionsFor(it).missing }
            .distinct()
        if (missing.isEmpty()) {
            showMessage("All permissions are already granted.", null)
            homePage.refreshPermissions()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun enableBluetooth() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.CONNECT)) {
            showMessage("Turning Bluetooth on needs the Nearby devices permission.", "Grant" to ::requestPermissions)
            return
        }
        enableBluetoothLauncher.launch(blueLib.enableBluetoothIntent())
    }

    private fun showMessage(text: String, action: Pair<String, () -> Unit>?) {
        val snackbar = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).setAnchorView(binding.bottomNav)
        action?.let { (label, onClick) -> snackbar.setAction(label) { onClick() } }
        snackbar.show()
    }

    private fun setBusy(active: Boolean) {
        busyCount = (busyCount + if (active) 1 else -1).coerceAtLeast(0)
        binding.busy.visibility = if (busyCount > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun showLog() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetLogBinding.inflate(layoutInflater)
        sheet.logDiagnostics.isChecked = log.includeDiagnostics
        sheet.logDiagnostics.setOnCheckedChangeListener { _, checked -> log.includeDiagnostics = checked }
        sheet.logClear.setOnClickListener { log.clear() }
        sheet.logCopy.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("BlueLib log", log.lines.value.joinToString("\n")))
            showMessage("Log copied", null)
        }
        val job = lifecycleScope.launch { log.lines.collect { sheet.logText.text = it.joinToString("\n") } }
        dialog.setOnDismissListener { job.cancel() }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    /** targetSdk 35+ draws edge to edge: pad for the status bar, and hide the tabs while typing. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val typing = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding.bottomNav.visibility = if (typing) View.GONE else View.VISIBLE
            view.setPadding(bars.left, bars.top, bars.right, if (typing) ime.bottom else 0)
            insets
        }
    }

    override fun onDestroy() {
        if (::blueLib.isInitialized) {
            connectPage.close()
            peripheralPage.close()
            linksPage.close()
            blueLib.close()
        }
        super.onDestroy()
    }
}
