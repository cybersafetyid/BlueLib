# 0002 — Support Android 5.0 (API 21) through Android 17 (API 37)

* Status: accepted
* Date: 2026-09-21
* Deciders: BlueLib maintainers

## Context

"Compatible with Android Lollipop through Android 17" is a range of eleven years and twelve API levels,
and the Bluetooth stack changed at nearly every one of them:

* API 21 — `BluetoothLeScanner` and `BluetoothLeAdvertiser` appear (replacing `startLeScan`).
* API 26 — extended advertising, 2M and coded PHY, periodic advertising.
* API 29 — L2CAP CoC sockets, `BluetoothAdapter` scanning helpers.
* API 31 — the `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` runtime permissions split
  the old `BLUETOOTH` + `ACCESS_FINE_LOCATION` pair.
* API 33 — `readCharacteristic` keeps its boolean while the writes gain status-returning overloads,
  `ScanFilter` gains AD-type matching, `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` becomes a distinct code,
  LE Audio support queries go public.
* API 34 — discoverable advertising sets, `CONNECTION_PRIORITY_DCK`.
* API 36 — Ranging (Channel Sounding and RSSI), `BluetoothSocketSettings`, DCK.
* API 36.1 — connection subrating and `EXTRA_BOND_LOSS_REASON`, delivered as a *minor* SDK version.
* API 37 — `BluetoothDevice.connect()`/`disconnect()`, `BluetoothGattConnectionSettings`,
  `createBond(transport)`, `cancelBondProcess()`, `getBondStatus()`, `PHY_LE_HDT`; the old
  `connectGatt` overloads deprecated.

## Decision

Support `minSdk 21` and compile against `compileSdk 37`, with the following rules:

1. `ApiLevel` is the only place `Build.VERSION.SDK_INT` is compared, and its helpers carry
   `@ChecksSdkIntAtLeast` so Android Lint's `NewApi` check stays an error rather than being suppressed.
2. Minor releases are first class: `ApiLevel.isAtLeast(36, 1)` reads `Build.getMinorSdkVersion()`, so
   API 36.1 features are detected instead of being invisible behind `SDK_INT == 36`.
3. Newer API levels are used when they are *better*, not just available. Android 17's
   `BluetoothGattConnectionSettings` is preferred over the deprecated `connectGatt` overloads; Android
   13's `notifyCharacteristicChanged(…, value)` is preferred over mutating the shared characteristic
   value; Android 16's `BluetoothSocketSettings` is used when encryption or authentication must be
   requested explicitly.
4. Where only a newer platform can express the request, older versions fail with
   `BlueLibError.FeatureUnsupported` naming the API level — never by ignoring the flag.
5. `bluelib-testing` and the domain layer stay pure, because Robolectric 4.16 cannot run API 21/22: the
   logic for those levels has to be testable without the framework.

## Consequences

**Good**

* One artifact serves devices from 2014 to 2026.
* Feature detection is honest: `PlatformReport.unsupportedReason(feature)` distinguishes "your Android
  is too old", "your hardware cannot", and "your manifest is missing a permission".
* Version-specific regressions are caught by the compatibility matrix check in CI, which fails when the
  generated matrix is stale.

**Bad**

* Every platform call needs a guard, and the guards are the least interesting code in the library.
* Some features are genuinely unreachable on old devices (extended advertising on Android 7,
  `BluetoothSocketSettings` before Android 16), so the library carries branches that only exist for
  correctness on old releases.

## Alternatives considered

* **`minSdk 23` or higher.** Rejected: Lollipop devices are still in service in industrial and medical
  deployments, which is exactly the audience for the Classic and GATT server features.
* **Two artifacts (legacy and modern).** Rejected: doubles the release surface and forces applications
  to choose, while the compatibility logic would still have to exist in one of them.
