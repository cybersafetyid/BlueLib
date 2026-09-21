package io.github.cybersafetyid.bluelib.domain.model

/**
 * Identity of a remote Bluetooth device.
 *
 * Address type matters because a dual-mode device is reachable over both BR/EDR and LE with the
 * same address, while an LE privacy device rotates its random address. Comparing [BluetoothDeviceId]
 * values therefore compares the address text, and call sites that need a stable identity key should
 * prefer the value returned by `BluetoothDevice.getIdentityAddressWithType()` (API 36+) which the
 * platform layer maps into this type.
 */
public data class BluetoothDeviceId(
    val address: BluetoothAddress,
    val addressType: AddressType = AddressType.UNKNOWN,
) {
    /** Human readable description used in logs and diagnostics. */
    override fun toString(): String =
        if (addressType == AddressType.UNKNOWN) address.value else "${address.value} ($addressType)"

    public companion object {
        /** Builds an identifier from a raw address string. */
        public fun of(address: String, addressType: AddressType = AddressType.UNKNOWN): BluetoothDeviceId =
            BluetoothDeviceId(BluetoothAddress.parse(address), addressType)
    }
}

/** Transport a connection or bonding procedure is created on. */
public enum class Transport {
    /** Let the platform choose. BlueLib does not recommend this for GATT: see [LE]. */
    AUTO,

    /** BR/EDR (Bluetooth Classic). */
    BREDR,

    /** Bluetooth Low Energy. */
    LE,
    ;

    /** `true` when the transport is usable for GATT connections. */
    public val supportsGatt: Boolean
        get() = this != BREDR
}
