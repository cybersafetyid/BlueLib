package io.github.cybersafetyid.bluelib.sample

import android.content.res.ColorStateList
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import io.github.cybersafetyid.bluelib.BlueLib
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.codec.DataCodec
import io.github.cybersafetyid.bluelib.domain.codec.DelimiterFramer
import io.github.cybersafetyid.bluelib.domain.codec.LengthPrefixedFramer
import io.github.cybersafetyid.bluelib.sample.databinding.PageHomeBinding
import kotlinx.coroutines.launch

/** Overview: adapter state, permissions, capability report and the codec playground. */
class HomePage(
    private val b: PageHomeBinding,
    private val host: SampleHost,
    private val enableBluetooth: () -> Unit,
) {
    private val blueLib get() = host.blueLib
    private val context get() = b.root.context

    init {
        val report = blueLib.capabilities
        b.heroVersion.text = BlueLib.version
        b.heroPlatform.text = context.getString(
            R.string.hero_platform,
            report.apiLevelDescription,
            Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            Build.MODEL,
        )
        b.btnEnableBluetooth.setOnClickListener { enableBluetooth() }
        b.btnGrantPermissions.setOnClickListener { host.requestPermissions() }

        host.scope.launch {
            blueLib.adapterState.collect { state ->
                b.heroAdapter.text = if (blueLib.hasAdapter) {
                    context.getString(R.string.adapter_state, state)
                } else {
                    context.getString(R.string.adapter_missing)
                }
                b.btnEnableBluetooth.visibility =
                    if (blueLib.hasAdapter && state != "ON") android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        renderCapabilities()
        b.codecInput.doAfterTextChanged { renderCodecs(it?.toString().orEmpty()) }
        renderCodecs(b.codecInput.text?.toString().orEmpty())
        refreshPermissions()
    }

    /** Re-reads the permission report; called again after the permission dialog closes. */
    fun refreshPermissions() {
        b.permissionChips.removeAllViews()
        var allGranted = true
        OPERATIONS.forEach { operation ->
            val report = blueLib.permissionsFor(operation)
            allGranted = allGranted && report.isSatisfied
            val chip = Chip(context).apply {
                text = operation.operationName.replaceFirstChar { it.uppercase() }
                isClickable = true
                chipIcon = ContextCompat.getDrawable(context, if (report.isSatisfied) R.drawable.ic_check else R.drawable.ic_close)
                val fg = ContextCompat.getColor(context, if (report.isSatisfied) R.color.success else R.color.danger)
                val bg = ContextCompat.getColor(context, if (report.isSatisfied) R.color.success_container else R.color.danger_container)
                chipIconTint = ColorStateList.valueOf(fg)
                setTextColor(fg)
                chipBackgroundColor = ColorStateList.valueOf(bg)
                chipStrokeWidth = 0f
                setOnClickListener {
                    val detail = when {
                        report.isSatisfied -> "granted"
                        report.notDeclared.isNotEmpty() -> "not declared in the manifest: ${report.notDeclared.joinToString()}"
                        else -> "missing: ${report.missing.joinToString { it.substringAfterLast('.') }}"
                    }
                    host.say("${operation.operationName}: $detail")
                }
            }
            b.permissionChips.addView(chip)
        }
        b.btnGrantPermissions.isEnabled = !allGranted
        b.btnGrantPermissions.setText(if (allGranted) R.string.permissions_all_granted else R.string.btn_grant_permissions)
    }

    private fun renderCapabilities() {
        val report = blueLib.capabilities
        b.capabilitiesSummary.text = context.getString(
            R.string.capabilities_summary,
            report.supportedFeatures.size,
            report.capabilities.size,
            report.apiLevelDescription,
        )
        report.capabilities.forEach { capability ->
            val chip = Chip(context).apply {
                text = capability.feature.name.lowercase().replace('_', ' ')
                isClickable = true
                // Dense cloud of 27 chips: drop the 48dp touch padding so they wrap tightly.
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = resources.getDimension(R.dimen.chip_dense_height)
                if (capability.supported) {
                    chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.blue_100))
                    setTextColor(ContextCompat.getColor(context, R.color.blue_900))
                    chipStrokeWidth = 0f
                } else {
                    chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))
                    setTextColor(ContextCompat.getColor(context, R.color.ink_muted))
                }
                setOnClickListener {
                    val reason = report.unsupportedReason(capability.feature) ?: "Supported."
                    host.say("${capability.feature.description} $reason")
                }
            }
            b.capabilityChips.addView(chip)
        }
    }

    private fun renderCodecs(input: String) {
        b.codecOutput.text = runCatching {
            val bytes = DataCodec.encodeText(input)
            val lineFramed = DelimiterFramer.lineFeed().frame(bytes)
            val lengthFramed = LengthPrefixedFramer().frame(bytes)
            listOf(
                "TEXT    $input",
                "HEX     ${DataCodec.decodeHex(bytes, " ")}",
                "BINARY  ${DataCodec.decodeBinary(bytes, formatSpaces = true)}",
                "BASE64  ${DataCodec.encodeBase64(bytes)}",
                "LF      ${DataCodec.decodeHex(lineFramed, " ")}",
                "LEN16   ${DataCodec.decodeHex(lengthFramed, " ")}",
            ).joinToString("\n")
        }.getOrElse { "Cannot encode: ${it.message}" }
    }

    private companion object {
        val OPERATIONS = listOf(
            BluetoothOperation.SCAN,
            BluetoothOperation.CONNECT,
            BluetoothOperation.CLASSIC_DISCOVERY,
            BluetoothOperation.ADVERTISE,
            BluetoothOperation.GATT_SERVER,
        )
    }
}
