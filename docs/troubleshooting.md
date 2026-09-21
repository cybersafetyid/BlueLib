# Troubleshooting

Every `BlueLibError` carries a `docsAnchor` pointing at a section below, so `error.docsAnchor` and the
heading here always match. Each section says what the error means, what usually causes it and what to do.

## Error codes at a glance

| Code | Error | Retryable | Section |
| --- | --- | --- | --- |
| `ADAPTER_UNAVAILABLE` | `AdapterUnavailable` | no | [adapter-unavailable](#adapter-unavailable) |
| `BLUETOOTH_DISABLED` | `BluetoothDisabled` | yes | [bluetooth-disabled](#bluetooth-disabled) |
| `PERMISSION_MISSING` | `PermissionMissing` | yes | [permission-missing](#permission-missing) |
| `FEATURE_UNSUPPORTED` | `FeatureUnsupported` | no | [feature-unsupported](#feature-unsupported) |
| `SCAN_THROTTLED` | `ScanThrottled` | yes | [scan-throttled](#scan-throttled) |
| `SCAN_ALREADY_ACTIVE` | `ScanAlreadyActive` | no | [scan-already-active](#scan-already-active) |
| `SCAN_FAILED` | `ScanFailed` | depends on `reason` | [scan-failed](#scan-failed) |
| `ADVERTISE_FAILED` | `AdvertiseFailed` | yes | [advertise-failed](#advertise-failed) |
| `SERVICE_NOT_FOUND` | `ServiceNotFound` | no | [service-not-found](#service-not-found) |
| `CHARACTERISTIC_NOT_FOUND` | `CharacteristicNotFound` | no | [characteristic-not-found](#characteristic-not-found) |
| `CHARACTERISTIC_NOT_NOTIFIABLE` | `CharacteristicNotNotifiable` | no | [characteristic-not-notifiable](#characteristic-not-notifiable) |
| `GATT_OPERATION_FAILED` | `GattOperationFailed` | from status | [gatt-operation-failed](#gatt-operation-failed) |
| `CONNECTION_FAILED` | `ConnectionFailed` | from status | [connection-failed](#connection-failed) |
| `CONNECTION_LOST` | `ConnectionLost` | yes | [connection-lost](#connection-lost) |
| `BOND_FAILED` | `BondFailed` | yes | [bond-failed](#bond-failed) |
| `BOND_LOST` | `BondLost` | yes | [bond-lost](#bond-lost) |
| `ENCRYPTION_FAILED` | `EncryptionFailed` | yes | [encryption-failed](#encryption-failed) |
| `MTU_NEGOTIATION_FAILED` | `MtuNegotiationFailed` | yes | [mtu-negotiation](#mtu-negotiation) |
| `PHY_UPDATE_FAILED` | `PhyUpdateFailed` | yes | [phy-update](#phy-update) |
| `TIMEOUT` | `Timeout` | yes | [timeout](#timeout) |
| `OPERATION_REJECTED` | `OperationRejected` | no | [operation-rejected](#operation-rejected) |
| `CLOSED` | `Closed` | no | [resource-closed](#resource-closed) |
| `CANCELLED` | `Cancelled` | no | [cancelled](#cancelled) |
| `UNEXPECTED` | `Unexpected` | no | [unexpected](#unexpected) |

## adapter-unavailable

The device does not expose a Bluetooth adapter: some tablets, emulator images and a few TVs.
`BlueLib.hasAdapter` is `false`, and `PlatformReport.hasAdapter` records it.

* Do not retry; there is nothing to retry.
* Gate the feature in your UI (`if (!blueLib.hasAdapter) hideBluetoothSection()`), and declare
  `<uses-feature android:name="android.hardware.bluetooth" android:required="false" />` so the app stays
  installable on those devices.

## bluetooth-disabled

The radio is off. BlueLib never calls the deprecated `BluetoothAdapter.enable()`.

```kotlin
enableLauncher.launch(blueLib.enableBluetoothIntent())               // ACTION_REQUEST_ENABLE
blueLib.adapterState.collect { state -> render(state) }              // ON / OFF / TURNING_ON / …
```

Retry after `adapterState` reports `ON`. A `TURNING_ON` state that never becomes `ON` is an OEM bug worth
reporting with the diagnostics trace.

## permission-missing

The operation needs permissions that are not granted. `error.permissions` lists the exact manifest
permissions, `error.operation` names the operation, and `error.permanentlyDenied` tells you whether the
system dialog will appear again or the user has to change it in Settings.

Common causes:

* **The split permissions are missing.** On Android 12+, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` and
  `BLUETOOTH_ADVERTISE` are runtime permissions; declaring `BLUETOOTH` is not enough for an app targeting
  Android 12+.
* **`ACCESS_FINE_LOCATION` is still required for scanning.** On Android 11 and older, and on Android 12+
  unless the app asserts `neverForLocation`.
* **The user denied once.** Ask again with a rationale; `permanentlyDenied` requires a link to app
  settings.
* **The permission is declared with `maxSdkVersion`** and the device is above it.

If `report.notDeclared` is not empty, the fix is in the manifest, not in a dialog.

## feature-unsupported

The Android release (or the hardware) cannot do what was asked. `error.currentApiLevel` and
`error.requiredApiLevel` are filled in, and the message says what to use instead. Examples:

* `ConnectionPriority.DCK` below API 34;
* PHY requests below API 26;
* L2CAP sockets below API 29;
* `BluetoothSocketSettings` encryption/authentication requirements below API 36;
* `GattServer.requestMtu` on every version, because the **central** owns the MTU exchange in the server
  role.

Treat it as permanent: fall back to the older behaviour explicitly rather than retrying.

## scan-throttled

Android's scan quota (five `startScan` calls per app per 30 seconds) rejected the start. `retryAfterMillis`
is exactly how long to wait — it comes from the governor, which already knows how much budget is left.

```kotlin
is BlueLibError.ScanThrottled -> delay(error.retryAfterMillis) then scanOnce()
```

Do not loop: restarting a scan is what consumes the budget. Keep one scan alive and filter in software
instead. If the platform *itself* reports throttling, the governor records it
(`ScanQuotaGovernor.recordPlatformThrottle`) so the next rejection is predicted rather than discovered.

## scan-already-active

A scan is already running in this `BlueLib` instance. Android silently replaces the callback of a second
`startScan`, losing the first scan's results, so BlueLib refuses instead.

* Cancel the collector of the first scan before starting another, or
* reuse the first scan and filter its results.

## scan-failed

The platform reported `onScanFailed`. `error.reason` is the normalised cause:

| Reason | Meaning | Action |
| --- | --- | --- |
| `ALREADY_STARTED` | a scan is already registered | stop the other scan |
| `APP_REGISTRATION_FAILED` | the app's scan registration was rejected | too many registered scanners; restart the app process |
| `INTERNAL_ERROR` | the stack failed | retry with backoff |
| `FEATURE_UNSUPPORTED` | scanning is not supported | check `PlatformReport.supports(LOW_ENERGY)` |
| `OUT_OF_HARDWARE_RESOURCES` | the controller is saturated | retry after a delay, reduce concurrent connections |
| `SCANNING_TOO_FREQUENTLY` | throttled (Android 13+ reports this distinctly) | wait, then retry once |

## advertise-failed

Advertising did not start. `error.reason` is one of "payload too large", "too many advertising sets",
"advertising already started", "internal error", "feature unsupported".

* **Payload too large.** Legacy sets are limited to 31 bytes including the 3 byte flags field; extended
  sets are limited to `leMaximumAdvertisingDataLength`. BlueLib validates before the platform call, so
  reaching this error with a payload error means the device reported a smaller maximum than expected —
  shorten the data or switch `useExtendedAdvertising`.
* **Too many sets.** Check `PlatformReport.supports(MULTIPLE_ADVERTISEMENT)` and stop unused sets with
  `stopAdvertising(handle)`.
* **Already started.** The handle from a previous start was never stopped; stop it or call `close()` on the
  `BlueLib` instance.

## service-not-found

The peripheral does not expose the requested service. `error.discovered` lists the service UUIDs that
*were* found, which is usually enough to spot a wrong UUID or a stale service cache.

* Confirm the UUID (16 bit vs full 128 bit form).
* If the peripheral changed its services, force a rediscovery: `session.discoverServices()`.
* Some peripherals only expose a service after the link is encrypted; connect with a bond in place.

## characteristic-not-found

The service exists, the characteristic does not. Same causes as above; remember that two instances of the
same characteristic inside one service are distinguished by `instanceId` in `GattServiceInfo`.

## characteristic-not-notifiable

`subscribe` was called on a characteristic that declares neither `PROPERTY_NOTIFY` nor
`PROPERTY_INDICATE`. `error.properties` carries the property bits.

* Use `GattCharacteristicInfo.isSubscribable` before subscribing instead of discovering the problem from a
  flow that never emits.
* If the characteristic *should* be notifiable, the peripheral's GATT table is wrong — many peripherals do
  not set the property bit while still sending notifications, in which case you must write the Client
  Characteristic Configuration descriptor yourself with `writeDescriptor` and read values through a
  polling path.

## gatt-operation-failed

A read, write, descriptor operation or discovery returned a GATT status. `error.status` is the typed
status and `error.operation` names the operation; `error.status.retryable` decides whether a retry makes
sense.

The frequent ones:

* `GATT_CONNECTION_CONGESTED` (143 in the old numbering, 133's quieter sibling) — retry with backoff.
* `GATT_INSUFFICIENT_AUTHENTICATION` / `GATT_INSUFFICIENT_ENCRYPTION` (5/15) — the link is not paired or
  encrypted; bond first.
* `GATT_DATABASE_OUT_OF_SYNC` (0x12) — the cached GATT database is stale; BlueLib retries this as a
  rediscovery.
* `GATT_REQUEST_NOT_SUPPORTED` (6) — the peripheral does not implement the ATT procedure (a common
  firmware shortcut for "no long writes").
* `GATT_ERROR` (133) — see [connection-failed](#connection-failed); repeated 133s usually mean a leaked
  connection.

## connection-failed

`connectGatt` failed or timed out. `error.status` carries the platform status when there was one, and
`error.attempt` says which attempt failed.

Diagnostic order that finds the cause fastest:

1. **Is another connection leaked?** `blueLib.openConnections` — a `BluetoothGatt` that was never closed
   eventually makes *every* connect fail with 133. Call `close()` on sessions.
2. **Was the device scanned before connecting?** A connect to a device that was never observed (or
   observed long ago) fails often; scan, then connect while the advertisement is fresh.
3. **Is a scan running?** Discovery and connection compete for the radio; stop scanning first.
4. **Is the device already connected to another phone / app?** Many peripherals accept one central.
5. **Is `autoConnect` on?** With `autoConnect = true` the platform reports no failure and can hold the
   attempt for a very long time. BlueLib defaults to `false`.
6. **GATT cache.** After several service changes on the same physical device the stack's cache can be
   stale: unpair, or use a different device address type, or reconnect after `onServiceChanged`.

Also worth knowing: `GATT_CONN_TIMEOUT` (8) and `GATT_CONN_LMP_TIMEOUT` (34) are RF problems — distance,
2.4 GHz congestion, or a peripheral with a weak antenna.

## connection-lost

The link dropped after having been established. `error.bondLossReason` is set only when the platform
reported one (Android 16.1+), and it changes the answer: if the bond is gone, reconnecting will not fix it.

Applications usually want a reconnect with backoff:

```kotlin
session.state.filter { it == ConnectionState.DISCONNECTED }
    .collect { attemptReconnectWithBackoff() }
```

## bond-failed

Pairing did not complete. `error.reason` distinguishes the causes: the platform rejected `createBond`, the
user declined the dialog, the device went out of range, or the attempt timed out
(see [timeout](#timeout)).

Checklist:

* The device must be discoverable (in pairing mode) for classic pairing, and advertising for LE pairing.
* On Android 12+, pairing needs `BLUETOOTH_CONNECT`; a missing permission looks like an immediate failure.
* iOS-created bonds do not work with Android for LE without a shared key scheme; the peripheral must
  support both.
* Remove a stale bond on both sides before retrying — a peripheral that still holds an old key rejects the
  new one.

## bond-lost

The bond no longer exists: the peer removed it, the keys were lost, or the platform's authentication failed
repeatedly. `error.reason` is one of `BREDR_AUTH_FAILURE`, `BREDR_INCOMING_PAIRING`,
`LE_ENCRYPT_FAILURE`, `LE_INCOMING_PAIRING` or `UNKNOWN`.

* On **Android 17**, `error.systemRepairInProgress = true` means the platform is running its own
  re-pairing flow. Do not show a pairing prompt; wait for `ACTION_KEY_MISSING`, which the platform only
  broadcasts when its own attempt failed.
* `LE_ENCRYPT_FAILURE` usually means the peripheral's link key changed (firmware reset, factory reset,
  another phone paired it).

## encryption-failed

The operation needed an encrypted (or authenticated) link and the link was not. Usually the bond is
missing or stale.

1. Bond first (`blueLib.bond(deviceId)`), or
2. for a device that needs a MITM-protected link, use `SocketSettings(authenticationRequired = true)` on
   Android 16+ for sockets, or require an encrypted characteristic permission on the server side.

## mtu-negotiation

`requestMtu` was granted less than the ATT minimum, or less than a value the caller declared as required.
`error.requested`, `error.negotiated` and `error.minimum` are filled in.

* Android clamps the requested value into `23..517`; the peripheral may grant less than requested, which is
  legal.
* A granted MTU below 23 is a broken peripheral; BlueLib reports it instead of pretending the write fits.
* The *central* negotiates MTU. In the server role, read the value from `server.connections` instead.

## phy-update

A PHY request was rejected. Use `PlatformReport.supports(LE_2M_PHY)` / `LE_CODED_PHY` / `LE_HDT_PHY` before
requesting, and be aware that the peripheral chooses the final combination — a rejected request is a
normal outcome, not an error worth retrying in a loop.

## timeout

An operation did not complete in time (`error.operation` and `error.timeoutMillis`). BlueLib always times
out platform operations, because a Bluetooth callback that never arrives is a real and common outcome.

The timeouts come from `BlueLibConfig`:

```kotlin
BlueLibConfig(
    operationTimeoutMillis = 10_000,   // per GATT operation
    connectTimeoutMillis = 15_000,     // connect + discovery
    bondTimeoutMillis = 30_000,        // pairing needs a human
)
```

Slow peripherals legitimately need larger values; an operation that times out at 10 s and succeeds at 30 s
is usually a firmware issue worth reporting rather than a number to keep raising.

## operation-rejected

The request contradicts the current state or the platform refused it. `error.reason` says what, `error.hint`
usually says what to do. Examples: a second scan, a descriptor write to a characteristic that does not
expose it, `sendResponse` for an already answered request, an unbond for a device the app is not
associated with (or an unbond on Android 15 and older, which returns `FeatureUnsupported` because
`CompanionDeviceManager.removeBond` only became public in Android 16), and an unknown attribute on the
GATT server (also answered with ATT status 0x0A).

Not retryable: read the `reason` and fix the call.

## resource-closed

The session, server or advertising handle was used after `close()`. This is a programming error surfaced
as data. The usual cause is a `BlueLib` instance owned by an activity that was destroyed while a coroutine
still held a session — own the instance in the `Application`, or scope the session to the same coroutine
scope as the UI.

## cancelled

The coroutine performing the operation was cancelled. Nothing is wrong; the platform operation was
abandoned, and BlueLib makes sure no callback is left dangling (for example, an advertising set that was
mid-start is stopped).

## unexpected

BlueLib could not classify the failure. `error.cause` is the original throwable and `error.operation` names
the operation. This is the case worth reporting: attach `blueLib.diagnostics` output, the device model, the
Android release and the peripheral's firmware version.

```kotlin
blueLib.diagnostics.onEach { event -> log(event) }.launchIn(scope)
```

The diagnostics stream contains the platform status codes and the negotiated MTU/PHY values, which is what
turns "it does not work" into a reportable bug.
