package io.github.cybersafetyid.bluelib.android.compat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothStatusCodes
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import io.github.cybersafetyid.bluelib.domain.BluetoothFeature

/**
 * Detects what the running device can actually do.
 *
 * Most `BluetoothAdapter.isLe*()` queries require `BLUETOOTH_CONNECT` from Android 12 (API 31), and
 * OEM stacks occasionally throw `SecurityException` even when the permission is granted. Instead of
 * letting that kill a feature probe, every query is wrapped and a failure is reported as
 * "not supported" **and** surfaced through diagnostics so the real cause stays visible.
 */
public class PlatformCapabilities(
    private val adapter: BluetoothAdapter?,
    private val packageManager: PackageManager?,
) {

    /** Capability of [feature] on this device. */
    @SuppressLint("InlinedApi")
    public fun capabilityOf(feature: BluetoothFeature): Boolean = when (feature) {
        BluetoothFeature.BLUETOOTH_CLASSIC -> hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        BluetoothFeature.LOW_ENERGY -> hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        BluetoothFeature.LE_PERIPHERAL_ROLE -> query { adapter?.isMultipleAdvertisementSupported } == true
        BluetoothFeature.MULTIPLE_ADVERTISEMENT -> query { adapter?.isMultipleAdvertisementSupported } == true
        BluetoothFeature.OFFLOADED_FILTERING -> query { adapter?.isOffloadedFilteringSupported } == true
        BluetoothFeature.OFFLOADED_BATCHING -> query { adapter?.isOffloadedScanBatchingSupported } == true

        BluetoothFeature.LE_EXTENDED_ADVERTISING -> api26 { isLeExtendedAdvertisingSupported }
        BluetoothFeature.LE_2M_PHY -> api26 { isLe2MPhySupported }
        BluetoothFeature.LE_CODED_PHY -> api26 { isLeCodedPhySupported }
        BluetoothFeature.LE_PERIODIC_ADVERTISING -> api26 { isLePeriodicAdvertisingSupported }
        BluetoothFeature.OFFLOADED_PENDING_INTENT_SCAN -> ApiLevel.isAtLeast(26)

        BluetoothFeature.HEARING_AID_PROFILE -> ApiLevel.isAtLeast(28)
        BluetoothFeature.HID_DEVICE_ROLE -> ApiLevel.isAtLeast(28)
        BluetoothFeature.L2CAP_CHANNEL -> ApiLevel.isAtLeast(29)

        BluetoothFeature.LE_AUDIO -> api33 { isLeAudioSupported }
        BluetoothFeature.LE_AUDIO_BROADCAST_SOURCE -> api33 { isLeAudioBroadcastSourceSupported }
        BluetoothFeature.LE_AUDIO_BROADCAST_ASSISTANT -> api33 { isLeAudioBroadcastAssistantSupported }
        BluetoothFeature.CSIP_SET_COORDINATOR -> ApiLevel.isAtLeast(33)

        BluetoothFeature.ADVERTISING_SET_DISCOVERABLE -> ApiLevel.isAtLeast(34)
        BluetoothFeature.CONNECTION_PRIORITY_DCK -> ApiLevel.isAtLeast(34)

        BluetoothFeature.LE_CHANNEL_SOUNDING -> hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING)
        BluetoothFeature.LE_RSSI_RANGING -> ApiLevel.isAtLeast(36)
        BluetoothFeature.CONNECTION_SUBRATING -> ApiLevel.isAtLeast(36, 1)
        BluetoothFeature.SOCKET_SETTINGS -> ApiLevel.isAtLeast(36)
        BluetoothFeature.LE_HDT_PHY -> api37 { isLeHighDataThroughputPhySupported }
        BluetoothFeature.BOND_STATUS_TYPED -> ApiLevel.isAtLeast(37)
        BluetoothFeature.GATT_CONNECTION_SETTINGS -> ApiLevel.isAtLeast(37)
    }

    /** All capabilities, in declaration order of [BluetoothFeature]. */
    public fun snapshot(): List<BluetoothFeature> = BluetoothFeature.entries.filter { capabilityOf(it) }

    /** Feature that the platform has but the hardware lacks, or `null`. */
    public fun missingHardwareReason(feature: BluetoothFeature): String? = when {
        ApiLevel.isAtLeast(feature.introducedInApiLevel, feature.introducedInMinorApiLevel) &&
            !capabilityOf(feature) -> "${feature.fullName} is available in the platform but not on this hardware"
        else -> null
    }

    private fun hasSystemFeature(name: String): Boolean =
        packageManager?.hasSystemFeature(name) ?: false

    private inline fun query(block: () -> Boolean?): Boolean =
        runCatching { block() }.getOrDefault(false) == true

    // The API level checks live *inside* these helpers, which Android Lint cannot follow through an
    // inline lambda, so the `NewApi` check is suppressed here and the guard remains the real gate.
    @SuppressLint("NewApi")
    private inline fun api26(block: BluetoothAdapter.() -> Boolean): Boolean {
        if (!ApiLevel.isAtLeast(26)) return false
        val target = adapter ?: return false
        return query { target.block() }
    }

    @SuppressLint("NewApi")
    @RequiresApi(33)
    private inline fun api33(block: BluetoothAdapter.() -> Int): Boolean {
        if (!ApiLevel.isAtLeast(33)) return false
        val target = adapter ?: return false
        return query { target.block() == BluetoothStatusCodes.FEATURE_SUPPORTED }
    }

    @SuppressLint("NewApi")
    @RequiresApi(37)
    private inline fun api37(block: BluetoothAdapter.() -> Int): Boolean {
        if (!ApiLevel.isAtLeast(37)) return false
        val target = adapter ?: return false
        return query { target.block() == BluetoothStatusCodes.FEATURE_SUPPORTED }
    }
}
