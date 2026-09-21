# Advertising

```kotlin
val handle = blueLib.startAdvertising(
    AdvertisingRequest(
        advertiseData = AdvertiseData(
            includeDeviceName = true,
            serviceUuids = listOf(BluetoothUuid.HEART_RATE),
            manufacturerData = mapOf(0x004C to byteArrayOf(0x02, 0x15)),
        ),
        parameters = AdvertisingParameters(
            mode = AdvertiseMode.LOW_LATENCY,
            connectable = true,
            useExtendedAdvertising = false,
        ),
    ),
)
…
blueLib.stopAdvertising(handle)
```

## Legacy or extended?

| | Legacy | Extended (Android 8+) |
| --- | --- | --- |
| Maximum payload | 31 bytes, including the 3 byte flags field Android prepends | `BluetoothAdapter.leMaximumAdvertisingDataLength`, usually 1650, often less on OEM stacks |
| Discoverable by older phones | yes | not on Android 7 and older |
| Set discoverable (Android 14+) | n/a | `AdvertisingParameters(discoverable = true)` |
| Scan response payload | yes | yes |

Choose legacy unless you need the room: an extended advertisement is invisible to a peripheral scanning
with legacy settings, which surprises people on mixed device fleets.

BlueLib validates the payload **before** the platform call, because `ADVERTISE_FAILED_DATA_TOO_LARGE` is
not reported by every OEM stack — a too-large legacy payload is frequently truncated instead:

```kotlin
// Throws BlueLibValidationException.InvalidPayload with the byte counts and the fix.
blueLib.startAdvertising(request)
```

## Parameters that matter

* `connectable = true` is required if a central should be able to connect; a non-connectable set is
  invisible to `startScan` results that try to connect.
* `scannable = true` on non-connectable sets makes them appear in *active* scans; without it, the device
  is only visible to passive scanners.
* `timeoutMillis` stops advertising on its own (handled by both the legacy `setTimeout` and the extended
  `duration` argument). Set it if nobody else will stop the set.
* `txPowerLevel` is a *level* (`ULTRA_LOW` … `HIGH`), not dBm: the platform picks the closest supported
  value, and `AdvertisingHandle.txPowerDbm` reports what it actually used.
* `interval` on extended sets comes from the mode preset (`LOW_POWER`, `BALANCED`, `LOW_LATENCY`); the
  platform clamps whatever you pass to the controller's supported range.

## Set lifetime and cancellation

`startAdvertising` suspends until the set starts and returns an `AdvertisingHandle`. Calling it from a
coroutine that gets cancelled mid-start stops the advertising set, so a cancelled flow cannot leave the
radio advertising.

```kotlin
// One set, stopped deterministically.
val handle = blueLib.startAdvertising(request)
try {
    serve(handle)
} finally {
    blueLib.stopAdvertising(handle)
}

// Or stop everything this instance started.
blueLib.startAdvertising(a)
blueLib.startAdvertising(b)   // a different set, when extended advertising is supported
blueLib.stopAll()             // internal call used by BlueLib.close()
```

Multiple concurrent sets require the `MULTIPLE_ADVERTISEMENT` capability; check it before opening the
second one:

```kotlin
if (!blueLib.capabilities.supports(BluetoothFeature.MULTIPLE_ADVERTISEMENT)) { … }
```

## Peripheral role checklist

Advertising alone does not make the device useful: a peripheral also needs

1. a **GATT server** with the services the central will talk to ([GATT server guide](gatt-server.md)),
2. a **connectable, scannable** advertising set,
3. optionally a `BLE_ADVERTISE`/`connectedDevice` **foreground service** if it must survive while the app
   is not in the foreground, and
4. **`BLUETOOTH_ADVERTISE`** on Android 12+ (`blueLib.permissionsFor(BluetoothOperation.ADVERTISE)`).

## LE Audio and Auracast

Android 13 exposes hardware capability queries (`isLeAudioSupported`,
`isLeAudioBroadcastSourceSupported`, `isLeAudioBroadcastAssistantSupported`) which BlueLib reports through
the `LE_AUDIO*` features in `PlatformReport`. Actually controlling an Auracast broadcast
(`BluetoothLeBroadcast`) or acting as a broadcast assistant
(`BluetoothLeBroadcastAssistant`) requires privileged permissions that only system apps hold, so BlueLib
does not pretend to wrap them — see the [Roadmap](../roadmap.md) for the current status of those modules.
