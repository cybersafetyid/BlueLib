package io.github.cybersafetyid.bluelib.port

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingHandle
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingRequest
import io.github.cybersafetyid.bluelib.domain.model.ScanLostReason
import io.github.cybersafetyid.bluelib.domain.model.ScanObservation
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import kotlinx.coroutines.flow.Flow

/** Something the scanner reported. */
public sealed interface ScanEvent {
    /** A device was seen. */
    public data class Observed(val observation: ScanObservation) : ScanEvent

    /** A device stopped advertising, or was not seen for a while. */
    public data class Lost(
        public val observation: ScanObservation,
        public val reason: ScanLostReason,
    ) : ScanEvent

    /** The scan failed and stopped. */
    public data class Failed(val error: BlueLibError) : ScanEvent
}

/**
 * The scanning port.
 *
 * Implementations must:
 *
 * * consult the scan quota governor before calling `startScan`,
 * * translate `ScanCallback.onScanFailed` into [ScanEvent.Failed],
 * * stop the platform scan when the collecting coroutine is cancelled or when
 *   [ScanRequest.autoStopAfterMillis] elapses.
 */
public interface BleScanPort {
    /** Starts a scan and emits until cancelled or the request's auto stop elapses. */
    public fun scan(request: ScanRequest): Flow<ScanEvent>

    /** `true` while a platform scan is running for this port. */
    public val isScanning: Boolean
}

/** Advertising port. */
public interface BleAdvertisePort {
    /** `true` when the hardware supports advertising at all. */
    public val isAdvertiserAvailable: Boolean

    /**
     * Starts advertising and suspends until the set stops.
     *
     * @throws io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException when the
     *   payload does not fit or the parameters contradict each other.
     */
    public suspend fun start(request: AdvertisingRequest): AdvertisingHandle

    /** Stops advertising for [handle]. */
    public suspend fun stop(handle: AdvertisingHandle)

    /** Stops every advertising set owned by this port. */
    public suspend fun stopAll()
}
