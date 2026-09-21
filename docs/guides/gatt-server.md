# GATT server

```kotlin
val server = blueLib.openGattServer(
    GattServerConfig(
        services = listOf(
            ServiceDefinition(
                uuid = BluetoothUuid.HEART_RATE,
                characteristics = listOf(
                    CharacteristicDefinition(
                        uuid = BluetoothUuid.fromShort(0x2A37),
                        properties = GattProperty.READ or GattProperty.NOTIFY,
                        permissions = GattPermission.READ,
                        value = byteArrayOf(0x00, 0x40),
                        descriptors = listOf(
                            DescriptorDefinition(
                                uuid = BluetoothUuid.CLIENT_CHARACTERISTIC_CONFIGURATION,
                                permissions = GattPermission.READ or GattPermission.WRITE,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    ),
).getOrThrow()
```

## The default is "answer the central"

An unanswered GATT request makes the central wait for the 30 second ATT transaction timeout, and several
stacks stop accepting further requests from that device in the meantime. Silent central + silent server is
the most common way a peripheral looks broken.

With the default `autoRespond = true` BlueLib answers every request from its local model:

| Request | Automatic answer |
| --- | --- |
| Read characteristic | The stored value, sliced from `offset` (0x07 `INVALID_OFFSET` past the end, 0x0A `ATTRIBUTE_NOT_FOUND` for an unknown attribute) |
| Write characteristic | Stored (merged at the offset), 0x03 `WRITE_NOT_PERMITTED` when the definition forbids writing |
| Read CCCD | The current subscription state as `01 00` / `00 00` |
| Write CCCD | Applies the subscription and answers success; non-subscribable characteristics stay unsubscribed |
| Read/Write other descriptor | 0x0A `ATTRIBUTE_NOT_FOUND` |
| Prepare/Execute write | Chunks accumulate and are published only on Execute Write |
| Write command (`responseNeeded = false`) | Applied, never answered (answering is a protocol error) |

## Observing and overriding

`GattServer.requests` streams every request even when it is answered automatically, so logging and
analytics do not force you to take over the answering:

```kotlin
server.requests.collect { request ->
    when (request) {
        is GattServerRequest.ReadCharacteristic -> log("read ${request.characteristic}")
        is GattServerRequest.WriteCharacteristic -> state.update(request.characteristic, request.value)
        else -> Unit
    }
}
```

For fully dynamic behaviour, disable the automation:

```kotlin
GattServerConfig(services = …, autoRespond = false)
```

Then **every** branch must answer, exactly once:

```kotlin
server.requests.collect { request ->
    val status = when (request) {
        is GattServerRequest.ReadCharacteristic -> { server.respond(request, readValueFor(request)); return@collect }
        is GattServerRequest.WriteCharacteristic -> applyWrite(request)
        else -> server.respond(request, null).let { return@collect }
    }
    server.sendResponse(request.deviceId, request.requestId, status, request.offset, null)
}
```

`sendResponse` returning `false` (a second answer for the same request, or an answer after the timeout) is
reported as `OperationRejected`, not swallowed.

## Notifying and indicating

```kotlin
server.notify(
    deviceId = central,
    service = BluetoothUuid.HEART_RATE,
    characteristic = BluetoothUuid.fromShort(0x2A37),
    value = byteArrayOf(0x01, 0x48),
    confirm = true,          // indication: BlueLib waits for onNotificationSent
)
```

* Notifications are sent with `notifyCharacteristicChanged(device, characteristic, confirm, value)` on
  Android 13+, which avoids writing the characteristic's shared `value` field — with two centrals
  connected that field is a data race.
* The value is validated against the central's negotiated MTU: notifications cannot be chunked, so an
  oversized value fails with `OperationRejected` naming the maximum, instead of being truncated.
* The stored value is updated to what was notified, so a subsequent read returns the same bytes.
* `confirm = true` (indication) suspends until `onNotificationSent` reports the result.

## Connections and MTU

```kotlin
server.connections.collect { connections -> render(connections) }   // deviceId + negotiated MTU
```

The negotiated MTU arrives through `onMtuChanged`, which is the only way a server learns it: the
**central** owns the MTU exchange. `server.requestMtu(...)` therefore fails with `FeatureUnsupported`
explaining exactly that, rather than pretending to support something the platform does not expose.

## Lifecycle

```kotlin
server.close()      // clearServices() + close(); BlueLib.close() does it too
```

`blueLib.isGattServerOpen` tells you whether a server is open; opening a second one returns the existing
instance, because a `BluetoothGattServer` per application is what the platform expects.

## Peripheral checklist

A usable peripheral needs all four:

1. `BLUETOOTH_CONNECT` (Android 12+) — `blueLib.permissionsFor(BluetoothOperation.GATT_SERVER)`;
2. an advertising set that is connectable ([Advertising](advertising.md));
3. the GATT server above, registered **before** the set starts advertising so the central can discover
   services immediately;
4. a foreground service with the `connectedDevice` type if the peripheral must keep serving while the app
   is backgrounded on Android 14+.
