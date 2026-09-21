package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException

/** Advertising interval preset, mirroring `AdvertiseSettings.ADVERTISE_MODE_*`. */
public enum class AdvertiseMode {
    /** ~1 s interval. */
    LOW_POWER,

    /** ~250 ms interval. */
    BALANCED,

    /** ~100 ms interval: fastest discovery, highest power. */
    LOW_LATENCY,
}

/** TX power preset, mirroring `AdvertiseSettings.ADVERTISE_TX_POWER_*`. */
public enum class TxPowerLevel {
    ULTRA_LOW,
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Advertising payload.
 *
 * Sizes are validated by [io.github.cybersafetyid.bluelib.domain.validation.AdvertisingPayload] rules:
 * legacy advertising has a 31 byte budget per section, extended advertising (Android 8.0+) has
 * around 1650 bytes, and BlueLib refuses payloads that would be silently truncated by Android.
 */
public data class AdvertiseData(
    val serviceUuids: List<BluetoothUuid> = emptyList(),
    val serviceSolicitationUuids: List<BluetoothUuid> = emptyList(),
    val serviceData: Map<BluetoothUuid, ByteArray> = emptyMap(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val includeDeviceName: Boolean = false,
    val includeTxPowerLevel: Boolean = false,
    val transportDiscoveryData: List<ByteArray> = emptyList(),
) {
    init {
        manufacturerData.keys.forEach { companyId ->
            if (companyId !in 0..0xFFFF) {
                throw BlueLibValidationException.ValueOutOfRange(
                    parameter = "manufacturerData.companyId",
                    value = companyId.toLong(),
                    allowed = 0L..0xFFFFL,
                )
            }
        }
        serviceData.forEach { (uuid, payload) ->
            if (payload.isEmpty()) {
                throw BlueLibValidationException.InvalidPayload(
                    operation = "AdvertiseData.serviceData[$uuid]",
                    sizeBytes = 0,
                    allowed = 1..251,
                    hint = "An empty service data field is rejected by the platform.",
                )
            }
        }
    }

    /** `true` when nothing would be transmitted. */
    public val isEmpty: Boolean
        get() = serviceUuids.isEmpty() && serviceSolicitationUuids.isEmpty() && serviceData.isEmpty() &&
            manufacturerData.isEmpty() && !includeDeviceName && !includeTxPowerLevel &&
            transportDiscoveryData.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertiseData) return false
        return serviceUuids == other.serviceUuids &&
            serviceSolicitationUuids == other.serviceSolicitationUuids &&
            includeDeviceName == other.includeDeviceName &&
            includeTxPowerLevel == other.includeTxPowerLevel &&
            serviceData.size == other.serviceData.size &&
            serviceData.all { (uuid, bytes) -> other.serviceData[uuid]?.contentEquals(bytes) == true } &&
            manufacturerData.size == other.manufacturerData.size &&
            manufacturerData.all { (id, bytes) -> other.manufacturerData[id]?.contentEquals(bytes) == true }
    }

    override fun hashCode(): Int {
        var result = serviceUuids.hashCode()
        result = 31 * result + serviceSolicitationUuids.hashCode()
        result = 31 * result + includeDeviceName.hashCode()
        result = 31 * result + includeTxPowerLevel.hashCode()
        result = 31 * result + serviceData.keys.hashCode()
        result = 31 * result + manufacturerData.keys.hashCode()
        return result
    }
}

/** Parameters of an advertising set. */
public data class AdvertisingParameters(
    val connectable: Boolean = true,
    val scannable: Boolean = false,
    /**
     * Mark the set discoverable. `AdvertisingSetParameters.setDiscoverable` arrived in Android 14
     * (API 34); on older versions BlueLib reports
     * [BlueLibError.FeatureUnsupported][io.github.cybersafetyid.bluelib.domain.error.BlueLibError.FeatureUnsupported]
     * when this is requested.
     */
    val discoverable: Boolean = false,
    val mode: AdvertiseMode = AdvertiseMode.BALANCED,
    val txPowerLevel: TxPowerLevel = TxPowerLevel.MEDIUM,
    /** Requested transmit power in dBm; overrides [txPowerLevel] when set. */
    val txPowerDbm: Int? = null,
    /** Stop advertising automatically after this many milliseconds (0 = no timeout). */
    val timeoutMillis: Long = 0L,
    val primaryPhy: Phy = Phy.LE_1M,
    val secondaryPhy: Phy? = null,
    /** `true` when the platform should use an extended advertising set (Android 8.0+). */
    val useExtendedAdvertising: Boolean = false,
    /** Number of advertising sets to reserve (Android 8.0+, up to `isMultipleAdvertisementSupported`). */
    val setCount: Int = 1,
) {
    init {
        if (timeoutMillis !in 0..MAX_TIMEOUT_MILLIS) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "timeoutMillis",
                value = timeoutMillis,
                allowed = 0L..MAX_TIMEOUT_MILLIS,
            )
        }
        txPowerDbm?.let {
            if (it !in MIN_TX_POWER_DBM..MAX_TX_POWER_DBM) {
                throw BlueLibValidationException.ValueOutOfRange(
                    parameter = "txPowerDbm",
                    value = it.toLong(),
                    allowed = MIN_TX_POWER_DBM.toLong()..MAX_TX_POWER_DBM.toLong(),
                )
            }
        }
        if (!Phy.isValidMask(primaryPhy.mask)) {
            throw BlueLibValidationException.InvalidPhyMask(primaryPhy.mask, Phy.ALL_MASK)
        }
        secondaryPhy?.let {
            if (!Phy.isValidMask(it.mask)) {
                throw BlueLibValidationException.InvalidPhyMask(it.mask, Phy.ALL_MASK)
            }
        }
        if (scannable && !connectable && !useExtendedAdvertising) {
            // Scannable-only legacy advertising is legal, but it only makes sense when the payload
            // is meant to be read; keep it explicit rather than letting Android reject the combo.
            check(secondaryPhy == null) {
                "A scannable, non-connectable legacy set cannot request a secondary PHY."
            }
        }
        if (setCount !in 1..MAX_ADVERTISING_SETS) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "setCount",
                value = setCount.toLong(),
                allowed = 1L..MAX_ADVERTISING_SETS.toLong(),
            )
        }
    }

    public companion object {
        /** `AdvertisingSetParameters.setTimeout` accepts up to ten minutes. */
        public const val MAX_TIMEOUT_MILLIS: Long = 10 * 60 * 1000L

        /** The Bluetooth specification allows -127 dBm to 20 dBm transmit power. */
        public const val MIN_TX_POWER_DBM: Int = -127
        public const val MAX_TX_POWER_DBM: Int = 20

        /** `BluetoothAdapter.isMultipleAdvertisementSupported()` allows a handful of sets. */
        public const val MAX_ADVERTISING_SETS: Int = 4
    }
}

/** Request to start advertising: parameters plus the payload sent in the advertising packet. */
public data class AdvertisingRequest(
    val advertiseData: AdvertiseData,
    val parameters: AdvertisingParameters = AdvertisingParameters(),
    val scanResponse: AdvertiseData? = null,
) {
    init {
        if (advertiseData.isEmpty && scanResponse?.isEmpty != false) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "AdvertisingRequest",
                sizeBytes = 0,
                allowed = 1..Int.MAX_VALUE,
                hint = "An advertising set needs at least one data field.",
            )
        }
        if (parameters.useExtendedAdvertising && scanResponse?.isEmpty == false) {
            throw BlueLibValidationException.IllegalState(
                stateMachine = "AdvertisingRequest",
                from = "useExtendedAdvertising=true",
                to = "scanResponse provided",
                hint = "Extended advertising sets cannot use scan responses; put the data in one payload.",
            )
        }
    }
}

/** Handle returned by the platform when advertising starts successfully. */
public data class AdvertisingHandle(
    val setId: Int,
    val isExtended: Boolean,
    val txPowerDbm: Int? = null,
)
