# Bluetooth Classic

Bluetooth Classic (BR/EDR) is still the right answer for high-throughput links: RFCOMM streams, L2CAP
channels, SPP devices, older audio accessories and the long tail of industrial equipment that will never
speak GATT.

```kotlin
blueLib.discoverClassic(includeRssi = true)
    .filterIsInstance<ClassicDiscoveryEvent.DeviceFound>()
    .collect { render(it.device, it.rssi) }

blueLib.bond(deviceId).getOrThrow()

val connection = blueLib.connectRfcomm(deviceId, SPP_UUID).getOrThrow()
connection.incoming.collect { bytes -> parse(bytes) }
connection.write(payload)
```

## Discovery

* Discovery is exclusive: one at a time, and it monopolises the radio. A collecting coroutine owns it —
  cancelling the collection calls `cancelDiscovery()`.
* `ClassicDiscoveryEvent` reports `Started`, `DeviceFound` (with `rssi` on Android 11+) and `Finished`.
  Below API 30 the RSSI extra is not public API, so BlueLib reports `null` rather than reading a hidden
  field.
* Starting discovery needs `BluetoothOperation.CLASSIC_DISCOVERY`, which on Android 12+ is
  `BLUETOOTH_CONNECT` and below that also `ACCESS_FINE_LOCATION`.
* Discovery and an active BR/EDR connection interfere; a link that is throughput-sensitive should be
  opened after discovery stops.

## Bonding

```kotlin
blueLib.bond(deviceId, transport = Transport.BREDR)
```

`bond()` waits for the platform's `BOND_BONDED` state, so a returned success means the bond exists — not
merely that the request was accepted. See
[Permissions and pairing](permissions-and-pairing.md#pairing) for the Android 16.1/17 bond-loss flow,
`BondLossReason` and the `systemRepairInProgress` flag.

Bonded devices are available as a flow:

```kotlin
blueLib.bondedDevices().collect { devices -> render(devices) }   // ClassicDevice(name, bondState, uuids, deviceClass)
```

Reading the list requires `BLUETOOTH_CONNECT` on Android 12+; a failure is reported as
`PermissionMissing` in diagnostics rather than an empty list, because an empty list is indistinguishable
from "no devices paired".

## RFCOMM sockets

```kotlin
val connection = blueLib.connectRfcomm(
    deviceId = deviceId,
    serviceUuid = BluetoothUuid.fromShort(0x1101),          // Serial Port Profile
    settings = SocketSettings(serviceName = "BlueLib SPP", secure = true),
).getOrThrow()
```

* `secure = true` uses `createRfcommSocketToServiceRecord`, which authenticates and encrypts using the
  link key — but "secure" has historically meant different things on different stacks.
* On **Android 16+** (`API 36`) you can state the requirement explicitly, and BlueLib uses
  `BluetoothSocketSettings` when you do:

  ```kotlin
  SocketSettings(
      encryptionRequired = true,
      authenticationRequired = true,
  )
  ```

  Below API 36 that combination fails with `FeatureUnsupported` naming the API level, because silently
  downgrading an explicit security requirement is exactly the kind of thing a library must not do.
* `connection.incoming` is a flow of byte chunks; it stops when the peer closes the link or `read` fails,
  and the failure is reported through diagnostics.
* `connection.write()` runs on the IO dispatcher and flushes; a broken pipe comes back as `Unexpected`
  with the original `IOException` in `cause`.
* `connection.close()` closes the streams first, then the socket, so a blocking read returns instead of
  hanging the reader thread.

## L2CAP channels

```kotlin
val connection = blueLib.connectL2cap(deviceId, psm = 0x0081).getOrThrow()
```

* Needs Android 10 (API 29) for connection-oriented channels; below that you get `FeatureUnsupported`.
* PSM values outside `1..65535` are rejected up front (Android reserves the low range for LE CoC).
* Same `SocketSettings` rules as RFCOMM for encryption/authentication on Android 16+.

## Which to choose

| Requirement | Use |
| --- | --- |
| Low power, small payloads, background-friendly | BLE + GATT |
| Streams with high throughput (audio, file transfer, serial) | Classic RFCOMM |
| A specific PSM level protocol | Classic L2CAP |
| Both, on one device (dual-mode) | both, but never connect BR/EDR and LE to the same device simultaneously unless the device documents dual-mode support |
