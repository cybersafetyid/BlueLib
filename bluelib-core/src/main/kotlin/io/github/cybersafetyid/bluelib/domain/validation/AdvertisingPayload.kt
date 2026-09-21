package io.github.cybersafetyid.bluelib.domain.validation

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.AdvertiseData
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid

/**
 * Size arithmetic for advertising payloads.
 *
 * Android happily accepts an oversized `AdvertiseData` and then truncates the packet (or fails with
 * `ADVERTISE_FAILED_DATA_TOO_LARGE` which many devices never report). BlueLib therefore computes the
 * exact AD-structure size before touching the platform:
 *
 * ```
 * AD structure = 1 length byte + 1 type byte + payload          (payload <= 29 bytes for legacy)
 * ```
 *
 * Legacy advertising allows 31 bytes per section, extended advertising (Android 8.0+) around 1650.
 */
public object AdvertisingPayload {

    /** Maximum number of bytes in one legacy advertising packet. */
    public const val LEGACY_MAX_BYTES: Int = 31

    /**
     * Maximum number of bytes in an extended advertising packet. The specification allows up to
     * 1650 bytes of advertising data (255 per AD structure); Android reports its own limit through
     * `BluetoothAdapter.getLeMaximumAdvertisingDataLength()` which the platform layer prefers.
     */
    public const val EXTENDED_MAX_BYTES: Int = 1650

    /** Longest payload a single AD structure can carry (255 byte structure minus the two headers). */
    public const val MAX_AD_STRUCTURE_PAYLOAD: Int = 253

    /** Flags (type `0x01`) are always three bytes long. */
    private const val FLAGS_FIELD_BYTES: Int = 3

    /** AD types used by the size calculation. */
    private const val TYPE_SERVICE_UUID_16 = 0x02
    private const val TYPE_SERVICE_UUID_128 = 0x06
    private const val TYPE_LOCAL_NAME_COMPLETE = 0x08
    private const val TYPE_TX_POWER_LEVEL = 0x09
    private const val TYPE_SERVICE_SOLICITATION_16 = 0x14
    private const val TYPE_SERVICE_SOLICITATION_128 = 0x1F
    private const val TYPE_SERVICE_DATA_16 = 0x15
    private const val TYPE_SERVICE_DATA_128 = 0x20
    private const val TYPE_MANUFACTURER_SPECIFIC = 0xFF
    private const val TYPE_TRANSPORT_DISCOVERY_DATA = 0x26

    /** The byte budget for an advertising section. */
    public fun budgetFor(extendedAdvertising: Boolean): Int =
        if (extendedAdvertising) EXTENDED_MAX_BYTES else LEGACY_MAX_BYTES

    /**
     * Computes how many bytes [data] occupies.
     *
     * @param nameLength length of the advertised device name, when the name is included.
     * @param includeFlags whether the packet carries the flags field (true for connectable sets).
     */
    public fun sizeOf(
        data: AdvertiseData,
        nameLength: Int? = null,
        includeFlags: Boolean = true,
    ): Int {
        var total = if (includeFlags) FLAGS_FIELD_BYTES else 0

        data.serviceUuids.groupBy { it.shortValue != null }.forEach { (isSixteenBit, uuids) ->
            val payloadPerUuid = if (isSixteenBit) 2 else 16
            total += adStructureSize(payloadPerUuid * uuids.size)
        }
        data.serviceSolicitationUuids.groupBy { it.shortValue != null }.forEach { (isSixteenBit, uuids) ->
            val payloadPerUuid = if (isSixteenBit) 2 else 16
            total += adStructureSize(payloadPerUuid * uuids.size)
        }
        data.serviceData.forEach { (uuid, payload) ->
            val uuidBytes = if (uuid.shortValue != null) 2 else 16
            total += adStructureSize(uuidBytes + payload.size)
        }
        data.manufacturerData.forEach { (_, payload) ->
            // Manufacturer specific data carries the two byte company id inside its payload.
            total += adStructureSize(2 + payload.size)
        }
        data.transportDiscoveryData.forEach { payload ->
            total += adStructureSize(payload.size)
        }
        if (data.includeTxPowerLevel) {
            total += adStructureSize(1)
        }
        if (data.includeDeviceName) {
            total += adStructureSize(nameLength ?: 0)
        }
        return total
    }

    /**
     * Validates [data] against [budget], throwing a typed error that names the offending section.
     *
     * @throws BlueLibValidationException.AdvertisingDataTooLarge
     */
    public fun validate(
        data: AdvertiseData,
        section: String,
        budget: Int = LEGACY_MAX_BYTES,
        nameLength: Int? = null,
        includeFlags: Boolean = true,
    ) {
        data.serviceData.forEach { (uuid, payload) ->
            requireFieldFits(payload.size + uuidLength(uuid), section = "$section.serviceData[$uuid]")
        }
        data.manufacturerData.forEach { (companyId, payload) ->
            requireFieldFits(payload.size + 2, section = "$section.manufacturerData[0x${companyId.toString(16)}]")
        }
        if (data.includeDeviceName) {
            val length = nameLength ?: 0
            if (length !in 1..LEGACY_MAX_BYTES - 2) {
                throw BlueLibValidationException.ValueOutOfRange(
                    parameter = "$section.deviceName.length",
                    value = length.toLong(),
                    allowed = 1L..(LEGACY_MAX_BYTES - 2).toLong(),
                )
            }
        }

        val size = sizeOf(data, nameLength = nameLength, includeFlags = includeFlags)
        if (size > budget) {
            throw BlueLibValidationException.AdvertisingDataTooLarge(
                section = section,
                requestedBytes = size,
                maximumBytes = budget,
            )
        }
    }

    /** Names of the AD types BlueLib knows, used by documentation and diagnostics. */
    public fun adTypeName(adType: Int): String = when (adType) {
        TYPE_SERVICE_UUID_16 -> "Incomplete/Complete 16-bit Service UUIDs"
        TYPE_SERVICE_UUID_128 -> "Incomplete/Complete 128-bit Service UUIDs"
        TYPE_LOCAL_NAME_COMPLETE -> "Complete Local Name"
        TYPE_TX_POWER_LEVEL -> "TX Power Level"
        TYPE_SERVICE_SOLICITATION_16 -> "16-bit Service Solicitation UUIDs"
        TYPE_SERVICE_SOLICITATION_128 -> "128-bit Service Solicitation UUIDs"
        TYPE_SERVICE_DATA_16 -> "16-bit Service Data"
        TYPE_SERVICE_DATA_128 -> "128-bit Service Data"
        TYPE_MANUFACTURER_SPECIFIC -> "Manufacturer Specific Data"
        TYPE_TRANSPORT_DISCOVERY_DATA -> "Transport Discovery Data"
        else -> "AD type 0x${adType.toString(16)}"
    }

    private fun uuidLength(uuid: BluetoothUuid): Int = if (uuid.shortValue != null) 2 else 16

    private fun adStructureSize(payloadBytes: Int): Int = 2 + payloadBytes

    private fun requireFieldFits(payloadBytes: Int, section: String) {
        if (payloadBytes > MAX_AD_STRUCTURE_PAYLOAD) {
            throw BlueLibValidationException.AdvertisingDataTooLarge(
                section = section,
                requestedBytes = payloadBytes,
                maximumBytes = MAX_AD_STRUCTURE_PAYLOAD,
            )
        }
    }
}
