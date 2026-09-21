package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.port.ClassicDevice

/**
 * Filter parameters used to discover and auto-pair with a target Bluetooth device.
 *
 * Callers can specify any combination of criteria: MAC address, exact device name, case-insensitive
 * name prefix, advertised service UUIDs, manufacturer ID, and minimum RSSI threshold.
 *
 * At least one matching criterion must be specified so an empty filter cannot accidentally
 * pair with an arbitrary nearby device.
 */
public data class AutoPairFilter(
    /** Match exact Bluetooth MAC address. */
    val targetAddress: BluetoothAddress? = null,
    /** Match exact device name. */
    val deviceName: String? = null,
    /** Match case-insensitive device name prefix. */
    val namePrefix: String? = null,
    /** Match advertised service UUIDs (device must advertise ALL listed UUIDs). */
    val serviceUuids: List<BluetoothUuid> = emptyList(),
    /** Match manufacturer company ID in advertising payload. */
    val manufacturerId: Int? = null,
    /** Minimum RSSI threshold in dBm (e.g. -70). */
    val minRssi: Int? = null,
    /** Transport to use for bonding procedure (AUTO, LE, BREDR). Default: AUTO. */
    val transport: Transport = Transport.AUTO,
) {
    init {
        validate()
    }

    /** Validates that at least one matching criterion is provided and parameters are within valid ranges. */
    public fun validate() {
        val isUnfiltered = (targetAddress == null) &&
            deviceName.isNullOrBlank() &&
            namePrefix.isNullOrBlank() &&
            serviceUuids.isEmpty() &&
            (manufacturerId == null) &&
            (minRssi == null)
        if (isUnfiltered) {
            throw BlueLibValidationException.EmptyFilter(
                "AutoPairFilter must specify at least one matching criterion " +
                    "(targetAddress, deviceName, namePrefix, serviceUuids, manufacturerId, or minRssi).",
            )
        }
        if (minRssi != null) {
            if (minRssi < ScanObservation.MIN_RSSI || minRssi > ScanObservation.MAX_RSSI) {
                throw BlueLibValidationException.ValueOutOfRange(
                    parameter = "minRssi",
                    value = minRssi.toLong(),
                    allowed = ScanObservation.MIN_RSSI.toLong()..ScanObservation.MAX_RSSI.toLong(),
                )
            }
        }
    }

    /** Checks whether a BLE [observation] matches every specified criterion in this filter. */
    public fun matches(observation: ScanObservation): Boolean {
        if (targetAddress != null && observation.deviceId.address != targetAddress) return false
        if (deviceName != null && observation.deviceName != deviceName) return false
        if (namePrefix != null && (observation.deviceName == null || !observation.deviceName.startsWith(namePrefix, ignoreCase = true))) return false
        if (serviceUuids.isNotEmpty() && !observation.serviceUuids.containsAll(serviceUuids)) return false
        if (manufacturerId != null && !observation.manufacturerData.containsKey(manufacturerId)) return false
        return minRssi == null || observation.rssi >= minRssi
    }

    /** Checks whether a Classic [device] matches every specified criterion in this filter. */
    public fun matches(device: ClassicDevice, rssi: Int?): Boolean {
        if (targetAddress != null && device.deviceId.address != targetAddress) return false
        if (deviceName != null && device.name != deviceName) return false
        if (namePrefix != null && (device.name == null || !device.name.startsWith(namePrefix, ignoreCase = true))) return false
        if (serviceUuids.isNotEmpty() && !device.uuids.containsAll(serviceUuids)) return false
        return minRssi == null || (rssi != null && rssi >= minRssi)
    }
}
