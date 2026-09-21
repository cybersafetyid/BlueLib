package io.github.cybersafetyid.bluelib.android.gatt

import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.CharacteristicDefinition
import io.github.cybersafetyid.bluelib.domain.model.GattProperty
import io.github.cybersafetyid.bluelib.domain.model.GattServerConfig
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The GATT server's attribute state, with no Android types in it.
 *
 * Splitting it out is what makes the server testable on the JVM: the interesting behaviour — who may
 * read what, when a long write is published, whether a central is subscribed, what happens on a
 * cancelled prepare/execute — is plain data manipulation. The Android class only translates callbacks
 * into calls on this object and answers the platform.
 *
 * ATT status codes are returned as `int` values rather than an enum because they travel straight back
 * into `BluetoothGattServer.sendResponse`, and mapping them twice would only add a place to get it
 * wrong.
 */
internal class GattServerState(private val config: GattServerConfig) {

    /** Current value per characteristic, addressed by `service|characteristic`. */
    private val values = ConcurrentHashMap<String, ByteArray>()

    /** Attributes subscribed per central, keyed by `device|service|characteristic`. */
    private val subscribers = ConcurrentHashMap<String, MutableSet<String>>()

    /** Accumulators for ATT prepare/execute long writes, keyed by `device|service|characteristic`. */
    private val preparedWrites = ConcurrentHashMap<String, ByteArray>()

    /** Loads the initial values declared by the configuration. */
    fun seed() {
        config.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                values[key(service.uuid, characteristic.uuid)] = characteristic.value.copyOf()
            }
        }
    }

    /** The current value of a characteristic, or `null` when the server does not expose it. */
    fun valueOf(service: BluetoothUuid, characteristic: BluetoothUuid): ByteArray? =
        values[key(service, characteristic)]?.copyOf()

    /** The definition of a characteristic, or `null` when it is not exposed. */
    fun definitionOf(service: BluetoothUuid, characteristic: BluetoothUuid): CharacteristicDefinition? =
        config.services.firstOrNull { it.uuid == service }
            ?.characteristics
            ?.firstOrNull { it.uuid == characteristic }

    /**
     * Reads a characteristic.
     *
     * @return the ATT status and the bytes to answer with, which is `null` whenever the status is not
     *   `0x00` — answering a failure with a payload confuses centrals that only check the status.
     */
    fun read(service: BluetoothUuid, characteristic: BluetoothUuid, offset: Int): ReadResult {
        val stored = values[key(service, characteristic)] ?: return ReadResult(ATTRIBUTE_NOT_FOUND, null)
        if (offset > stored.size) return ReadResult(INVALID_OFFSET, null)
        return ReadResult(GATT_SUCCESS, stored.copyOfRange(offset, stored.size))
    }

    /** Writes a characteristic in one shot. */
    fun write(service: BluetoothUuid, characteristic: BluetoothUuid, offset: Int, payload: ByteArray): Int {
        val definition = definitionOf(service, characteristic) ?: return ATTRIBUTE_NOT_FOUND
        if (!GattProperty.isWritable(definition.properties)) return WRITE_NOT_PERMITTED

        val storeKey = key(service, characteristic)
        values[storeKey] = if (offset == 0) {
            payload.copyOf()
        } else {
            merge(values[storeKey] ?: ByteArray(0), offset, payload)
        }
        return GATT_SUCCESS
    }

    /**
     * Accumulates one chunk of a long write.
     *
     * A prepare/execute sequence is only visible to readers once [executePreparedWrites] publishes it,
     * which is exactly what the ATT protocol promises — a central that prepares and then cancels must
     * leave the attribute untouched.
     */
    fun prepareWriteChunk(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        offset: Int,
        payload: ByteArray,
    ): Int {
        val definition = definitionOf(service, characteristic) ?: return ATTRIBUTE_NOT_FOUND
        if (!GattProperty.isWritable(definition.properties)) return WRITE_NOT_PERMITTED

        val storeKey = prepareKey(deviceId, service, characteristic)
        preparedWrites[storeKey] = merge(preparedWrites[storeKey] ?: ByteArray(0), offset, payload)
        return GATT_SUCCESS
    }

    /** Publishes ([execute] = `true`) or discards the prepared writes of [deviceId]. */
    fun executePreparedWrites(deviceId: BluetoothDeviceId, execute: Boolean): Int {
        val prefix = "${deviceId.address.value}|"
        preparedWrites.keys.filter { it.startsWith(prefix) }.forEach { prepareKey ->
            val attributeKey = prepareKey.removePrefix(prefix)
            val accumulated = preparedWrites.remove(prepareKey)
            if (execute && accumulated != null) {
                values[attributeKey] = accumulated
            }
        }
        return GATT_SUCCESS
    }

    /** Reads the Client Characteristic Configuration descriptor of a characteristic. */
    fun readClientCharacteristicConfiguration(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
    ): ReadResult = ReadResult(
        status = GATT_SUCCESS,
        value = if (isSubscribed(deviceId, service, characteristic)) NOTIFY_VALUE else IDLE_VALUE,
    )

    /**
     * Applies a Client Characteristic Configuration write.
     *
     * Returns `true` when the central requested notifications or indications, which is what a caller
     * needs to decide whether to start pushing values.
     */
    fun writeClientCharacteristicConfiguration(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
        payload: ByteArray,
    ): Boolean {
        val definition = definitionOf(service, characteristic)
        val subscribe = payload.any { it != 0.toByte() } &&
            (definition == null || GattProperty.isSubscribable(definition.properties))

        val subscriptionKey = subscriptionKey(deviceId, service, characteristic)
        if (subscribe) {
            // `ConcurrentHashMap.newKeySet()` needs API 24 (or core library desugaring), and BlueLib
            // supports API 21 without desugaring, so the classic factory is used instead.
            subscribers.getOrPut(subscriptionKey) { newKeySet() }.add(characteristic.toString())
        } else {
            subscribers[subscriptionKey]?.remove(characteristic.toString())
        }
        return subscribe
    }

    /** `true` when [deviceId] subscribed to [characteristic]. */
    fun isSubscribed(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
    ): Boolean = subscribers[subscriptionKey(deviceId, service, characteristic)]?.isNotEmpty() == true

    /** Forgets everything known about [deviceId]; called when a central disconnects. */
    fun forget(deviceId: BluetoothDeviceId) {
        val prefix = "${deviceId.address.value}|"
        subscribers.keys.removeAll { it.startsWith(prefix) }
        preparedWrites.keys.removeAll { it.startsWith(prefix) }
    }

    /** Forgets every device; called when the server closes. */
    fun clear() {
        values.clear()
        subscribers.clear()
        preparedWrites.clear()
    }

    /** Keeps the stored value in sync with what was just notified. */
    fun recordNotifiedValue(service: BluetoothUuid, characteristic: BluetoothUuid, value: ByteArray) {
        values[key(service, characteristic)] = value.copyOf()
    }

    /** Result of a read: an ATT status plus the payload to answer with, or `null` for a failure. */
    data class ReadResult(val status: Int, val value: ByteArray?)

    private fun <T> newKeySet(): MutableSet<T> =
        Collections.newSetFromMap(ConcurrentHashMap<T, Boolean>())

    private fun merge(base: ByteArray, offset: Int, payload: ByteArray): ByteArray {
        val required = offset + payload.size
        val buffer = if (base.size < required) base.copyOf(required) else base.copyOf()
        payload.copyInto(buffer, destinationOffset = offset)
        return buffer
    }

    private fun key(service: BluetoothUuid, characteristic: BluetoothUuid): String = "$service|$characteristic"

    private fun prepareKey(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
    ): String = "${deviceId.address.value}|${key(service, characteristic)}"

    private fun subscriptionKey(
        deviceId: BluetoothDeviceId,
        service: BluetoothUuid,
        characteristic: BluetoothUuid,
    ): String = "${deviceId.address.value}|${key(service, characteristic)}"

    internal companion object {
        /** `BluetoothGatt.GATT_SUCCESS`. */
        const val GATT_SUCCESS = 0x00

        /** `BluetoothGatt.GATT_WRITE_NOT_PERMITTED`. */
        const val WRITE_NOT_PERMITTED = 0x03

        /** `BluetoothGatt.GATT_INVALID_OFFSET`. */
        const val INVALID_OFFSET = 0x07

        /** `BluetoothGatt.GATT_ATTRIBUTE_NOT_FOUND`. */
        const val ATTRIBUTE_NOT_FOUND = 0x0A

        /** Client Characteristic Configuration value that enables notifications. */
        val NOTIFY_VALUE = byteArrayOf(0x01, 0x00)

        /** Client Characteristic Configuration value that disables notifications. */
        val IDLE_VALUE = byteArrayOf(0x00, 0x00)
    }
}
