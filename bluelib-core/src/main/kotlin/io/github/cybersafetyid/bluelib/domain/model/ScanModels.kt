package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException

/** Scan mode, mirroring `android.bluetooth.le.ScanSettings.SCAN_MODE_*`. */
public enum class ScanMode {
    /** Lowest duty cycle, best for opportunistic background scans. */
    LOW_POWER,

    /** Balanced duty cycle. */
    BALANCED,

    /** Highest duty cycle; stop it as soon as the target is found. */
    LOW_LATENCY,
}

/** Callback type, mirroring `ScanSettings.CALLBACK_TYPE_*`. */
public enum class ScanCallbackType {
    /** Report each packet, even from devices already reported. */
    ALL_MATCHES,

    /** Report the first packet of each device. */
    FIRST_MATCH,

    /** Report when a device has not been seen for a while (lost). */
    MATCH_LOST,

    /** Report both first match and lost events. */
    FIRST_AND_MATCH_LOST,
    ;

    public companion object {
        /**
         * `CALLBACK_TYPE_ALL_MATCHES_AUTO_BATCH` arrived in Android 14 (API 34) and is exposed here
         * for completeness; the platform layer maps it when the API level allows.
         */
        public val ALL_MATCHES_AUTO_BATCH: ScanCallbackType = ALL_MATCHES
    }
}

/** Match mode, mirroring `ScanSettings.MATCH_MODE_*`. */
public enum class MatchMode {
    /** More matches, more power use. */
    AGGRESSIVE,

    /** Fewer matches, less power use. */
    STICKY,
}

/** Filter result reported by the platform when a device stops advertising. */
public enum class ScanLostReason {
    /** The device was not seen for a while. */
    NOT_SEEN_FOR_A_WHILE,

    /** The device stopped advertising explicitly, or the peripheral failed. */
    STOPPED,
}

/**
 * Everything a caller can ask of the scanner.
 *
 * All fields are validated in [init], so an invalid request can never reach Android and produce a
 * silent failure (which is exactly what happens when, for example, a scan is started with an
 * unsupported PHY combination).
 */
public data class ScanRequest(
    /** Restrict results to devices advertising one of these services. */
    val serviceUuids: List<BluetoothUuid> = emptyList(),
    /** Exact device name match. */
    val deviceName: String? = null,
    /** Case insensitive name prefix match. */
    val namePrefix: String? = null,
    /** Manufacturer id (Bluetooth SIG company identifier) to match. */
    val manufacturerId: Int? = null,
    val scanMode: ScanMode = ScanMode.LOW_LATENCY,
    val callbackType: ScanCallbackType = ScanCallbackType.ALL_MATCHES,
    val matchMode: MatchMode = MatchMode.AGGRESSIVE,
    /** Requested scanning PHY. Android 8.0+ only; older devices always scan on 1M. */
    val phy: Phy = Phy.LE_1M,
    /** Ask the platform to only deliver legacy advertisements. */
    val legacyOnly: Boolean = false,
    /** Batch results and deliver them at most every N milliseconds (0 = deliver immediately). */
    val reportDelayMillis: Long = 0,
    /** Ask the platform to stop the scan automatically. `null` disables the safety stop. */
    val autoStopAfterMillis: Long? = DEFAULT_AUTO_STOP_MILLIS,
    /**
     * `true` when the scan may continue while the app is in the background. The platform layer then
     * prefers the `PendingIntent`-based scan (Android 8.0+) or requires a foreground service on
     * Android 14+, and the permission gateway asks for the background location permission on
     * Android 10/11.
     */
    val allowBackgroundScan: Boolean = false,
) {
    init {
        if (deviceName != null && deviceName.length > MAX_ADVERTISED_NAME_LENGTH) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "deviceName.length",
                value = deviceName.length.toLong(),
                allowed = 0L..MAX_ADVERTISED_NAME_LENGTH.toLong(),
            )
        }
        if (namePrefix != null && namePrefix.length > MAX_ADVERTISED_NAME_LENGTH) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "namePrefix.length",
                value = namePrefix.length.toLong(),
                allowed = 0L..MAX_ADVERTISED_NAME_LENGTH.toLong(),
            )
        }
        manufacturerId?.let {
            if (it !in 0..0xFFFF) {
                throw BlueLibValidationException.ValueOutOfRange(
                    parameter = "manufacturerId",
                    value = it.toLong(),
                    allowed = 0L..0xFFFFL,
                )
            }
        }
        if (!Phy.isValidMask(phy.mask)) {
            throw BlueLibValidationException.InvalidPhyMask(phy.mask, Phy.ALL_MASK)
        }
        if (reportDelayMillis !in 0..MAX_REPORT_DELAY_MILLIS) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "reportDelayMillis",
                value = reportDelayMillis,
                allowed = 0L..MAX_REPORT_DELAY_MILLIS,
            )
        }
        autoStopAfterMillis?.let {
            if (it !in MIN_AUTO_STOP_MILLIS..MAX_AUTO_STOP_MILLIS) {
                throw BlueLibValidationException.ValueOutOfRange(
                    parameter = "autoStopAfterMillis",
                    value = it,
                    allowed = MIN_AUTO_STOP_MILLIS..MAX_AUTO_STOP_MILLIS,
                )
            }
        }
        if (legacyOnly && phy != Phy.LE_1M) {
            throw BlueLibValidationException.IllegalState(
                stateMachine = "ScanRequest",
                from = "legacyOnly=true",
                to = "phy=$phy",
                hint = "Legacy advertisements are only transmitted on the 1M PHY.",
            )
        }
    }

    /** `true` when the request carries no filters and therefore matches every advertiser. */
    public val isUnfiltered: Boolean
        get() = serviceUuids.isEmpty() && deviceName == null && namePrefix == null && manufacturerId == null

    public companion object {
        /** Longest device name the legacy advertising payload can carry. */
        public const val MAX_ADVERTISED_NAME_LENGTH: Int = 29

        /** Default safety stop, long enough for a foreground scan, short enough to save power. */
        public const val DEFAULT_AUTO_STOP_MILLIS: Long = 15_000L

        /**
         * Silence after which a device is reported as lost. Android itself only reports match-lost
         * events when the scan was started with a match-lost callback type, which is why BlueLib
         * tracks presence in software instead.
         */
        public const val DEFAULT_LOST_AFTER_MILLIS: Long = 5_000L

        public const val MIN_AUTO_STOP_MILLIS: Long = 500L
        public const val MAX_AUTO_STOP_MILLIS: Long = 30 * 60 * 1000L
        public const val MAX_REPORT_DELAY_MILLIS: Long = 10_000L
    }
}

/**
 * A normalised scan result.
 *
 * BlueLib keeps the parsed advertising fields in a structured form (instead of only the raw bytes)
 * because almost every caller ends up re-parsing them, and parsing in the domain layer makes it
 * testable without a device.
 */
public data class ScanObservation(
    val deviceId: BluetoothDeviceId,
    val rssi: Int,
    val txPower: Int? = null,
    val deviceName: String? = null,
    val serviceUuids: List<BluetoothUuid> = emptyList(),
    val serviceData: Map<BluetoothUuid, ByteArray> = emptyMap(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val isConnectable: Boolean = true,
    val primaryPhy: Phy = Phy.LE_1M,
    val secondaryPhy: Phy? = null,
    val advertisingSid: Int? = null,
    val periodicAdvertisingInterval: Int? = null,
    val advertisedData: ByteArray = ByteArray(0),
    val scanRecordRaw: ByteArray = ByteArray(0),
    val timestampMillis: Long = 0L,
) {
    init {
        if (rssi !in MIN_RSSI..MAX_RSSI) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "rssi",
                value = rssi.toLong(),
                allowed = MIN_RSSI.toLong()..MAX_RSSI.toLong(),
            )
        }
    }

    /** `true` when the advertiser publishes [serviceUuid]. */
    public fun advertises(serviceUuid: BluetoothUuid): Boolean = serviceUuid in serviceUuids

    /** Manufacturer specific payload for [companyId], or `null`. */
    public fun manufacturerPayload(companyId: Int): ByteArray? = manufacturerData[companyId]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanObservation) return false
        return deviceId == other.deviceId &&
            rssi == other.rssi &&
            txPower == other.txPower &&
            deviceName == other.deviceName &&
            serviceUuids == other.serviceUuids &&
            isConnectable == other.isConnectable &&
            primaryPhy == other.primaryPhy &&
            secondaryPhy == other.secondaryPhy &&
            advertisingSid == other.advertisingSid &&
            timestampMillis == other.timestampMillis &&
            advertisedData.contentEquals(other.advertisedData)
    }

    // ByteArray fields are compared by content in equals(), so the hash must follow the same rule.
    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + rssi
        result = 31 * result + (txPower ?: 0)
        result = 31 * result + (deviceName?.hashCode() ?: 0)
        result = 31 * result + serviceUuids.hashCode()
        result = 31 * result + isConnectable.hashCode()
        result = 31 * result + primaryPhy.hashCode()
        result = 31 * result + (secondaryPhy?.hashCode() ?: 0)
        result = 31 * result + (advertisingSid ?: -1)
        result = 31 * result + timestampMillis.hashCode()
        result = 31 * result + advertisedData.contentHashCode()
        return result
    }

    public companion object {
        /** `RSSI` range the Bluetooth specification allows. */
        public const val MIN_RSSI: Int = -127

        /** `RSSI` range the Bluetooth specification allows. */
        public const val MAX_RSSI: Int = 20
    }
}
