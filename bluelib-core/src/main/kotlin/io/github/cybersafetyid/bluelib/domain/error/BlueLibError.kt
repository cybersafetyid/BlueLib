package io.github.cybersafetyid.bluelib.domain.error

import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.Phy

/**
 * Everything BlueLib can fail with, as data rather than exceptions.
 *
 * The hierarchy is intentionally closed so `when` expressions stay exhaustive for applications, and
 * every error carries [isRetryable] plus a [docsAnchor] pointing at the matching section of
 * `docs/troubleshooting.md`.
 */
public sealed interface BlueLibError {

    /** Stable machine readable code, safe to log or send to analytics. */
    public val code: BlueLibErrorCode

    /** Human readable description. */
    public val message: String

    /** Underlying platform failure, when there is one. */
    public val cause: Throwable?

    /** `true` when retrying the operation can plausibly succeed. */
    public val isRetryable: Boolean

    /** Anchor of the troubleshooting section that explains how to react. */
    public val docsAnchor: String

    /** Structured context for logs, key/value and stable across releases. */
    public val context: Map<String, String>
        get() = emptyMap()

    /** The adapter is missing or the hardware does not expose Bluetooth. */
    public data class AdapterUnavailable(
        override val message: String = "This device does not expose a Bluetooth adapter.",
        override val cause: Throwable? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.ADAPTER_UNAVAILABLE
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "adapter-unavailable"
    }

    /** Bluetooth is turned off. BlueLib never calls the deprecated `enable()`/`disable()` APIs. */
    public data object BluetoothDisabled : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.BLUETOOTH_DISABLED
        override val message: String get() = "Bluetooth is turned off; ask the user with ACTION_REQUEST_ENABLE."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "bluetooth-disabled"
    }

    /**
     * A permission required by the operation has not been granted.
     *
     * [permissions] lists the exact manifest permissions, which differ between Android 11 and older
     * (`BLUETOOTH`, `ACCESS_FINE_LOCATION`) and Android 12 and newer
     * (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`).
     */
    public data class PermissionMissing(
        public val operation: String,
        public val permissions: List<String>,
        public val permanentlyDenied: Boolean = false,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.PERMISSION_MISSING
        override val message: String
            get() = "Missing ${permissions.joinToString()} for operation '$operation'" +
                if (permanentlyDenied) " (denied permanently: send the user to app settings)." else "."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "permission-missing"
        override val context: Map<String, String>
            get() = mapOf("operation" to operation, "permissions" to permissions.joinToString(","))
    }

    /** The device or the Android version cannot do what was asked. */
    public data class FeatureUnsupported(
        public val feature: String,
        public val currentApiLevel: Int,
        public val requiredApiLevel: Int,
        override val message: String =
            "Feature '$feature' needs Android API $requiredApiLevel (device runs API $currentApiLevel).",
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.FEATURE_UNSUPPORTED
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "feature-unsupported"
        override val context: Map<String, String>
            get() = mapOf(
                "feature" to feature,
                "apiLevel" to currentApiLevel.toString(),
                "requiredApiLevel" to requiredApiLevel.toString(),
            )
    }

    /** Android's scan quota (five scan starts per 30 seconds, per app) rejected this start. */
    public data class ScanThrottled(
        public val retryAfterMillis: Long,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.SCAN_THROTTLED
        override val message: String get() = "Scan rejected by Android's quota; retry in ${retryAfterMillis}ms."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "scan-throttled"
    }

    /** A scan is already running; BlueLib refuses to start a second one. */
    public data object ScanAlreadyActive : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.SCAN_ALREADY_ACTIVE
        override val message: String get() = "A scan is already active. Stop it before starting another one."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "scan-already-active"
    }

    /** The platform reported `onScanFailed`. */
    public data class ScanFailed(
        public val reason: ScanFailureReason,
        public val platformErrorCode: Int? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.SCAN_FAILED
        override val message: String get() = "Scan failed: $reason (platform code ${platformErrorCode ?: "n/a"})."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean
            get() = reason in setOf(
                ScanFailureReason.OUT_OF_HARDWARE_RESOURCES,
                ScanFailureReason.SCANNING_TOO_FREQUENTLY,
                ScanFailureReason.INTERNAL_ERROR,
            )
        override val docsAnchor: String get() = "scan-failed"
    }

    /** The platform reported `onStartFailure`/`onAdvertisingSetStopped` for advertising. */
    public data class AdvertiseFailed(
        public val reason: String,
        public val platformErrorCode: Int? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.ADVERTISE_FAILED
        override val message: String get() = "Advertising failed: $reason (platform code ${platformErrorCode ?: "n/a"})."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "advertise-failed"
    }

    /** A GATT service was not found on the peripheral. */
    public data class ServiceNotFound(
        public val service: BluetoothUuid,
        public val discovered: List<BluetoothUuid> = emptyList(),
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.SERVICE_NOT_FOUND
        override val message: String get() = "Service $service is not present on the peripheral."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "service-not-found"
        override val context: Map<String, String>
            get() = mapOf("service" to service.toString(), "discovered" to discovered.joinToString())
    }

    /** A characteristic was not found inside a service. */
    public data class CharacteristicNotFound(
        public val service: BluetoothUuid,
        public val characteristic: BluetoothUuid,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.CHARACTERISTIC_NOT_FOUND
        override val message: String get() = "Characteristic $characteristic is missing from service $service."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "characteristic-not-found"
    }

    /** `subscribe` was called on a characteristic that has no notify or indicate property. */
    public data class CharacteristicNotNotifiable(
        public val characteristic: BluetoothUuid,
        public val properties: Int,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.CHARACTERISTIC_NOT_NOTIFIABLE
        override val message: String
            get() = "Characteristic $characteristic cannot be subscribed (properties=0x${properties.toString(16)}): " +
                "it declares neither NOTIFY nor INDICATE."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "characteristic-not-notifiable"
    }

    /** A read, write, descriptor or discovery operation failed with a GATT status. */
    public data class GattOperationFailed(
        public val operation: String,
        public val status: GattStatus,
        public val device: BluetoothDeviceId? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.GATT_OPERATION_FAILED
        override val message: String get() = "GATT '$operation' failed: $status — ${status.description}"
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = status.retryable
        override val docsAnchor: String get() = "gatt-operation-failed"
        override val context: Map<String, String>
            get() = buildMap {
                put("operation", operation)
                put("status", status.name)
                put("statusCode", status.code.toString())
                device?.let { put("device", it.address.value) }
            }
    }

    /** A connection attempt failed. */
    public data class ConnectionFailed(
        public val device: BluetoothDeviceId,
        public val status: GattStatus? = null,
        public val attempt: Int = 1,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.CONNECTION_FAILED
        override val message: String
            get() = "Connecting to ${device.address.value} failed (attempt $attempt)" +
                (status?.let { " with $it" } ?: "") + "."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = status?.retryable ?: true
        override val docsAnchor: String get() = "connection-failed"
        override val context: Map<String, String>
            get() = buildMap {
                put("device", device.address.value)
                put("attempt", attempt.toString())
                status?.let { put("status", it.name) }
            }
    }

    /** An established connection went away. */
    public data class ConnectionLost(
        public val device: BluetoothDeviceId,
        public val status: GattStatus? = null,
        public val bondLossReason: BondLossReason? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.CONNECTION_LOST
        override val message: String
            get() = "Connection to ${device.address.value} was lost" +
                (status?.let { " ($it)" } ?: "") + "."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = bondLossReason == null
        override val docsAnchor: String get() = "connection-lost"
    }

    /** Pairing failed. */
    public data class BondFailed(
        public val device: BluetoothDeviceId,
        public val reason: String,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.BOND_FAILED
        override val message: String get() = "Pairing with ${device.address.value} failed: $reason."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "bond-failed"
    }

    /**
     * The bond with a paired device was lost.
     *
     * [systemRepairInProgress] is `true` on Android 17 (API 37) when the platform is running its
     * autonomous re-pairing flow: in that case BlueLib recommends *not* prompting the user, because
     * Android will ask for consent itself and only broadcasts `ACTION_KEY_MISSING` when its attempt
     * fails.
     */
    public data class BondLost(
        public val device: BluetoothDeviceId,
        public val reason: BondLossReason = BondLossReason.UNKNOWN,
        public val systemRepairInProgress: Boolean = false,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.BOND_LOST
        override val message: String
            get() = "Bond with ${device.address.value} was lost ($reason)" +
                if (systemRepairInProgress) "; Android is re-pairing automatically." else "."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "bond-lost"
    }

    /** The link could not be encrypted, even though the operation needed encryption. */
    public data class EncryptionFailed(
        public val device: BluetoothDeviceId,
        public val status: GattStatus? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.ENCRYPTION_FAILED
        override val message: String
            get() = "Link to ${device.address.value} is not encrypted" + (status?.let { " ($it)" } ?: "") + "."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "encryption-failed"
    }

    /** MTU negotiation did not reach the requested value and the result is below the ATT minimum. */
    public data class MtuNegotiationFailed(
        public val requested: Int,
        public val negotiated: Int,
        public val minimum: Int = 23,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.MTU_NEGOTIATION_FAILED
        override val message: String
            get() = "MTU negotiation asked for $requested and got $negotiated, below the $minimum byte minimum."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "mtu-negotiation"
    }

    /** An operation did not complete inside its deadline. */
    public data class Timeout(
        public val operation: String,
        public val timeoutMillis: Long,
        public val device: BluetoothDeviceId? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.TIMEOUT
        override val message: String get() = "'$operation' did not complete within ${timeoutMillis}ms."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "timeout"
    }

    /** The request contradicts the current state of the resource. */
    public data class OperationRejected(
        public val reason: String,
        public val hint: String = "",
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.OPERATION_REJECTED
        override val message: String get() = "Operation rejected: $reason" + if (hint.isNotEmpty()) " ($hint)" else ""
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "operation-rejected"
    }

    /** The resource was already released. Using it is a programming error, surfaced as data. */
    public data class Closed(
        public val resource: String,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.CLOSED
        override val message: String get() = "'$resource' has been closed and cannot be used any more."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "resource-closed"
    }

    /** The coroutine performing the operation was cancelled. */
    public data class Cancelled(
        public val operation: String,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.CANCELLED
        override val message: String get() = "'$operation' was cancelled."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "cancelled"
    }

    /** Something that BlueLib could not classify; always carries the original throwable. */
    public data class Unexpected(
        override val message: String,
        override val cause: Throwable,
        public val operation: String? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.UNEXPECTED
        override val isRetryable: Boolean get() = false
        override val docsAnchor: String get() = "unexpected"
    }

    /** A PHY update request was rejected by the peripheral. */
    public data class PhyUpdateFailed(
        public val device: BluetoothDeviceId,
        public val requested: Phy,
        public val status: GattStatus? = null,
    ) : BlueLibError {
        override val code: BlueLibErrorCode get() = BlueLibErrorCode.PHY_UPDATE_FAILED
        override val message: String get() = "PHY $requested was rejected for ${device.address.value}."
        override val cause: Throwable? get() = null
        override val isRetryable: Boolean get() = true
        override val docsAnchor: String get() = "phy-update"
    }
}

/** Stable error codes for logging and analytics. */
public enum class BlueLibErrorCode {
    ADAPTER_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    PERMISSION_MISSING,
    FEATURE_UNSUPPORTED,
    SCAN_THROTTLED,
    SCAN_ALREADY_ACTIVE,
    SCAN_FAILED,
    ADVERTISE_FAILED,
    SERVICE_NOT_FOUND,
    CHARACTERISTIC_NOT_FOUND,
    CHARACTERISTIC_NOT_NOTIFIABLE,
    GATT_OPERATION_FAILED,
    CONNECTION_FAILED,
    CONNECTION_LOST,
    BOND_FAILED,
    BOND_LOST,
    ENCRYPTION_FAILED,
    MTU_NEGOTIATION_FAILED,
    PHY_UPDATE_FAILED,
    TIMEOUT,
    OPERATION_REJECTED,
    CLOSED,
    CANCELLED,
    UNEXPECTED,
}

/** Reasons `onScanFailed` reports, normalised across API levels. */
public enum class ScanFailureReason {
    ALREADY_STARTED,
    APP_REGISTRATION_FAILED,
    INTERNAL_ERROR,
    FEATURE_UNSUPPORTED,
    OUT_OF_HARDWARE_RESOURCES,
    SCANNING_TOO_FREQUENTLY,
    UNKNOWN,
}

/** Bond loss reasons reported through `BluetoothDevice.EXTRA_BOND_LOSS_REASON` (Android 16.1+). */
public enum class BondLossReason {
    UNKNOWN,
    BREDR_AUTH_FAILURE,
    BREDR_INCOMING_PAIRING,
    LE_ENCRYPT_FAILURE,
    LE_INCOMING_PAIRING,
}
