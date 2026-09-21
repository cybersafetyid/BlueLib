package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import java.util.UUID

/**
 * A Bluetooth UUID with the validation the platform skips.
 *
 * The class understands both 16 bit (SIG assigned), 32 bit and full 128 bit forms, and knows the
 * base UUID used to expand short forms (`0000xxxx-0000-1000-8000-00805F9B34FB`).
 */
@JvmInline
public value class BluetoothUuid private constructor(public val uuid: UUID) {

    /** `true` when this UUID was created from a 16 bit short value. */
    public val isShort16Bit: Boolean
        get() = uuid.toString().endsWith(BASE_UUID_SUFFIX) && uuid.toString().startsWith("0000")

    /**
     * The 16 bit value of this UUID when it belongs to the SIG base UUID range, otherwise `null`.
     * Useful for building readable logs and for the `ServiceData` advertising fields.
     */
    public val shortValue: Int?
        get() {
            val text = uuid.toString()
            if (!text.endsWith(BASE_UUID_SUFFIX)) return null
            val hex = text.substring(0, 8)
            if (!hex.startsWith("0000")) return null
            return hex.substring(4, 8).toInt(16)
        }

    override fun toString(): String = uuid.toString().lowercase()

    public companion object {
        /** The SIG base UUID suffix shared by all assigned 16/32 bit UUIDs. */
        public const val BASE_UUID_SUFFIX: String = "-0000-1000-8000-00805f9b34fb"

        /**
         * The all-zero UUID, used where no attribute is addressed — for instance an ATT Execute Write,
         * which carries no attribute handle at all.
         */
        public val UNKNOWN: BluetoothUuid = BluetoothUuid(UUID(0L, 0L))

        /**
         * Well-known GATT service UUIDs used across guides, tests and the sample app.
         */
        public val GENERIC_ACCESS: BluetoothUuid = fromShort(0x1800)
        public val GENERIC_ATTRIBUTE: BluetoothUuid = fromShort(0x1801)
        public val DEVICE_INFORMATION: BluetoothUuid = fromShort(0x180A)
        public val HEART_RATE: BluetoothUuid = fromShort(0x180D)
        public val BATTERY_SERVICE: BluetoothUuid = fromShort(0x180F)
        public val CLIENT_CHARACTERISTIC_CONFIGURATION: BluetoothUuid = fromShort(0x2902)
        public val CHARACTERISTIC_USER_DESCRIPTION: BluetoothUuid = fromShort(0x2901)

        /** Creates a UUID from a SIG assigned 16 bit value. */
        public fun fromShort(value: Int): BluetoothUuid {
            if (value !in 0x0000..0xFFFF) {
                throw BlueLibValidationException.InvalidUuid(
                    "16 bit UUID must be within 0x0000..0xFFFF, got ${value.toString(16)}",
                )
            }
            val hex = value.toString(16).padStart(4, '0')
            return BluetoothUuid(UUID.fromString("0000$hex$BASE_UUID_SUFFIX"))
        }

        /** Creates a UUID from a SIG assigned 32 bit value. */
        public fun fromInt(value: Long): BluetoothUuid {
            if (value !in 0x00000000L..0xFFFFFFFFL) {
                throw BlueLibValidationException.InvalidUuid(
                    "32 bit UUID must be within 0x00000000..0xFFFFFFFF, got ${value.toString(16)}",
                )
            }
            val hex = value.toString(16).padStart(8, '0')
            return BluetoothUuid(UUID.fromString("$hex$BASE_UUID_SUFFIX"))
        }

        /** Creates a random 128 bit UUID, as used for custom vendor services. */
        public fun random(): BluetoothUuid = BluetoothUuid(UUID.randomUUID())

        /**
         * Parses a UUID in its canonical form.
         *
         * @throws BlueLibValidationException when [raw] is not a valid UUID.
         */
        public fun parse(raw: String): BluetoothUuid {
            val candidate = raw.trim()
            return try {
                BluetoothUuid(UUID.fromString(candidate))
            } catch (illegalArgument: IllegalArgumentException) {
                throw BlueLibValidationException.InvalidUuid(raw, illegalArgument)
            }
        }

        /** Parses [raw], returning `null` for malformed input. */
        public fun parseOrNull(raw: String?): BluetoothUuid? =
            raw?.trim()?.let { runCatching { parse(it) }.getOrNull() }

        /** Wraps an existing [UUID]. */
        public fun of(uuid: UUID): BluetoothUuid = BluetoothUuid(uuid)
    }
}
