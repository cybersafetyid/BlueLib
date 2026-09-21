package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError

/**
 * How BlueLib negotiates the ATT MTU.
 *
 * Facts this encodes, all verified against the platform behaviour documented in
 * `docs/research/gatt-pitfalls.md`:
 *
 * * the ATT default is 23 bytes, so a client that never negotiates can only exchange 20 byte
 *   payloads;
 * * `requestMtu` has been available since Android 5.0 (API 21), but Android caps the granted value
 *   at 517 regardless of what the peripheral offers;
 * * many peripherals grant a *smaller* MTU than requested, and some answer with 23 even when they
 *   could do better, so the negotiated value must always be read back instead of assumed;
 * * BlueLib therefore requests a target, accepts anything `>= 23`, and reports the delta so
 *   applications can react (for example by switching to chunked writes).
 */
public object MtuNegotiationPolicy {

    /** ATT default MTU, used before any negotiation happens. */
    public const val MINIMUM_MTU: Int = 23

    /** Largest MTU Android's stack accepts. */
    public const val MAXIMUM_MTU: Int = 517

    /** Target BlueLib requests when the caller expresses no preference. */
    public const val DEFAULT_TARGET_MTU: Int = MAXIMUM_MTU

    /** Result of a negotiation. */
    public data class Result(
        val requested: Int,
        val negotiated: Int,
    ) {
        /** Bytes available for characteristic values, i.e. `MTU - 3`. */
        public val usablePayloadBytes: Int
            get() = negotiated - 3

        /** `true` when the peripheral granted everything that was asked for. */
        public val metRequest: Boolean
            get() = negotiated >= requested

        /** How many bytes the negotiation fell short of the request. */
        public val shortfallBytes: Int
            get() = (requested - negotiated).coerceAtLeast(0)
    }

    /** Clamps [desired] into the range Android accepts. `null` means "use the target default". */
    public fun plan(desired: Int?): Int =
        (desired ?: DEFAULT_TARGET_MTU).coerceIn(MINIMUM_MTU, MAXIMUM_MTU)

    /**
     * Evaluates a negotiated MTU.
     *
     * @return [Result] when the negotiation is usable, or a
     *   [BlueLibError.MtuNegotiationFailed] when the device answered with an impossible value.
     */
    public fun evaluate(plan: Int, negotiated: Int): NegotiationOutcome = when {
        negotiated < MINIMUM_MTU -> NegotiationOutcome.Failed(
            BlueLibError.MtuNegotiationFailed(requested = plan, negotiated = negotiated, minimum = MINIMUM_MTU),
        )

        negotiated > MAXIMUM_MTU -> NegotiationOutcome.Negotiated(Result(plan, MAXIMUM_MTU))
        else -> NegotiationOutcome.Negotiated(Result(plan, negotiated))
    }

    /** Either a usable outcome or a typed failure. */
    public sealed interface NegotiationOutcome {
        public data class Negotiated(val result: Result) : NegotiationOutcome
        public data class Failed(val error: BlueLibError) : NegotiationOutcome
    }
}
