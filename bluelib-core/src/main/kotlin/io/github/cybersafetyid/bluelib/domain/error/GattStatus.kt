package io.github.cybersafetyid.bluelib.domain.error

/**
 * A GATT status code reported by Android.
 *
 * Android mixes three code spaces on the same `int` channel: ATT protocol errors (`0x00`–`0x1F`),
 * GATT connection errors (`0x100`+) and stack internal errors such as `133` (`0x85`). BlueLib keeps
 * that reality explicit instead of pretending there is a single enum:
 *
 * * [retryable] says whether the failure is worth retrying with backoff.
 * * [source] tells whether the code came from the ATT layer, the connection layer or the stack.
 */
public data class GattStatus(
    val code: Int,
    val name: String,
    val source: GattStatusSource,
    val retryable: Boolean,
    val description: String,
) {
    /** `true` for `GATT_SUCCESS`. */
    public val isSuccess: Boolean
        get() = code == SUCCESS

    override fun toString(): String = "$name($code)"

    public companion object {
        /** `BluetoothGatt.GATT_SUCCESS`. */
        public const val SUCCESS: Int = 0

        /** Generic Android Bluetooth stack error, by far the most common failure. */
        public const val GATT_ERROR: Int = 0x85

        private val known: Map<Int, GattStatus> = listOf(
            GattStatus(0x00, "GATT_SUCCESS", GattStatusSource.ATT, false, "Operation completed."),
            GattStatus(0x01, "GATT_INVALID_HANDLE", GattStatusSource.ATT, false, "Server reported an unknown attribute handle."),
            GattStatus(0x02, "GATT_READ_NOT_PERMITTED", GattStatusSource.ATT, false, "The attribute is not readable."),
            GattStatus(0x03, "GATT_WRITE_NOT_PERMITTED", GattStatusSource.ATT, false, "The attribute is not writable."),
            GattStatus(0x05, "GATT_INSUFFICIENT_AUTHENTICATION", GattStatusSource.ATT, true, "Bonding or a secure connection is required."),
            GattStatus(0x06, "GATT_REQUEST_NOT_SUPPORTED", GattStatusSource.ATT, false, "The peripheral does not support this request."),
            GattStatus(0x07, "GATT_INVALID_OFFSET", GattStatusSource.ATT, false, "A long read used an invalid offset."),
            GattStatus(0x08, "GATT_CONN_TIMEOUT", GattStatusSource.CONNECTION, true, "The connection timed out at the link layer."),
            GattStatus(0x09, "GATT_PREPARE_QUEUE_FULL", GattStatusSource.ATT, true, "The peripheral's prepare-write queue is full."),
            GattStatus(0x0A, "GATT_ATTRIBUTE_NOT_FOUND", GattStatusSource.ATT, false, "The handle does not exist; rediscover services."),
            GattStatus(0x0B, "GATT_ATTRIBUTE_NOT_LONG", GattStatusSource.ATT, false, "The attribute cannot be read with a long procedure."),
            GattStatus(0x0C, "GATT_INSUFFICIENT_ENCRYPTION_KEY_SIZE", GattStatusSource.ATT, false, "The encryption key is too short for this attribute."),
            GattStatus(0x0D, "GATT_INVALID_ATTRIBUTE_LENGTH", GattStatusSource.ATT, false, "Payload length does not match the attribute definition."),
            GattStatus(0x0E, "GATT_UNLIKELY_ERROR", GattStatusSource.ATT, true, "The peripheral reported an unlikely error; retry."),
            GattStatus(0x0F, "GATT_INSUFFICIENT_ENCRYPTION", GattStatusSource.ATT, true, "The link is not encrypted yet; retry after bonding."),
            GattStatus(0x10, "GATT_UNSUPPORTED_GROUP_TYPE", GattStatusSource.ATT, false, "Unsupported grouping type."),
            GattStatus(0x11, "GATT_INSUFFICIENT_RESOURCES", GattStatusSource.ATT, true, "The peripheral is out of resources; back off and retry."),
            GattStatus(0x12, "GATT_DATABASE_OUT_OF_SYNC", GattStatusSource.ATT, true, "The GATT database changed; rediscover services."),
            GattStatus(0x13, "GATT_CONN_TERMINATE_PEER_USER", GattStatusSource.CONNECTION, true, "The peer closed the connection."),
            GattStatus(0x16, "GATT_CONN_TERMINATE_LOCAL_HOST", GattStatusSource.CONNECTION, true, "The local device closed the connection."),
            GattStatus(0x22, "GATT_CONN_LMP_TIMEOUT", GattStatusSource.CONNECTION, true, "Link manager timeout; the peripheral likely went away."),
            GattStatus(0x3E, "GATT_CONN_FAIL_ESTABLISH", GattStatusSource.CONNECTION, true, "The connection could not be established."),
            GattStatus(GATT_ERROR, "GATT_ERROR", GattStatusSource.STACK, true, "Android's generic internal error (133). Usually stale state or a busy controller."),
            GattStatus(0x100, "GATT_CONN_CANCEL", GattStatusSource.CONNECTION, true, "The connection request was cancelled."),
        ).associateBy { it.code }

        /** Statuses BlueLib knows by name. */
        public val knownStatuses: List<GattStatus> = known.values.toList()

        /**
         * Resolves [code] to a [GattStatus]. Unknown codes from the transport range (`>= 0x80`) are
         * reported as retryable, because they come from the controller rather than the peer.
         */
        public fun of(code: Int): GattStatus = known[code] ?: GattStatus(
            code = code,
            name = "UNCLASSIFIED_0x${code.toString(16).padStart(2, '0')}",
            source = if (code >= 0x80) GattStatusSource.STACK else GattStatusSource.ATT,
            retryable = code >= 0x80,
            description = "Undocumented status code; see docs/research/gatt-pitfalls.md.",
        )
    }
}

/** Where a `GATT` status code came from. */
public enum class GattStatusSource {
    /** ATT protocol error reported by the peripheral. */
    ATT,

    /** Link-layer / GATT connection error. */
    CONNECTION,

    /** Internal Android Bluetooth stack error such as `133`. */
    STACK,
}
