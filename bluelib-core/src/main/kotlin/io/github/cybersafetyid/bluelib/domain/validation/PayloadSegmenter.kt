package io.github.cybersafetyid.bluelib.domain.validation

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.WriteMode

/**
 * Splits values into MTU sized chunks and validates write requests before they reach the platform.
 *
 * Android's `writeCharacteristic` silently truncates a value longer than `MTU - 3`, and long writes
 * executed by hand are a classic source of corrupted payloads. BlueLib makes the arithmetic explicit
 * and refuses impossible requests instead of producing partial writes.
 */
public object PayloadSegmenter {

    /** ATT write overhead: one opcode byte plus a two byte handle. */
    public const val ATT_WRITE_OVERHEAD: Int = 3

    /** Maximum value length of a prepared (long) write, per the ATT specification. */
    public const val MAX_LONG_WRITE_BYTES: Int = 512

    /** ATT minimum MTU. */
    public const val DEFAULT_MTU: Int = 23

    /** Largest MTU Android accepts. */
    public const val MAX_MTU: Int = 517

    /** Bytes available in a single ATT write for the given [mtu]. */
    public fun maxChunkSize(mtu: Int): Int {
        requireValidMtu(mtu)
        return mtu - ATT_WRITE_OVERHEAD
    }

    /** `true` when [value] fits in one write. */
    public fun fitsInSingleWrite(value: ByteArray, mtu: Int): Boolean =
        value.size <= maxChunkSize(mtu)

    /**
     * Validates a write request.
     *
     * @throws BlueLibValidationException.InvalidMtu when the MTU is outside the ATT range.
     * @throws BlueLibValidationException.InvalidPayload when the value cannot be written with the
     *   requested mode, which is the case for a multi-chunk payload written with a single
     *   `WRITE_REQUEST` or `WRITE_COMMAND`.
     */
    public fun validateWrite(
        value: ByteArray,
        writeMode: WriteMode,
        mtu: Int,
        maxAttributeLength: Int? = null,
    ) {
        requireValidMtu(mtu)
        val singleWriteLimit = maxChunkSize(mtu)
        when (writeMode) {
            WriteMode.WITH_RESPONSE, WriteMode.WITHOUT_RESPONSE -> {
                if (value.size > singleWriteLimit) {
                    throw BlueLibValidationException.InvalidPayload(
                        operation = "writeCharacteristic(${value.size} bytes, $writeMode)",
                        sizeBytes = value.size,
                        allowed = 0..singleWriteLimit,
                        hint = "Use WriteMode.LONG to split the value across ${(value.size + singleWriteLimit - 1) / singleWriteLimit} chunks.",
                    )
                }
            }

            WriteMode.LONG -> {
                val attributeLimit = maxAttributeLength ?: MAX_LONG_WRITE_BYTES
                if (value.size > attributeLimit) {
                    throw BlueLibValidationException.InvalidPayload(
                        operation = "writeCharacteristic(long write)",
                        sizeBytes = value.size,
                        allowed = 0..attributeLimit,
                        hint = "The ATT long write procedure is limited to $MAX_LONG_WRITE_BYTES bytes.",
                    )
                }
            }
        }
    }

    /**
     * Splits [value] into chunks that each fit in one ATT write.
     *
     * The last chunk keeps the remaining bytes, so callers can send exactly one write per chunk and
     * keep "exactly once" semantics with `WITH_RESPONSE`, or use `WITHOUT_RESPONSE` for the
     * fire-and-forget case.
     */
    public fun segment(value: ByteArray, mtu: Int, writeMode: WriteMode): List<ByteArray> {
        requireValidMtu(mtu)
        if (writeMode != WriteMode.LONG && value.size <= maxChunkSize(mtu)) {
            return listOf(value)
        }
        validateWrite(value, writeMode, mtu)
        val chunkSize = maxChunkSize(mtu)
        return value.asList().chunked(chunkSize).map { it.toByteArray() }
    }

    /**
     * Validates an MTU value.
     *
     * @throws BlueLibValidationException.InvalidMtu when [mtu] is outside `23..517`.
     */
    public fun requireValidMtu(mtu: Int) {
        if (mtu !in DEFAULT_MTU..MAX_MTU) {
            throw BlueLibValidationException.InvalidMtu(mtu, DEFAULT_MTU..MAX_MTU)
        }
    }

    /** Clamps [requested] into the range Android accepts, which is what the platform layer sends. */
    public fun clampMtu(requested: Int): Int = requested.coerceIn(DEFAULT_MTU, MAX_MTU)
}
