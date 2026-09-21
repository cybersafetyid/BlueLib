package io.github.cybersafetyid.bluelib.domain.model

import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException

/**
 * Characteristic property bits, mirroring `BluetoothGattCharacteristic.PROPERTY_*`.
 *
 * The helpers exist because the underlying platform is an untyped `int`: forgetting to check
 * `PROPERTY_NOTIFY` before subscribing is one of the most common BLE bugs.
 */
public object GattProperty {
    public const val BROADCAST: Int = 0x01
    public const val READ: Int = 0x02
    public const val WRITE_NO_RESPONSE: Int = 0x04
    public const val WRITE: Int = 0x08
    public const val NOTIFY: Int = 0x10
    public const val INDICATE: Int = 0x20
    public const val SIGNED_WRITE: Int = 0x40
    public const val EXTENDED_PROPERTIES: Int = 0x80

    /** `true` when the characteristic can push values through notifications. */
    public fun isNotifiable(properties: Int): Boolean = (properties and NOTIFY) != 0

    /** `true` when the characteristic can push values through indications. */
    public fun isIndicatable(properties: Int): Boolean = (properties and INDICATE) != 0

    /** `true` when the characteristic can be subscribed to at all. */
    public fun isSubscribable(properties: Int): Boolean = isNotifiable(properties) || isIndicatable(properties)

    /** `true` when the characteristic can be read. */
    public fun isReadable(properties: Int): Boolean = (properties and READ) != 0

    /** `true` when the characteristic accepts writes (with or without response). */
    public fun isWritable(properties: Int): Boolean =
        (properties and WRITE) != 0 || (properties and WRITE_NO_RESPONSE) != 0
}

/** Permission bits a local GATT server can require, mirroring `BluetoothGattCharacteristic.PERMISSION_*`. */
public object GattPermission {
    public const val READ: Int = 0x01
    public const val READ_ENCRYPTED: Int = 0x02
    public const val READ_ENCRYPTED_MITM: Int = 0x04
    public const val WRITE: Int = 0x10
    public const val WRITE_ENCRYPTED: Int = 0x20
    public const val WRITE_ENCRYPTED_MITM: Int = 0x40
    public const val WRITE_SIGNED: Int = 0x80
    public const val WRITE_SIGNED_MITM: Int = 0x100
}

/** How a write is transmitted over the ATT bearer. */
public enum class WriteMode {
    /** Write request, the peripheral answers with a response (ATT "Write Request"). */
    WITH_RESPONSE,

    /** Write command, fire and forget (ATT "Write Command"). Lowest latency, no confirmation. */
    WITHOUT_RESPONSE,

    /**
     * Long write: the payload is split across MTU sized chunks using prepare/execute write.
     * BlueLib handles the chunking (see
     * [io.github.cybersafetyid.bluelib.domain.validation.PayloadSegmenter]) and validates that the
     * value fits the characteristic, because Android silently truncates oversized values.
     */
    LONG,
    ;

    /** `true` when the mode needs a response from the peripheral. */
    public val expectsResponse: Boolean
        get() = this == WITH_RESPONSE
}

/** One characteristic discovered on a peripheral. */
public data class GattCharacteristicInfo(
    val uuid: BluetoothUuid,
    val instanceId: Int,
    val properties: Int,
    val permissions: Int = 0,
    val descriptors: List<GattDescriptorInfo> = emptyList(),
) {
    /** `true` when the characteristic publishes notifications or indications. */
    public val isSubscribable: Boolean
        get() = GattProperty.isSubscribable(properties)

    /** `true` when the characteristic can be read. */
    public val isReadable: Boolean
        get() = GattProperty.isReadable(properties)

    /** `true` when the characteristic accepts writes. */
    public val isWritable: Boolean
        get() = GattProperty.isWritable(properties)

    /** The Client Characteristic Configuration descriptor, when the peripheral declares one. */
    public val clientCharacteristicConfiguration: GattDescriptorInfo?
        get() = descriptors.firstOrNull { it.uuid == BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION }
}

/** One descriptor discovered on a peripheral. */
public data class GattDescriptorInfo(
    val uuid: BluetoothUuid,
    val instanceId: Int,
    val permissions: Int = 0,
) {
    /** `true` when the descriptor is the Client Characteristic Configuration descriptor. */
    public val isClientCharacteristicConfiguration: Boolean
        get() = uuid == BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION
}

/** One service discovered on a peripheral. */
public data class GattServiceInfo(
    val uuid: BluetoothUuid,
    val instanceId: Int,
    val isPrimary: Boolean = true,
    val characteristics: List<GattCharacteristicInfo> = emptyList(),
    val includedServices: List<BluetoothUuid> = emptyList(),
) {
    /** Looks up a characteristic by UUID, optionally restricted to an instance id. */
    public fun characteristic(uuid: BluetoothUuid, instanceId: Int? = null): GattCharacteristicInfo? =
        characteristics.firstOrNull { it.uuid == uuid && (instanceId == null || it.instanceId == instanceId) }
}

/** Snapshot of everything discovered on a peripheral. */
public data class GattProfile(
    val services: List<GattServiceInfo> = emptyList(),
) {
    /** Looks up a service by UUID. */
    public fun service(uuid: BluetoothUuid): GattServiceInfo? = services.firstOrNull { it.uuid == uuid }

    /** All service UUIDs, handy for diagnostics when a lookup fails. */
    public val serviceUuids: List<BluetoothUuid>
        get() = services.map { it.uuid }
}

/** Definition of a characteristic a local GATT server exposes. */
public data class CharacteristicDefinition(
    val uuid: BluetoothUuid,
    val properties: Int,
    val permissions: Int,
    /** Initial value. Validated against the MTU budget of connecting centrals when notified. */
    val value: ByteArray = ByteArray(0),
    val descriptors: List<DescriptorDefinition> = emptyList(),
) {
    init {
        if (properties == 0) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "properties",
                value = 0,
                allowed = 1L..0xFFL,
            )
        }
        if (permissions == 0) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "permissions",
                value = 0,
                allowed = 1L..0x1FFL,
            )
        }
    }

    /** Convenience: `true` when this characteristic can notify centrals. */
    public val isNotifiable: Boolean
        get() = GattProperty.isNotifiable(properties)
}

/** Definition of a descriptor a local GATT server exposes. */
public data class DescriptorDefinition(
    val uuid: BluetoothUuid,
    val permissions: Int,
    val value: ByteArray = ByteArray(0),
)

/** Definition of a service a local GATT server exposes. */
public data class ServiceDefinition(
    val uuid: BluetoothUuid,
    val isPrimary: Boolean = true,
    val characteristics: List<CharacteristicDefinition> = emptyList(),
) {
    init {
        if (characteristics.isEmpty()) {
            throw BlueLibValidationException.InvalidPayload(
                operation = "ServiceDefinition($uuid)",
                sizeBytes = 0,
                allowed = 1..Int.MAX_VALUE,
                hint = "A GATT service must expose at least one characteristic.",
            )
        }
    }
}

/** Configuration for a local GATT server. */
public data class GattServerConfig(
    val services: List<ServiceDefinition>,
    /** Request a higher MTU as soon as a central connects (Android 6.0+ reports the result). */
    val preferHigherMtu: Boolean = true,
    /** Automatic connection priority to request per central. */
    val connectionPriority: ConnectionPriority = ConnectionPriority.BALANCED,
    /**
     * When `true` (the default) BlueLib answers every request from the local model, so a central is
     * never left waiting: reads return the characteristic's current value and writes update it.
     *
     * Set to `false` to answer by hand through
     * [io.github.cybersafetyid.bluelib.port.GattServer.respond] — then nothing is answered
     * automatically and an unanswered request makes the central time out after 30 seconds, so every
     * branch of `requests` must respond.
     */
    val autoRespond: Boolean = true,
)

/** A central currently connected to the local GATT server. */
public data class GattServerConnection(
    val deviceId: BluetoothDeviceId,
    val mtu: Int = 23,
)

/**
 * A request a central sent to the local GATT server.
 *
 * Every variant carries the [requestId] the platform assigned, because answering is mandatory: an
 * unanswered request leaves the central waiting until the ATT transaction times out (30 s), and on
 * Android the server stops answering further requests from that device in the meantime. Applications
 * therefore answer through
 * [io.github.cybersafetyid.bluelib.port.GattServer.sendResponse], or ignore the request explicitly
 * using [GattServerRequest.Rejected].
 */
public sealed interface GattServerRequest {
    /** The central that sent the request. */
    public val deviceId: BluetoothDeviceId

    /** Platform request id, echoed back when responding. */
    public val requestId: Int

    /** Byte offset inside the attribute value (non-zero for prepared/long writes). */
    public val offset: Int

    /** Service the attribute belongs to. */
    public val service: BluetoothUuid

    /** Characteristic the attribute belongs to. */
    public val characteristic: BluetoothUuid

    /** A central read a characteristic. */
    public data class ReadCharacteristic(
        override val deviceId: BluetoothDeviceId,
        override val requestId: Int,
        override val offset: Int,
        override val service: BluetoothUuid,
        override val characteristic: BluetoothUuid,
    ) : GattServerRequest

    /** A central wrote a characteristic. */
    public data class WriteCharacteristic(
        override val deviceId: BluetoothDeviceId,
        override val requestId: Int,
        override val offset: Int,
        override val service: BluetoothUuid,
        override val characteristic: BluetoothUuid,
        /** The bytes received; empty for a prepared-write execution. */
        public val value: ByteArray,
        /** `false` for a write command, which must *not* be answered. */
        public val responseNeeded: Boolean,
        /** `true` when this is one chunk of a prepare/execute long write. */
        public val preparedWrite: Boolean,
    ) : GattServerRequest

    /** A central read a descriptor. */
    public data class ReadDescriptor(
        override val deviceId: BluetoothDeviceId,
        override val requestId: Int,
        override val offset: Int,
        override val service: BluetoothUuid,
        override val characteristic: BluetoothUuid,
        public val descriptor: BluetoothUuid,
    ) : GattServerRequest

    /** A central wrote a descriptor, typically a Client Characteristic Configuration. */
    public data class WriteDescriptor(
        override val deviceId: BluetoothDeviceId,
        override val requestId: Int,
        override val offset: Int,
        override val service: BluetoothUuid,
        override val characteristic: BluetoothUuid,
        public val descriptor: BluetoothUuid,
        public val value: ByteArray,
        public val responseNeeded: Boolean,
        public val preparedWrite: Boolean,
    ) : GattServerRequest

    /** A central executed (or cancelled) the writes it prepared. */
    public data class ExecuteWrite(
        override val deviceId: BluetoothDeviceId,
        override val requestId: Int,
        override val offset: Int = 0,
        public val execute: Boolean,
    ) : GattServerRequest {
        override val service: BluetoothUuid get() = BluetoothUuid.UNKNOWN
        override val characteristic: BluetoothUuid get() = BluetoothUuid.UNKNOWN
    }
}
