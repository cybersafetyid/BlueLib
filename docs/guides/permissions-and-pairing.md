# Permissions and pairing

## The permission matrix

BlueLib asks for exactly the permissions the running Android release requires, and nothing else.

| Operation | Android 5–11 (API 21–30) | Android 12+ (API 31+) |
| --- | --- | --- |
| `BluetoothOperation.SCAN` | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` | `BLUETOOTH_SCAN` |
| `BluetoothOperation.BACKGROUND_SCAN` | the above plus `ACCESS_BACKGROUND_LOCATION` (API 29+) | `BLUETOOTH_SCAN` (+ background location unless the app is exempt) |
| `BluetoothOperation.CONNECT` | `BLUETOOTH`, `BLUETOOTH_ADMIN` | `BLUETOOTH_CONNECT` |
| `BluetoothOperation.GATT_SERVER` | `BLUETOOTH`, `BLUETOOTH_ADMIN` | `BLUETOOTH_CONNECT` |
| `BluetoothOperation.CLASSIC_DISCOVERY` | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` | `BLUETOOTH_CONNECT` |
| `BluetoothOperation.ADVERTISE` | `BLUETOOTH`, `BLUETOOTH_ADMIN` | `BLUETOOTH_ADVERTISE` |
| `BluetoothOperation.RANGING` | not available | `RANGING` (Android 16+) |

```kotlin
val report = blueLib.permissionsFor(BluetoothOperation.SCAN)
when {
    report.isSatisfied -> scan()
    report.notDeclared.isNotEmpty() -> crashInDebug("Add ${report.notDeclared} to the manifest")
    else -> launcher.launch(report.missing.toTypedArray())
}
```

`missing` is "the user said no (or never answered)" and `notDeclared` is "the app never asked for it in
the manifest". They are never mixed, because the fixes are completely different: one is a UI flow, the
other is a code change. A permission that is *not declared at all* counts as missing — `checkSelfPermission`
reports it as denied on every release, so treating it as granted would only delay the failure to the
platform call.

### `neverForLocation`

If your app asserts that scan results are never used to derive location, tell BlueLib so it stops
demanding `ACCESS_FINE_LOCATION` on Android 12+:

```xml
<meta-data
    android:name="io.github.cybersafetyid.bluelib.neverForLocation"
    android:value="true" />
```

This mirrors `android:usesPermissionFlags="neverForLocation"` on the `BLUETOOTH_SCAN` declaration. Both
places matter: the manifest declaration is what Google Play evaluates, the meta-data is what BlueLib
reads at runtime.

### Bluetooth-off handling

BlueLib never calls `BluetoothAdapter.enable()` (deprecated and privileged). Instead:

```kotlin
enableLauncher.launch(blueLib.enableBluetoothIntent())
```

and observe the state through `blueLib.adapterState`, which is driven by `ACTION_STATE_CHANGED` (with the
Android 13+ `RECEIVER_NOT_EXPORTED` flag, which the platform requires for dynamically registered
receivers).

## Pairing

```kotlin
blueLib.bond(deviceId).onFailure { error ->
    when (error) {
        is BlueLibError.BondFailed -> show("Pairing failed: ${error.reason}")
        is BlueLibError.Timeout -> show("Pairing dialog was not answered")
        else -> show(error.message)
    }
}
```

What BlueLib guarantees:

* `createBond()` returning `true` only means the request was *accepted*. BlueLib waits for
  `ACTION_BOND_STATE_CHANGED` to report `BOND_BONDED`, and reports `BOND_NONE` (user declined, or the device
  went out of range) as `BondFailed` instead of hanging.
* The bond timeout is `BlueLibConfig.bondTimeoutMillis` (30 s by default) because pairing needs a user.
* `bondState(deviceId)` streams the state, so a UI can show "pairing…" from the first `BONDING` event.
* `Transport.LE` is forwarded to `createBond(transport)` on **Android 17+** only: although the transport
  constants exist since API 30, the public `createBond(int)` overload is API 37. Below that the platform
  chooses the transport, and BlueLib says so rather than calling a method that does not exist.

### Bond loss on Android 16.1 and 17

Android 16.1 added `EXTRA_BOND_LOSS_REASON` to the bond broadcast, and Android 17 added **autonomous
re-pairing**: when a bond is lost the platform may run its own pairing flow and broadcast
`ACTION_KEY_MISSING` only when that attempt fails.

BlueLib therefore never forces a manual pairing flow on Android 17:

```kotlin
blueLib.diagnostics
    .filterIsInstance<DiagnosticEvent.ErrorReported>()
    .map { it.error }
    .filterIsInstance<BlueLibError.BondLost>()
    .collect { lost ->
        if (lost.systemRepairInProgress) {
            show("Reconnecting is being handled by Android…")
        } else {
            show("The bond to ${lost.device} was lost (${lost.reason}). Pair again to continue.")
        }
    }
```

`BondLossReason` distinguishes an authentication failure, an incoming pairing attempt on the other side,
and an LE encryption failure, which is usually enough to decide whether re-pairing can work at all.

## Unpairing

`BluetoothDevice.removeBond()` is **still not public API** in Android 17. The only supported path is the
Companion Device Manager:

```kotlin
blueLib.classicPort.unbond(deviceId)
```

* Android 16+ (API 36): for a device associated through `CompanionDeviceManager.associate(...)`, BlueLib
  calls `removeBond(associationId)`. This is the first release in which `removeBond` is public API —
  before Android 16 it was a system API, so an app could only *appear* to work on those releases.
* For a device the app is not associated with, the call fails with `OperationRejected` explaining that
  the user has to forget the device from the system Bluetooth settings — which is the honest answer.

## Companion device pairing

For devices that should be re-discoverable in the background, Android's recommended flow is
`CompanionDeviceManager.associate(AssociationRequest, …)` with a
`BluetoothDeviceFilter`/`BluetoothLeDeviceFilter`. BlueLib does not wrap this: the request objects are
about *your* UI and *your* device filters, and wrapping them would hide the per-device-type
configuration that matters. Associate first (which also gives you `unbond` above and the ability to
start observing presence), then use BlueLib for the Bluetooth work itself.
