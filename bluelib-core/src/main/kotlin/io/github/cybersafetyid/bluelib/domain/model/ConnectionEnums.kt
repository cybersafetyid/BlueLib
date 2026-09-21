package io.github.cybersafetyid.bluelib.domain.model

/** State of a GATT connection as BlueLib models it. */
public enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    READY,
    DISCONNECTING,
    RECONNECTING,
    CLOSED,
}

/** Bond (pairing) state of a remote device. */
public enum class BondState {
    NONE,
    BONDING,
    BONDED,
    ;

    public companion object {
        /** `BluetoothDevice.BOND_NONE`. */
        public const val PLATFORM_NONE: Int = 10

        /** `BluetoothDevice.BOND_BONDING`. */
        public const val PLATFORM_BONDING: Int = 11

        /** `BluetoothDevice.BOND_BONDED`. */
        public const val PLATFORM_BONDED: Int = 12

        /**
         * Maps a `BluetoothDevice.getBondState()` value.
         *
         * The platform constants are part of the public API since Android 5.0 and their values
         * (`10`, `11`, `12`) have never changed, so the mapping lives in the domain layer where it can
         * be unit tested on the JVM — `BOND_*` constants cannot be referenced from a pure Kotlin module.
         */
        public fun fromPlatformValue(value: Int): BondState = when (value) {
            PLATFORM_BONDING -> BONDING
            PLATFORM_BONDED -> BONDED
            else -> NONE
        }
    }
}

/**
 * Connection priority handed to the controller.
 *
 * `DCK` (distributed connection kit) was added in Android 14 (API 34) and `LOW`/`BALANCED`/`HIGH`
 * exist since Android 5.0 (API 21).
 */
public enum class ConnectionPriority {
    /** Low power, high latency: the right default for sensors that report slowly. */
    LOW,

    /** Balanced interval; the platform default. */
    BALANCED,

    /** Low latency; keep it for short bursts because it drains both batteries. */
    HIGH,

    /** Distributed connection kit priority (Android 14 / API 34 and newer). */
    DCK,
    ;

    /** The `BluetoothGatt.CONNECTION_PRIORITY_*` constant this value maps to on API 21–33. */
    public val legacyPlatformValue: Int
        get() = when (this) {
            LOW -> 0
            BALANCED -> 1
            HIGH -> 2
            DCK -> 3
        }
}

/**
 * LE PHY selection as a bitmask, mirroring `BluetoothDevice.PHY_LE_*` constants.
 *
 * `HDT` (high data throughput, Bluetooth 6.x) is only available from Android 17 (API 37) and is
 * reported by `BluetoothAdapter.isLeHighDataThroughputPhySupported()`.
 */
public enum class Phy(public val mask: Int) {
    LE_1M(0x01),
    LE_2M(0x02),
    LE_CODED(0x04),
    LE_HDT(0x08),
    ;

    /** PHY mask meaning "no preference". */
    public companion object {
        public const val NO_PREFERRED_OPTION: Int = 0

        /** Every PHY BlueLib knows about. */
        public val ALL_MASK: Int = entries.fold(0) { acc, phy -> acc or phy.mask }

        /** Decodes a mask into the list of selected PHYs. */
        public fun fromMask(mask: Int): List<Phy> = entries.filter { (mask and it.mask) != 0 }

        /** `true` when [mask] only sets bits BlueLib understands and is not empty. */
        public fun isValidMask(mask: Int): Boolean = mask != 0 && (mask and ALL_MASK.inv()) == 0
    }
}

/** Coding scheme applied when a PHY is `LE_CODED`. */
public enum class PhyCoding {
    /** 125 kbit/s, longest range. */
    S2,

    /** 500 kbit/s. */
    S8,
    ;
}
