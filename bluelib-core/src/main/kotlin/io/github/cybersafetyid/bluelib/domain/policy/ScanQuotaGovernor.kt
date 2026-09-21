package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.port.ClockPort

/**
 * Enforces Android's undocumented-but-real scan quota: an app may start at most **five scans in any
 * 30 second window** (throttling landed in Android 7/8 and reports
 * `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` from Android 13). Exceeding it silences the scanner for the
 * rest of the window, which looks exactly like "my device is not advertising".
 *
 * The governor is a small, deterministic sliding window: it takes the current timestamp from a
 * [io.github.cybersafetyid.bluelib.port.ClockPort] so tests can drive it without sleeping.
 */
public class ScanQuotaGovernor(
    private val clock: ClockPort,
    private val maxStartsPerWindow: Int = DEFAULT_MAX_STARTS,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val startTimestamps = ArrayDeque<Long>()

    init {
        require(maxStartsPerWindow >= 1) { "maxStartsPerWindow must be >= 1" }
        require(windowMillis > 0) { "windowMillis must be positive" }
    }

    /** Result of asking for permission to start a scan. */
    public sealed interface Decision {
        /** The scan may start. */
        public data object Allowed : Decision

        /** The scan must wait; retry after [retryAfterMillis]. */
        public data class Throttled(val retryAfterMillis: Long) : Decision
    }

    /** Number of scan starts still available in the current window. */
    public val remainingBudget: Int
        get() {
            prune(clock.nowMillis())
            return (maxStartsPerWindow - startTimestamps.size).coerceAtLeast(0)
        }

    /** Timestamp at which the window forgets its oldest start. */
    public fun nextAvailableAtMillis(): Long {
        prune(clock.nowMillis())
        val oldest = startTimestamps.firstOrNull() ?: return clock.nowMillis()
        return oldest + windowMillis
    }

    /** Asks for permission to start a scan, consuming budget when allowed. */
    public fun acquire(): Decision {
        val now = clock.nowMillis()
        prune(now)
        if (startTimestamps.size >= maxStartsPerWindow) {
            return Decision.Throttled((startTimestamps.first() + windowMillis - now).coerceAtLeast(0L))
        }
        startTimestamps.addLast(now)
        return Decision.Allowed
    }

    /**
     * Records a scan that the platform rejected with `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`, so the
     * governor keeps the budget consistent with the stack's own view.
     */
    public fun recordPlatformThrottle() {
        val now = clock.nowMillis()
        prune(now)
        // Fill the window so no further start is allowed until it drains.
        while (startTimestamps.size < maxStartsPerWindow) {
            startTimestamps.addLast(now)
        }
    }

    /** Maps a throttled decision to the typed BlueLib error, or `null` when the scan may start. */
    public fun decisionToError(decision: Decision): BlueLibError? = when (decision) {
        Decision.Allowed -> null
        is Decision.Throttled -> BlueLibError.ScanThrottled(decision.retryAfterMillis)
    }

    private fun prune(now: Long) {
        val threshold = now - windowMillis
        while (startTimestamps.isNotEmpty() && startTimestamps.first() <= threshold) {
            startTimestamps.removeFirst()
        }
    }

    public companion object {
        /** Android allows five scan starts per 30 second window, per app. */
        public const val DEFAULT_MAX_STARTS: Int = 5

        /** Length of the quota window Android applies. */
        public const val DEFAULT_WINDOW_MILLIS: Long = 30_000L

        /** Minimum duration BlueLib waits before a stop/start cycle to avoid burning the quota. */
        public const val MIN_SCAN_DURATION_MILLIS: Long = 500L
    }
}
