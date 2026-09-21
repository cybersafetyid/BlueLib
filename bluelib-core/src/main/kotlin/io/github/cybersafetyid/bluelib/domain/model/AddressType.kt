package io.github.cybersafetyid.bluelib.domain.model

/**
 * Address type of a Bluetooth device, matching the values exposed by
 * `BluetoothDevice.getAddressType()` (added in Android 15 / API 35) and the
 * `ADDRESS_TYPE_*` constants that exist since Android 12 / API 31.
 */
public enum class AddressType {
    /** A public (IEEE registered) address. */
    PUBLIC,

    /** A random address, including resolvable private addresses. */
    RANDOM,

    /**
     * An anonymous address, only ever reported by advertisers that use
     * non-resolvable private addresses for host identity.
     */
    ANONYMOUS,

    /**
     * Used on platforms that cannot report the address type (Android 14 and older) or when the
     * value could not be resolved.
     */
    UNKNOWN,
    ;

    /** `true` when the platform can report this address type, i.e. Android 15 (API 35) and newer. */
    public val isReportedByPlatform: Boolean
        get() = this != UNKNOWN
}
