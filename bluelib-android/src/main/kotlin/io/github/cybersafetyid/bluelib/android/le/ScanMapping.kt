package io.github.cybersafetyid.bluelib.android.le

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.domain.error.ScanFailureReason
import io.github.cybersafetyid.bluelib.domain.model.AddressType
import io.github.cybersafetyid.bluelib.domain.model.BluetoothAddress
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.MatchMode
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.ScanCallbackType
import io.github.cybersafetyid.bluelib.domain.model.ScanMode
import io.github.cybersafetyid.bluelib.domain.model.ScanObservation
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest

/** Translation between BlueLib's scan model and `android.bluetooth.le`. */
internal object ScanMapping {

    /** Builds the platform filters for a request, skipping the parts the platform cannot express. */
    fun filtersFor(request: ScanRequest): List<ScanFilter> {
        if (request.isUnfiltered) return emptyList()

        val builder = ScanFilter.Builder()
        var hasPlatformFilter = false

        request.serviceUuids.forEach { uuid ->
            builder.setServiceUuid(ParcelUuid(uuid.uuid))
            hasPlatformFilter = true
        }
        request.deviceName?.let { name ->
            builder.setDeviceName(name)
            hasPlatformFilter = true
        }
        request.manufacturerId?.let { companyId ->
            // Empty data plus an empty mask means "any payload for this company id"; a zero length
            // mask keeps Android's length validation happy.
            builder.setManufacturerData(companyId, EMPTY_BYTES, EMPTY_BYTES)
            hasPlatformFilter = true
        }

        return if (hasPlatformFilter) listOf(builder.build()) else emptyList()
    }

    /** Builds the platform scan settings, applying only the fields the API level supports. */
    @SuppressLint("InlinedApi")
    fun settingsFor(request: ScanRequest): ScanSettings {
        val builder = ScanSettings.Builder()
            .setScanMode(request.scanMode.toPlatform())
            .setReportDelay(request.reportDelayMillis)

        // `setCallbackType` and `setMatchMode` were both added in Android 6.0 (API 23). On Android
        // 5.0/5.1 only all-matches reporting exists, which is what the platform default already is.
        if (ApiLevel.isAtLeast(23)) {
            builder.setCallbackType(request.callbackType.toPlatform())
            builder.setMatchMode(request.matchMode.toPlatform())
        }
        if (ApiLevel.isAtLeast(26)) {
            builder.setPhy(request.phy.mask)
            builder.setLegacy(request.legacyOnly)
        }
        return builder.build()
    }

    /**
     * Applies the filters Android cannot express natively; today that is the name prefix, which is
     * checked in software to avoid the pitfall of `setDeviceName` requiring an exact match.
     */
    fun matchesSoftwareFilters(request: ScanRequest, observation: ScanObservation): Boolean {
        val prefix = request.namePrefix ?: return true
        val name = observation.deviceName ?: return false
        return name.startsWith(prefix, ignoreCase = true)
    }

    /** Maps a `ScanCallback.onScanFailed` code to a BlueLib reason. */
    fun failureReason(platformCode: Int): ScanFailureReason {
        return when (platformCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> ScanFailureReason.ALREADY_STARTED
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> ScanFailureReason.APP_REGISTRATION_FAILED
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> ScanFailureReason.INTERNAL_ERROR
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> ScanFailureReason.FEATURE_UNSUPPORTED
            // `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` only exists from Android 13.
            else -> if (ApiLevel.isAtLeast(33)) failureReasonFromApi33(platformCode) else ScanFailureReason.UNKNOWN
        }
    }

    @RequiresApi(33)
    private fun failureReasonFromApi33(platformCode: Int): ScanFailureReason {
        if (!ApiLevel.isAtLeast(33)) return ScanFailureReason.UNKNOWN
        return when (platformCode) {
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> ScanFailureReason.OUT_OF_HARDWARE_RESOURCES
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> ScanFailureReason.SCANNING_TOO_FREQUENTLY
            else -> ScanFailureReason.UNKNOWN
        }
    }

    /**
     * Maps a platform scan result into a BlueLib observation.
     *
     * `device.name` needs `BLUETOOTH_CONNECT` from Android 12 and throws without it, so every read is
     * wrapped: a missing permission degrades to a `null` name instead of losing the whole observation.
     */
    @SuppressLint("MissingPermission")
    fun observationOf(result: ScanResult, clockMillis: Long): ScanObservation {
        val device = result.device
        val record = result.scanRecord

        return ScanObservation(
            deviceId = deviceIdOf(device),
            rssi = result.rssi,
            txPower = runCatching { record?.txPowerLevel }.getOrNull(),
            deviceName = runCatching { record?.deviceName }.getOrNull()
                ?: runCatching { device.name }.getOrNull(),
            serviceUuids = runCatching { record?.serviceUuids }.getOrNull().orEmpty()
                .mapNotNull { parcel -> parcel?.let { BluetoothUuid.of(it.uuid) } },
            serviceData = runCatching { record?.serviceData }.getOrNull().orEmpty()
                .mapNotNull { (parcel, bytes) -> parcel?.let { BluetoothUuid.of(it.uuid) to bytes } }
                .toMap(),
            manufacturerData = runCatching { record?.manufacturerSpecificData }.getOrNull()?.let { sparse ->
                (0 until sparse.size()).associate { index -> sparse.keyAt(index) to sparse.valueAt(index) }
            }.orEmpty(),
            isConnectable = if (ApiLevel.isAtLeast(26)) result.isConnectable else true,
            primaryPhy = if (ApiLevel.isAtLeast(26)) {
                Phy.fromMask(result.primaryPhy).firstOrNull() ?: Phy.LE_1M
            } else {
                Phy.LE_1M
            },
            secondaryPhy = if (ApiLevel.isAtLeast(26)) Phy.fromMask(result.secondaryPhy).firstOrNull() else null,
            advertisingSid = if (ApiLevel.isAtLeast(26)) result.advertisingSid else null,
            periodicAdvertisingInterval = if (ApiLevel.isAtLeast(26)) result.periodicAdvertisingInterval else null,
            advertisedData = runCatching { record?.bytes }.getOrNull() ?: EMPTY_BYTES,
            scanRecordRaw = EMPTY_BYTES,
            timestampMillis = runCatching { result.timestampNanos / 1_000_000L }.getOrDefault(clockMillis),
        )
    }

    /**
     * Reads the device identity, degrading gracefully when the permission is missing or the API level
     * cannot report the address type.
     */
    fun deviceIdOf(device: BluetoothDevice): BluetoothDeviceId {
        val address = runCatching { device.address }.getOrNull().orEmpty()
        val parsed = BluetoothAddress.parseOrNull(address) ?: BluetoothAddress.parse(BluetoothAddress.PLACEHOLDER)
        val type = if (ApiLevel.isAtLeast(35)) addressTypeOf(device) else AddressType.UNKNOWN
        return BluetoothDeviceId(parsed, type)
    }

    @RequiresApi(35)
    private fun addressTypeOf(device: BluetoothDevice): AddressType = runCatching {
        when (device.addressType) {
            BluetoothDevice.ADDRESS_TYPE_PUBLIC -> AddressType.PUBLIC
            BluetoothDevice.ADDRESS_TYPE_RANDOM -> AddressType.RANDOM
            BluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> AddressType.ANONYMOUS
            else -> AddressType.UNKNOWN
        }
    }.getOrDefault(AddressType.UNKNOWN)

    private fun ScanMode.toPlatform(): Int = when (this) {
        ScanMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
        ScanMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
        ScanMode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
    }

    private fun ScanCallbackType.toPlatform(): Int = when (this) {
        ScanCallbackType.ALL_MATCHES -> ScanSettings.CALLBACK_TYPE_ALL_MATCHES
        ScanCallbackType.FIRST_MATCH -> ScanSettings.CALLBACK_TYPE_FIRST_MATCH
        ScanCallbackType.MATCH_LOST -> ScanSettings.CALLBACK_TYPE_MATCH_LOST
        ScanCallbackType.FIRST_AND_MATCH_LOST -> ScanSettings.CALLBACK_TYPE_ALL_MATCHES
    }

    private fun MatchMode.toPlatform(): Int = when (this) {
        MatchMode.AGGRESSIVE -> ScanSettings.MATCH_MODE_AGGRESSIVE
        MatchMode.STICKY -> ScanSettings.MATCH_MODE_STICKY
    }

    private val EMPTY_BYTES = ByteArray(0)
}
