package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException

/**
 * A Bluetooth device address in its canonical `AA:BB:CC:DD:EE:FF` form.
 *
 * BlueLib never treats the address as a stable identifier on its own:
 *
 * * From Android 6.0 (API 23) the *local* adapter address is replaced with
 *   `02:00:00:00:00:00` for apps that lack the privileged `LOCAL_MAC_ADDRESS` permission, so
 *   [isPlaceholder] exists to make that case explicit instead of silently producing wrong data.
 * * From Android 12 (API 31) the address of a *remote* device requires `BLUETOOTH_CONNECT`, and
 *   Devices with LE privacy enabled rotate their resolvable private address. Use
 *   [BluetoothDeviceId] (address + type) or an application-level identifier when stable identity
 *   matters.
 */
@JvmInline
public value class BluetoothAddress private constructor(public val value: String) {

    /** `true` when the platform replaced the real address with the masked placeholder. */
    public val isPlaceholder: Boolean
        get() = value == PLACEHOLDER

    /** The address as six raw bytes, most significant byte first. */
    public val bytes: ByteArray
        get() = value.split(':').map { it.toInt(16).toByte() }.toByteArray()

    override fun toString(): String = value

    public companion object {
        /** Address returned by Android for the local adapter when the app cannot read the real one. */
        public const val PLACEHOLDER: String = "02:00:00:00:00:00"

        private val PATTERN = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

        /** Returns `true` when [raw] is a well formed six octet address. */
        public fun isValid(raw: String): Boolean = PATTERN.matches(raw)

        /**
         * Parses [raw] into a normalised address.
         *
         * @throws BlueLibValidationException when the input is not a six octet address.
         */
        public fun parse(raw: String): BluetoothAddress {
            val trimmed = raw.trim()
            if (!PATTERN.matches(trimmed)) {
                throw BlueLibValidationException.InvalidAddress(raw)
            }
            val normalised = trimmed.uppercase().let(::uppercaseAddress)
            return BluetoothAddress(normalised)
        }

        /** Parses [raw], returning `null` instead of throwing for malformed input. */
        public fun parseOrNull(raw: String?): BluetoothAddress? =
            raw?.let { if (PATTERN.matches(it.trim())) parse(it) else null }

        /** Builds an address from its six raw bytes. */
        public fun fromBytes(bytes: ByteArray): BluetoothAddress {
            if (bytes.size != 6) {
                throw BlueLibValidationException.InvalidAddress(
                    "expected six octets, got ${bytes.size}",
                )
            }
            val text = bytes.joinToString(":") { byte ->
                (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
            }
            return BluetoothAddress(text)
        }

        private fun uppercaseAddress(value: String): String =
            value.split(':').joinToString(":") { it.uppercase() }
    }
}
