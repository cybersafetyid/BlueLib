package io.github.cybersafetyid.bluelib

import io.github.cybersafetyid.bluelib.domain.policy.BackoffPolicy
import io.github.cybersafetyid.bluelib.domain.policy.ReconnectPolicy
import io.github.cybersafetyid.bluelib.domain.policy.RetryPolicy
import io.github.cybersafetyid.bluelib.domain.policy.ScanQuotaGovernor
import io.github.cybersafetyid.bluelib.domain.validation.PayloadSegmenter

/**
 * Runtime configuration of a `BlueLib` instance.
 *
 * Every default is chosen to match Android's real behaviour rather than the happy path, and every
 * value is validated so a misconfiguration fails at construction time.
 */
public data class BlueLibConfig(
    /** Timeout applied to a single platform callback, such as a characteristic read. */
    val operationTimeoutMillis: Long = 10_000L,
    /** Timeout for connect plus service discovery. */
    val connectTimeoutMillis: Long = 15_000L,
    /** Retry policy for retryable GATT failures. */
    val gattRetryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 3, backoff = BackoffPolicy(initialDelayMillis = 250L, maxDelayMillis = 4_000L)),
    /** Reconnection policy applied when a link drops. */
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    /** Requested ATT MTU when a caller does not specify one. */
    val defaultMtu: Int = PayloadSegmenter.MAX_MTU,
    /** Whether BlueLib should request the 2M PHY automatically after connecting. */
    val preferHighThroughputPhy: Boolean = false,
    /** Scan quota Android enforces; only change it if an OEM documents different limits. */
    val scanStartsPerWindow: Int = ScanQuotaGovernor.DEFAULT_MAX_STARTS,
    /** Length of the scan quota window. */
    val scanQuotaWindowMillis: Long = ScanQuotaGovernor.DEFAULT_WINDOW_MILLIS,
    /**
     * `true` to treat Android's `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` as fatal for the current
     * window. Keeping it on avoids the "my scan silently returns nothing" trap.
     */
    val respectPlatformScanThrottle: Boolean = true,
    /** Request the discoverable flag on advertising sets when the API level allows it. */
    val advertiseDiscoverableWhenSupported: Boolean = false,
    /**
     * Wall-clock budget for a whole bond procedure. Pairing needs user interaction, so this is
     * deliberately generous.
     */
    val bondTimeoutMillis: Long = 30_000L,
    /** Keep optimistic writes enabled: `WITHOUT_RESPONSE` writes resolve as soon as they are queued. */
    val optimisticWriteWithoutResponse: Boolean = true,
    /** Emit diagnostics events; disable for the lowest possible overhead in production. */
    val diagnosticsEnabled: Boolean = true,
) {
    init {
        require(operationTimeoutMillis > 0) { "operationTimeoutMillis must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(defaultMtu in PayloadSegmenter.DEFAULT_MTU..PayloadSegmenter.MAX_MTU) {
            "defaultMtu must be within ${PayloadSegmenter.DEFAULT_MTU}..${PayloadSegmenter.MAX_MTU}"
        }
        require(scanStartsPerWindow >= 1) { "scanStartsPerWindow must be >= 1" }
        require(scanQuotaWindowMillis > 0) { "scanQuotaWindowMillis must be positive" }
        require(bondTimeoutMillis > 0) { "bondTimeoutMillis must be positive" }
    }
}

/** Builds a [BlueLibConfig] with only the values that differ from the defaults. */
public fun blueLibConfig(configure: BlueLibConfigBuilder.() -> Unit): BlueLibConfig =
    BlueLibConfigBuilder().apply(configure).build()

/** Mutable builder for [BlueLibConfig]. */
public class BlueLibConfigBuilder {
    private var operationTimeoutMillis: Long = 10_000L
    private var connectTimeoutMillis: Long = 15_000L
    private var defaultMtu: Int = PayloadSegmenter.MAX_MTU
    private var preferHighThroughputPhy: Boolean = false
    private var diagnosticsEnabled: Boolean = true

    /** Sets the per operation timeout. */
    public fun operationTimeoutMillis(value: Long): BlueLibConfigBuilder = apply { operationTimeoutMillis = value }

    /** Sets the connect timeout. */
    public fun connectTimeoutMillis(value: Long): BlueLibConfigBuilder = apply { connectTimeoutMillis = value }

    /** Sets the default MTU. */
    public fun defaultMtu(value: Int): BlueLibConfigBuilder = apply { defaultMtu = value }

    /** Requests the 2M PHY automatically after connecting. */
    public fun preferHighThroughputPhy(value: Boolean): BlueLibConfigBuilder = apply { preferHighThroughputPhy = value }

    /** Enables or disables diagnostics collection. */
    public fun diagnosticsEnabled(value: Boolean): BlueLibConfigBuilder = apply { diagnosticsEnabled = value }

    /** Builds the immutable configuration. */
    public fun build(): BlueLibConfig = BlueLibConfig(
        operationTimeoutMillis = operationTimeoutMillis,
        connectTimeoutMillis = connectTimeoutMillis,
        defaultMtu = defaultMtu,
        preferHighThroughputPhy = preferHighThroughputPhy,
        diagnosticsEnabled = diagnosticsEnabled,
    )
}
