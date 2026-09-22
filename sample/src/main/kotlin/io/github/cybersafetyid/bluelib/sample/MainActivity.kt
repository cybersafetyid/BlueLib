package io.github.cybersafetyid.bluelib.sample

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.cybersafetyid.bluelib.BlueLib
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.codec.DataCodec
import io.github.cybersafetyid.bluelib.domain.codec.DataEncoding
import io.github.cybersafetyid.bluelib.domain.codec.DelimiterFramer
import io.github.cybersafetyid.bluelib.domain.model.AutoPairFilter
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.port.ScanEvent
import io.github.cybersafetyid.bluelib.sample.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var blueLib: BlueLib
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize BlueLib facade using published library artifact
        blueLib = BlueLib.create(applicationContext)

        // Display library version and system report
        val versionText = getString(R.string.version_format, BlueLib.version)
        binding.tvVersion.text = versionText

        val report = blueLib.capabilities
        val capsSummary = getString(
            R.string.capabilities_format,
            report.apiLevelDescription,
            blueLib.hasAdapter,
            report.supportedFeatures.size,
            report.capabilities.size,
        )
        binding.tvCapabilities.text = capsSummary

        log("Initialized ${BlueLib.version}")
        log("Device Capabilities: ${report.supportedFeatures.joinToString { it.fullName }}")

        // Setup UI listeners
        binding.btnPermissions.setOnClickListener {
            checkPermissions()
        }

        binding.btnScan.setOnClickListener {
            if (scanJob?.isActive == true) {
                stopScan()
            } else {
                startScan()
            }
        }

        binding.btnAutoPair.setOnClickListener {
            runAutoPair()
        }

        binding.btnTestCodecs.setOnClickListener {
            runDataCodecDemo()
        }

        // Collect diagnostics
        lifecycleScope.launch {
            blueLib.diagnostics.collect { event ->
                log("DiagnosticEvent: $event")
            }
        }
    }

    private fun checkPermissions() {
        val scanPermissions = blueLib.permissionsFor(BluetoothOperation.SCAN)
        val connectPermissions = blueLib.permissionsFor(BluetoothOperation.CONNECT)

        val reportText = "Scan satisfied: ${scanPermissions.isSatisfied}\n" +
            "Missing scan permissions: ${scanPermissions.missing}\n" +
            "Connect satisfied: ${connectPermissions.isSatisfied}\n" +
            "Missing connect permissions: ${connectPermissions.missing}"

        Toast.makeText(this, reportText, Toast.LENGTH_LONG).show()
        log("Permissions check:\n$reportText")
    }

    private fun startScan() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.SCAN)) {
            val missingText = getString(R.string.missing_scan_permission)
            log("Cannot start scan: $missingText")
            Toast.makeText(this, missingText, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnScan.text = getString(R.string.btn_stop_scan)
        binding.tvStatus.text = getString(R.string.status_scanning)
        log("Starting LE Scan...")

        scanJob = lifecycleScope.launch {
            try {
                blueLib.scan(ScanRequest(autoStopAfterMillis = 10_000L)).collect { event ->
                    when (event) {
                        is ScanEvent.Observed -> {
                            val obs = event.observation
                            log("Device found: ${obs.deviceName ?: "Unknown"} (${obs.deviceId}) RSSI: ${obs.rssi}")
                        }
                        is ScanEvent.Lost -> {
                            val obs = event.observation
                            log("Device lost: ${obs.deviceName ?: "Unknown"} (${obs.deviceId}) Reason: ${event.reason}")
                        }
                        is ScanEvent.Failed -> log("Scan event: Failed (${event.error})")
                    }
                }
            } catch (e: Exception) {
                log("Scan exception: ${e.message}")
            } finally {
                stopScanUI()
            }
        }
    }

    private fun runAutoPair() {
        if (!blueLib.hasPermissionFor(BluetoothOperation.SCAN) || !blueLib.hasPermissionFor(BluetoothOperation.CONNECT)) {
            log("Cannot run Auto Pair: missing SCAN or CONNECT permissions.")
            Toast.makeText(this, "Missing SCAN or CONNECT permission", Toast.LENGTH_SHORT).show()
            return
        }

        val filter = AutoPairFilter(
            minRssi = -80,
            transport = Transport.AUTO,
        )

        log("Initiating Dynamic Auto Pair (minRssi >= -80 dBm)...")
        lifecycleScope.launch {
            blueLib.autoPair(filter, timeoutMillis = 10_000L).onSuccess { deviceId ->
                log("Auto Pair SUCCESS: Paired with $deviceId")
                Toast.makeText(this@MainActivity, "Auto Pair Success: $deviceId", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                log("Auto Pair FAILED: [${error.code}] ${error.message}")
                Toast.makeText(this@MainActivity, "Auto Pair Failed: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runDataCodecDemo() {
        log("--- Multi-Format Data Codec Demo (BlueLib) ---")

        val originalText = "Hello BlueLib!"
        val textBytes = DataCodec.encodeText(originalText, DataEncoding.UTF8)
        val decodedText = DataCodec.decodeText(textBytes, DataEncoding.UTF8)
        log("[Text UTF-8] Original: '$originalText' -> Decoded: '$decodedText'")

        val hexSample = "48656C6C6F"
        val hexBytes = DataCodec.encodeHex(hexSample)
        val decodedHex = DataCodec.decodeHex(hexBytes, separator = ":")
        log("[Hex] Input: '$hexSample' -> Decoded String: '${DataCodec.decodeText(hexBytes)}' (Formatted Hex: '$decodedHex')")

        val binarySample = "01001000 01100101"
        val binaryBytes = DataCodec.encodeBinary(binarySample)
        val decodedBinary = DataCodec.decodeBinary(binaryBytes, formatSpaces = true)
        log("[Binary] Input: '$binarySample' -> Decoded Bits: '$decodedBinary'")

        val base64Sample = DataCodec.encodeBase64("BlueLib Base64 Test".toByteArray(Charsets.UTF_8))
        val decodedBase64Bytes = DataCodec.decodeBase64(base64Sample)
        log("[Base64] Encoded: '$base64Sample' -> Decoded Text: '${DataCodec.decodeText(decodedBase64Bytes)}'")

        // Framer Demo
        val framer = DelimiterFramer.LINE_FEED
        val framedPacket = framer.frame("SampleMessage".toByteArray(Charsets.UTF_8))
        val parsedPackets = framer.parseIncoming(framedPacket)
        log("[DelimiterFramer] Framed '${DataCodec.decodeText(framedPacket)}' -> Parsed ${parsedPackets.size} packet: '${DataCodec.decodeText(parsedPackets.first())}'")
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        blueLib.releaseScanner()
        stopScanUI()
        log("Scan cancelled.")
    }

    private fun stopScanUI() {
        binding.btnScan.text = getString(R.string.btn_start_scan)
        binding.tvStatus.text = getString(R.string.status_idle)
    }

    private fun log(message: String) {
        val currentLog = binding.tvLog.text.toString()
        val updated = "$message\n$currentLog"
        binding.tvLog.text = updated
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::blueLib.isInitialized) {
            blueLib.close()
        }
    }
}
