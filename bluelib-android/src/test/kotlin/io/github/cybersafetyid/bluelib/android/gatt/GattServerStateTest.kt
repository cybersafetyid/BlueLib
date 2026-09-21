package io.github.cybersafetyid.bluelib.android.gatt

import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.CharacteristicDefinition
import io.github.cybersafetyid.bluelib.domain.model.DescriptorDefinition
import io.github.cybersafetyid.bluelib.domain.model.GattPermission
import io.github.cybersafetyid.bluelib.domain.model.GattProperty
import io.github.cybersafetyid.bluelib.domain.model.GattServerConfig
import io.github.cybersafetyid.bluelib.domain.model.ServiceDefinition
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The GATT server's answer logic, tested off-device.
 *
 * These are the cases that decide whether a central hangs for 30 seconds or gets a correct answer:
 * reads past the end of a value, writes to a read-only characteristic, a long write that is cancelled
 * halfway, and a subscription that arrives from a device that disconnects.
 */
class GattServerStateTest {

    private val service = BluetoothUuid.fromShort(0x180D)
    private val notifyCharacteristic = BluetoothUuid.fromShort(0x2A37)
    private val writeOnlyCharacteristic = BluetoothUuid.fromShort(0x2A39)
    private val readOnlyCharacteristic = BluetoothUuid.fromShort(0x2A38)
    private val device = BluetoothDeviceId.of("AA:BB:CC:DD:EE:FF")

    private fun state(autoRespond: Boolean = true) = GattServerState(
        GattServerConfig(services = listOf(serviceDefinition()), autoRespond = autoRespond),
    )

    private fun serviceDefinition() = ServiceDefinition(
        uuid = service,
        characteristics = listOf(
            CharacteristicDefinition(
                uuid = notifyCharacteristic,
                properties = GattProperty.READ or GattProperty.NOTIFY,
                permissions = GattPermission.READ,
                value = byteArrayOf(0x01, 0x02, 0x03),
                descriptors = listOf(
                    DescriptorDefinition(
                        uuid = BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION,
                        permissions = GattPermission.READ or GattPermission.WRITE,
                    ),
                ),
            ),
            CharacteristicDefinition(
                uuid = writeOnlyCharacteristic,
                properties = GattProperty.WRITE,
                permissions = GattPermission.WRITE,
            ),
            CharacteristicDefinition(
                uuid = readOnlyCharacteristic,
                properties = GattProperty.READ,
                permissions = GattPermission.READ,
                value = byteArrayOf(0x7F),
            ),
        ),
    )

    @Test
    fun `seed exposes the declared initial values`() {
        val target = state()
        target.seed()

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), target.valueOf(service, notifyCharacteristic))
        assertArrayEquals(byteArrayOf(0x7F), target.valueOf(service, readOnlyCharacteristic))
        // A characteristic declared without an initial value still exists: reading it succeeds and
        // returns zero bytes, which is very different from the attribute being absent.
        assertArrayEquals(ByteArray(0), target.valueOf(service, writeOnlyCharacteristic))
        assertNull(target.valueOf(service, BluetoothUuid.fromShort(0x2A00)))
    }

    @Test
    fun `a read returns the value from the requested offset`() {
        val target = state()
        target.seed()

        val full = target.read(service, notifyCharacteristic, offset = 0)
        assertEquals(GattServerState.GATT_SUCCESS, full.status)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), full.value)

        val tail = target.read(service, notifyCharacteristic, offset = 2)
        assertEquals(GattServerState.GATT_SUCCESS, tail.status)
        assertArrayEquals(byteArrayOf(0x03), tail.value)
    }

    @Test
    fun `an offset past the end of the value is invalid, and an unknown attribute is not found`() {
        val target = state()
        target.seed()

        val beyond = target.read(service, notifyCharacteristic, offset = 4)
        assertEquals(GattServerState.INVALID_OFFSET, beyond.status)
        assertNull(beyond.value, "a failing status must never carry a payload")

        val unknown = target.read(service, BluetoothUuid.fromShort(0x2A00), offset = 0)
        assertEquals(GattServerState.ATTRIBUTE_NOT_FOUND, unknown.status)
        assertNull(unknown.value)
    }

    @Test
    fun `writing a read-only characteristic is refused and leaves the value untouched`() {
        val target = state()
        target.seed()

        val status = target.write(service, readOnlyCharacteristic, offset = 0, payload = byteArrayOf(0x00))

        assertEquals(GattServerState.WRITE_NOT_PERMITTED, status)
        assertArrayEquals(byteArrayOf(0x7F), target.valueOf(service, readOnlyCharacteristic))
    }

    @Test
    fun `a single write replaces the whole value`() {
        val target = state()
        target.seed()

        val status = target.write(service, writeOnlyCharacteristic, offset = 0, payload = byteArrayOf(0x11, 0x22))

        assertEquals(GattServerState.GATT_SUCCESS, status)
        assertArrayEquals(byteArrayOf(0x11, 0x22), target.valueOf(service, writeOnlyCharacteristic))
    }

    @Test
    fun `a prepared write is invisible until it is executed`() {
        val target = state()
        target.seed()
        val chunk = byteArrayOf(0x0A, 0x0B)

        assertEquals(
            GattServerState.GATT_SUCCESS,
            target.prepareWriteChunk(device, service, writeOnlyCharacteristic, offset = 0, payload = chunk),
        )
        assertArrayEquals(
            ByteArray(0),
            target.valueOf(service, writeOnlyCharacteristic),
            "a prepared value must not be readable before the central executes it",
        )

        target.executePreparedWrites(device, execute = true)
        assertArrayEquals(chunk, target.valueOf(service, writeOnlyCharacteristic))
    }

    @Test
    fun `a cancelled long write is discarded`() {
        val target = state()
        target.seed()

        target.prepareWriteChunk(device, service, writeOnlyCharacteristic, offset = 0, payload = byteArrayOf(0x01))
        target.executePreparedWrites(device, execute = false)

        assertArrayEquals(ByteArray(0), target.valueOf(service, writeOnlyCharacteristic))
    }

    @Test
    fun `long write chunks are assembled at their offsets`() {
        val target = state()
        target.seed()

        target.prepareWriteChunk(device, service, writeOnlyCharacteristic, offset = 0, payload = byteArrayOf(0x01, 0x02))
        target.prepareWriteChunk(device, service, writeOnlyCharacteristic, offset = 2, payload = byteArrayOf(0x03))
        // A chunk that arrives out of order still lands at its own offset.
        target.prepareWriteChunk(device, service, writeOnlyCharacteristic, offset = 3, payload = byteArrayOf(0x04))
        target.executePreparedWrites(device, execute = true)

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), target.valueOf(service, writeOnlyCharacteristic))
    }

    @Test
    fun `an offset write on a normal write merges into the stored value`() {
        val target = state()
        target.seed()
        target.write(service, writeOnlyCharacteristic, offset = 0, payload = byteArrayOf(0x01, 0x02, 0x03))

        target.write(service, writeOnlyCharacteristic, offset = 1, payload = byteArrayOf(0x42))

        assertArrayEquals(byteArrayOf(0x01, 0x42, 0x03), target.valueOf(service, writeOnlyCharacteristic))
    }

    @Test
    fun `the client characteristic configuration reflects the subscription state`() {
        val target = state()
        target.seed()

        val before = target.readClientCharacteristicConfiguration(device, service, notifyCharacteristic)
        assertArrayEquals(GattServerState.IDLE_VALUE, before.value)

        val subscribed = target.writeClientCharacteristicConfiguration(
            device,
            service,
            notifyCharacteristic,
            byteArrayOf(0x01, 0x00),
        )
        assertTrue(subscribed)
        assertTrue(target.isSubscribed(device, service, notifyCharacteristic))
        assertArrayEquals(
            GattServerState.NOTIFY_VALUE,
            target.readClientCharacteristicConfiguration(device, service, notifyCharacteristic).value,
        )

        val unsubscribed = target.writeClientCharacteristicConfiguration(
            device,
            service,
            notifyCharacteristic,
            byteArrayOf(0x00, 0x00),
        )
        assertFalse(unsubscribed)
        assertFalse(target.isSubscribed(device, service, notifyCharacteristic))
    }

    @Test
    fun `a characteristic that cannot notify is never reported as subscribed`() {
        val target = state()
        target.seed()

        // `readOnlyCharacteristic` has READ only: the two CCCD bytes are accepted by the platform, but
        // BlueLib must not start pushing values the characteristic cannot carry.
        val subscribed = target.writeClientCharacteristicConfiguration(
            device,
            service,
            readOnlyCharacteristic,
            byteArrayOf(0x01, 0x00),
        )

        assertFalse(subscribed)
        assertFalse(target.isSubscribed(device, service, readOnlyCharacteristic))
    }

    @Test
    fun `disconnecting forgets subscriptions and prepared writes`() {
        val target = state()
        target.seed()
        target.writeClientCharacteristicConfiguration(device, service, notifyCharacteristic, byteArrayOf(0x01, 0x00))
        target.prepareWriteChunk(device, service, writeOnlyCharacteristic, offset = 0, payload = byteArrayOf(0x01))

        target.forget(device)

        assertFalse(target.isSubscribed(device, service, notifyCharacteristic))
        target.executePreparedWrites(device, execute = true)
        assertArrayEquals(ByteArray(0), target.valueOf(service, writeOnlyCharacteristic))
    }

    @Test
    fun `another device keeps its own subscription state`() {
        val target = state()
        target.seed()
        val second = BluetoothDeviceId.of("11:22:33:44:55:66")

        target.writeClientCharacteristicConfiguration(device, service, notifyCharacteristic, byteArrayOf(0x01, 0x00))
        target.forget(second)

        assertTrue(target.isSubscribed(device, service, notifyCharacteristic))
        assertFalse(target.isSubscribed(second, service, notifyCharacteristic))
    }

    @Test
    fun `a notified value becomes the value a later read returns`() {
        val target = state()
        target.seed()

        target.recordNotifiedValue(service, notifyCharacteristic, byteArrayOf(0x09))

        assertArrayEquals(byteArrayOf(0x09), target.valueOf(service, notifyCharacteristic))
        assertArrayEquals(byteArrayOf(0x09), target.read(service, notifyCharacteristic, offset = 0).value)
    }

    @Test
    fun `clearing drops every value`() {
        val target = state()
        target.seed()
        target.clear()

        assertNull(target.valueOf(service, notifyCharacteristic))
    }
}
