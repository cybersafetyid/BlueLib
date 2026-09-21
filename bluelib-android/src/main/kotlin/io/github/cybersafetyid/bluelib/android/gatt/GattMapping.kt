package io.github.cybersafetyid.bluelib.android.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import io.github.cybersafetyid.bluelib.domain.error.GattStatus
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.ConnectionPriority
import io.github.cybersafetyid.bluelib.domain.model.GattCharacteristicInfo
import io.github.cybersafetyid.bluelib.domain.model.GattDescriptorInfo
import io.github.cybersafetyid.bluelib.domain.model.GattProfile
import io.github.cybersafetyid.bluelib.domain.model.GattServiceInfo
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.PhyCoding
import io.github.cybersafetyid.bluelib.domain.model.ServiceDefinition
import io.github.cybersafetyid.bluelib.domain.model.Transport

/** Translation between BlueLib's GATT model and `android.bluetooth`. */
internal object GattMapping {

    fun profileOf(services: List<BluetoothGattService>): GattProfile =
        GattProfile(services.map { serviceOf(it) })

    fun serviceOf(service: BluetoothGattService): GattServiceInfo = GattServiceInfo(
        uuid = BluetoothUuid.of(service.uuid),
        instanceId = service.instanceId,
        isPrimary = service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
        characteristics = service.characteristics.map { characteristicOf(it) },
        includedServices = runCatching { service.includedServices }.getOrNull().orEmpty()
            .map { BluetoothUuid.of(it.uuid) },
    )

    fun characteristicOf(characteristic: BluetoothGattCharacteristic): GattCharacteristicInfo =
        GattCharacteristicInfo(
            uuid = BluetoothUuid.of(characteristic.uuid),
            instanceId = characteristic.instanceId,
            properties = characteristic.properties,
            permissions = characteristic.permissions,
            descriptors = characteristic.descriptors.map { descriptorOf(it) },
        )

    fun descriptorOf(descriptor: BluetoothGattDescriptor): GattDescriptorInfo = GattDescriptorInfo(
        uuid = BluetoothUuid.of(descriptor.uuid),
        // `BluetoothGattDescriptor` exposes no instance id; descriptors are addressed by UUID alone.
        instanceId = 0,
        permissions = descriptor.permissions,
    )

    /** Builds a platform service from a BlueLib definition, for the local GATT server. */
    fun platformServiceOf(definition: ServiceDefinition): BluetoothGattService {
        val service = BluetoothGattService(
            definition.uuid.uuid,
            if (definition.isPrimary) {
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            } else {
                BluetoothGattService.SERVICE_TYPE_SECONDARY
            },
        )

        definition.characteristics.forEach { characteristic ->
            val platformCharacteristic = BluetoothGattCharacteristic(
                characteristic.uuid.uuid,
                characteristic.properties,
                characteristic.permissions,
            )
            // Android 5.0–7.1 only accept initial values through the deprecated setter, and every
            // version from 21 to 37 still honours it, so the value is always seeded here.
            @Suppress("DEPRECATION")
            platformCharacteristic.value = characteristic.value.copyOf()

            characteristic.descriptors.forEach { descriptor ->
                val platformDescriptor = BluetoothGattDescriptor(descriptor.uuid.uuid, descriptor.permissions)
                @Suppress("DEPRECATION")
                platformDescriptor.value = descriptor.value.copyOf()
                platformCharacteristic.addDescriptor(platformDescriptor)
            }
            service.addCharacteristic(platformCharacteristic)
        }
        return service
    }

    /**
     * Whether this device can host a GATT server.
     *
     * Every Android release from 5.0 exposes `BluetoothManager.openGattServer`, but the manager itself
     * can be absent on devices without a Bluetooth stack (large tablets, some emulator images), and
     * asking for it is the only reliable check short of opening the server.
     */
    fun isServerSupported(context: android.content.Context): Boolean = runCatching {
        // The `getSystemService(Class)` overload needs API 23, so the string lookup is used to stay
        // compatible with Android 5.0.
        context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) != null
    }.getOrDefault(false)

    /** Maps a BlueLib transport onto `BluetoothDevice.TRANSPORT_*`. */
    fun transportOf(transport: Transport): Int = when (transport) {
        Transport.AUTO -> BluetoothDeviceCompat.TRANSPORT_AUTO
        Transport.BREDR -> BluetoothDeviceCompat.TRANSPORT_BREDR
        Transport.LE -> BluetoothDeviceCompat.TRANSPORT_LE
    }

    /** Maps a BlueLib connection priority onto `BluetoothGatt.CONNECTION_PRIORITY_*`. */
    fun priorityOf(priority: ConnectionPriority): Int = when (priority) {
        ConnectionPriority.LOW -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        ConnectionPriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        ConnectionPriority.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
        ConnectionPriority.DCK -> BluetoothDeviceCompat.CONNECTION_PRIORITY_DCK
    }

    /** Maps a `BluetoothDevice.PHY_LE_*` mask onto BlueLib PHYs. */
    fun physOf(mask: Int): List<Phy> = Phy.fromMask(mask)

    /** Maps a PHY coding option onto `BluetoothDevice.PHY_OPTION_*`. */
    fun phyOptionOf(coding: PhyCoding?): Int = when (coding) {
        null -> BluetoothDeviceCompat.PHY_OPTION_NO_PREFERRED
        PhyCoding.S2 -> BluetoothDeviceCompat.PHY_OPTION_S2
        PhyCoding.S8 -> BluetoothDeviceCompat.PHY_OPTION_S8
    }

    /** Converts a platform status code into the typed representation. */
    fun statusOf(code: Int): GattStatus = GattStatus.of(code)

    /** Constant holder that keeps API level gated values in one place. */
    private object BluetoothDeviceCompat {
        /** `BluetoothDevice.TRANSPORT_AUTO`. */
        const val TRANSPORT_AUTO = 0

        /** `BluetoothDevice.TRANSPORT_BREDR`. */
        const val TRANSPORT_BREDR = 1

        /** `BluetoothDevice.TRANSPORT_LE`. */
        const val TRANSPORT_LE = 2

        /** `BluetoothGatt.CONNECTION_PRIORITY_DCK` (Android 14 / API 34). */
        const val CONNECTION_PRIORITY_DCK = 3

        /** `BluetoothDevice.PHY_OPTION_NO_PREFERRED`. */
        const val PHY_OPTION_NO_PREFERRED = 0

        /** `BluetoothDevice.PHY_OPTION_S2`. */
        const val PHY_OPTION_S2 = 1

        /** `BluetoothDevice.PHY_OPTION_S8`. */
        const val PHY_OPTION_S8 = 2
    }
}
