package io.github.cybersafetyid.bluelib.domain.error

/**
 * Thrown when a BlueLib API is handed input that cannot be sent to the platform without risking a
 * crash, a silent failure or corrupted data.
 *
 * Every subclass carries the offending value so applications can build readable messages, and each
 * one maps to a documentation anchor under `docs/troubleshooting.md`.
 */
public sealed class BlueLibValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {

    /** Documentation anchor for this validation failure. */
    public abstract val docsAnchor: String

    /** A [BluetoothAddress][io.github.cybersafetyid.bluelib.domain.model.BluetoothAddress] was malformed. */
    public class InvalidAddress(
        raw: String,
    ) : BlueLibValidationException(
        "Not a valid Bluetooth address: '$raw'. Expected six octets such as 00:11:22:33:44:55.",
    ) {
        override val docsAnchor: String = "invalid-address"
    }

    /** A UUID was malformed. */
    public class InvalidUuid(
        raw: String,
        cause: Throwable? = null,
    ) : BlueLibValidationException("Not a valid UUID: '$raw'.", cause) {
        override val docsAnchor: String = "invalid-uuid"
    }

    /** An MTU value is outside the range the Bluetooth specification allows. */
    public class InvalidMtu(
        public val requested: Int,
        allowedRange: IntRange = MINIMUM_MTU..MAXIMUM_MTU,
    ) : BlueLibValidationException(
        "MTU $requested is invalid: the ATT default is $MINIMUM_MTU and Android refuses values " +
            "above $MAXIMUM_MTU (allowed: $allowedRange).",
    ) {
        override val docsAnchor: String = "invalid-mtu"
    }

    /** A PHY mask is empty or contains undefined bits. */
    public class InvalidPhyMask(
        public val mask: Int,
        validMask: Int,
    ) : BlueLibValidationException(
        "PHY mask 0x${mask.toString(16)} is invalid, expected a non-empty subset of 0x${validMask.toString(16)}.",
    ) {
        override val docsAnchor: String = "invalid-phy"
    }

    /** An advertising, scan response or service data payload exceeds the advertising budget. */
    public class AdvertisingDataTooLarge(
        public val section: String,
        public val requestedBytes: Int,
        public val maximumBytes: Int,
    ) : BlueLibValidationException(
        "Advertising section '$section' needs $requestedBytes bytes but only $maximumBytes are " +
            "available. Legacy advertising data is limited to 31 bytes; switch to extended " +
            "advertising (Android 8.0+) for up to 1650 bytes.",
    ) {
        override val docsAnchor: String = "advertising-data-too-large"
    }

    /** A GATT payload cannot be written with the requested write mode. */
    public class InvalidPayload(
        public val operation: String,
        public val sizeBytes: Int,
        public val allowed: IntRange,
        public val hint: String,
    ) : BlueLibValidationException(
        "$operation rejects a $sizeBytes byte payload (allowed: $allowed). $hint",
    ) {
        override val docsAnchor: String = "invalid-payload"
    }

    /** A numeric value (RSSI, scan interval, TX power, manufacturer id, ...) is out of range. */
    public class ValueOutOfRange(
        public val parameter: String,
        public val value: Long,
        public val allowed: LongRange,
    ) : BlueLibValidationException(
        "$parameter=$value is out of the supported range $allowed.",
    ) {
        override val docsAnchor: String = "value-out-of-range"
    }

    /** A state machine rejected a transition because the current state does not allow it. */
    public class IllegalState(
        public val stateMachine: String,
        public val from: String,
        public val to: String,
        public val hint: String,
    ) : BlueLibValidationException(
        "$stateMachine cannot move from $from to $to. $hint",
    ) {
        override val docsAnchor: String = "illegal-state"
    }

    /** Two `BlueLib` calls that must be serialised were issued at the same time. */
    public class ConcurrentOperation(
        public val device: String,
        inFlight: String,
        attempted: String,
    ) : BlueLibValidationException(
        "A '$inFlight' operation is already running for $device; '$attempted' was rejected. " +
            "BlueLib serialises GATT work per device to avoid Android's silent operation drops.",
    ) {
        override val docsAnchor: String = "concurrent-operations"
    }

    /** An [AutoPairFilter][io.github.cybersafetyid.bluelib.domain.model.AutoPairFilter] was provided without any matching criteria. */
    public class EmptyFilter(
        message: String = "AutoPairFilter must specify at least one matching criterion.",
    ) : BlueLibValidationException(message) {
        override val docsAnchor: String = "empty-filter"
    }

    public companion object {
        /** ATT minimum MTU required by the Bluetooth specification. */
        public const val MINIMUM_MTU: Int = 23

        /** Largest MTU Android's Bluetooth stack accepts. */
        public const val MAXIMUM_MTU: Int = 517
    }
}
