package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BondLossReason
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with full jitter.
 *
 * Full jitter (a uniform pick between 0 and the exponentially grown ceiling) is the variant that
 * behaves best when many devices reconnect after a power cut; BlueLib defaults to it instead of
 * "decorrelated" or "equal jitter" because reconnection storms are the common failure mode here.
 */
public data class BackoffPolicy(
    val initialDelayMillis: Long = 500L,
    val maxDelayMillis: Long = 30_000L,
    val multiplier: Double = 2.0,
    val jitter: Jitter = Jitter.FULL,
) {
    init {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(maxDelayMillis >= initialDelayMillis) { "maxDelayMillis must be >= initialDelayMillis" }
        require(multiplier >= 1.0) { "multiplier must be >= 1.0" }
    }

    /** Randomness strategy applied to each delay. */
    public enum class Jitter {
        NONE,
        FULL,
        EQUAL,
    }

    /** Delay ceiling for [attempt] (`attempt` starts at 1). */
    public fun ceilingFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val raw = initialDelayMillis * multiplier.pow(exponent)
        return min(raw.toLong(), maxDelayMillis)
    }

    /** Actual delay for [attempt], applying the jitter strategy. */
    public fun delayFor(attempt: Int, random: Random = Random.Default): Long {
        val ceiling = ceilingFor(attempt)
        return when (jitter) {
            Jitter.NONE -> ceiling
            Jitter.FULL -> random.nextLong(0L, ceiling + 1L)
            Jitter.EQUAL -> (ceiling / 2) + random.nextLong(0L, (ceiling / 2) + 1L)
        }
    }
}

/** Outcome of consulting a [RetryPolicy]. */
public sealed interface RetryDecision {
    /** Try again after [delayMillis]. */
    public data class Retry(val attempt: Int, val delayMillis: Long) : RetryDecision

    /** Stop retrying. */
    public data class GiveUp(val attempt: Int, val reason: String) : RetryDecision
}

/**
 * Bounded retry policy that only retries errors BlueLib marked as
 * [retryable][BlueLibError.isRetryable].
 */
public data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoff: BackoffPolicy = BackoffPolicy(),
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /** Decides what to do after [attempt] failed with [error]. */
    public fun afterFailure(
        attempt: Int,
        error: BlueLibError,
        random: Random = Random.Default,
    ): RetryDecision = when {
        !error.isRetryable -> RetryDecision.GiveUp(attempt, "'${error.code}' is not retryable")
        attempt >= maxAttempts -> RetryDecision.GiveUp(attempt, "reached the $maxAttempts attempt limit")
        else -> RetryDecision.Retry(attempt + 1, backoff.delayFor(attempt, random))
    }

    /** The delays a caller can expect, useful for documentation and tests. */
    public fun delaySequence(random: Random = Random.Default): List<Long> =
        (1 until maxAttempts).map { backoff.delayFor(it, random) }
}

/**
 * Reconnection policy.
 *
 * Bond loss needs special treatment: on Android 17 (API 37) the platform runs autonomous re-pairing
 * in the background and only broadcasts `ACTION_KEY_MISSING` when its own attempt failed, so a
 * reconnecting client must *not* assume a manual pairing flow is needed while the system is
 * repairing.
 */
public data class ReconnectPolicy(
    val enabled: Boolean = true,
    val maxAttempts: Int = 5,
    val backoff: BackoffPolicy = BackoffPolicy(initialDelayMillis = 1_000L, maxDelayMillis = 60_000L),
    val reconnectOnBondLoss: Boolean = false,
) {
    /** Decision for the next reconnection attempt. */
    public sealed interface Decision {
        /** Schedule a reconnect after [delayMillis]. */
        public data class Schedule(val attempt: Int, val delayMillis: Long, val reason: String) : Decision

        /** Do not reconnect; [reason] explains why. */
        public data class Stop(val reason: String) : Decision

        /** Let Android's autonomous re-pairing finish before reconnecting. */
        public data class AwaitSystemRepair(val reason: String) : Decision
    }

    /** Computes the next step after a connection was lost. */
    public fun onConnectionLost(
        attempt: Int,
        bondLossReason: BondLossReason? = null,
        systemRepairInProgress: Boolean = false,
        random: Random = Random.Default,
    ): Decision = when {
        !enabled -> Decision.Stop("reconnection is disabled by policy")
        systemRepairInProgress -> Decision.AwaitSystemRepair(
            "Android 17 is re-pairing the device automatically; reconnect once it finishes",
        )
        (bondLossReason != null) && (!reconnectOnBondLoss) -> Decision.Stop(
            "bond was lost ($bondLossReason); re-pairing requires user consent",
        )
        attempt >= maxAttempts -> Decision.Stop("reached the $maxAttempts reconnect attempt limit")
        else -> Decision.Schedule(
            attempt = attempt + 1,
            delayMillis = backoff.delayFor(attempt, random),
            reason = "connection attempt $attempt failed",
        )
    }
}
