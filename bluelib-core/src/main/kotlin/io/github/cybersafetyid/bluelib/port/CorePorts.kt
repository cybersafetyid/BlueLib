package io.github.cybersafetyid.bluelib.port

import io.github.cybersafetyid.bluelib.domain.BluetoothFeature
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Injectable wall clock so policies and tests never call `System.currentTimeMillis()` directly. */
public fun interface ClockPort {
    /** Current time in milliseconds since the Unix epoch. */
    public fun nowMillis(): Long
}

/** Default clock backed by the system. */
public object SystemClockPort : ClockPort {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Everything BlueLib reports for observability. */
public sealed interface DiagnosticEvent {
    /** Timestamp the event was created. */
    public val timestampMillis: Long

    /** Adapter state changed. */
    public data class AdapterStateChanged(
        public val previous: String,
        public val current: String,
        override val timestampMillis: Long,
    ) : DiagnosticEvent

    /** Connection state changed. */
    public data class ConnectionStateChanged(
        public val deviceId: BluetoothDeviceId,
        public val previous: ConnectionState,
        public val current: ConnectionState,
        override val timestampMillis: Long,
    ) : DiagnosticEvent

    /** A platform operation started. */
    public data class OperationStarted(
        public val operation: String,
        public val deviceId: BluetoothDeviceId?,
        override val timestampMillis: Long,
    ) : DiagnosticEvent

    /** A platform operation finished. */
    public data class OperationFinished(
        public val operation: String,
        public val deviceId: BluetoothDeviceId?,
        public val durationMillis: Long,
        public val success: Boolean,
        override val timestampMillis: Long,
    ) : DiagnosticEvent

    /** A failure was mapped into the BlueLib error taxonomy. */
    public data class ErrorReported(
        public val error: BlueLibError,
        public val deviceId: BluetoothDeviceId?,
        override val timestampMillis: Long,
    ) : DiagnosticEvent

    /** The scan quota governor rejected a scan start. */
    public data class ScanThrottled(
        public val retryAfterMillis: Long,
        override val timestampMillis: Long,
    ) : DiagnosticEvent

    /** A capability was probed. Useful when filing OEM specific bug reports. */
    public data class CapabilityProbed(
        public val feature: BluetoothFeature,
        public val supported: Boolean,
        public val apiLevel: Int,
        override val timestampMillis: Long,
    ) : DiagnosticEvent
}

/** Sink for [DiagnosticEvent]s. */
public interface DiagnosticsPort {
    /** Hot stream of diagnostic events. */
    public val events: Flow<DiagnosticEvent>

    /** Publishes one event. */
    public fun emit(event: DiagnosticEvent)
}

/** State of a runtime permission. */
public enum class PermissionStatus {
    GRANTED,
    DENIED,
    /** Denied permanently: the user must change it from the app settings screen. */
    DENIED_PERMANENTLY,

    /** The platform does not know this permission on the running API level. */
    NOT_APPLICABLE,
}

/** A permission requirement discovered by the gateway. */
public data class PermissionRequirement(
    /** Android manifest permission name, e.g. `android.permission.BLUETOOTH_SCAN`. */
    public val permission: String,
    public val status: PermissionStatus,
)

/** Very small abstraction so permission checks can be faked in tests. */
public interface PermissionPort {
    /** Current status of one manifest permission. */
    public fun statusOf(permission: String): PermissionStatus
}

/** Adapter availability and capability surface. */
public interface AdapterAvailabilityPort {
    /** `true` when the device has a Bluetooth adapter at all. */
    public val isAdapterAvailable: Boolean

    /** Current adapter state as a hot stream. */
    public val adapterState: StateFlow<String>

    /** `true` when a feature is both present in the platform and supported by the hardware. */
    public fun isFeatureSupported(feature: BluetoothFeature): Boolean

    /** API level the library detected at runtime. */
    public val apiLevel: Int

    /** Minor API level (for example `1` on Android 16.1), or `0` when the platform cannot report it. */
    public val minorApiLevel: Int
}
