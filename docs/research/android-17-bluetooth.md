# Android 5 to 17: what changed for Bluetooth

This page is the research behind BlueLib's compatibility layer. Every signature and `since` value below
was read from the Android SDK database that ships with the platform tools —
`$ANDROID_HOME/platforms/android-37.0/data/api-versions.xml`, the same source Android Lint uses — and
cross-checked against the published behaviour-change notes. Run `./gradlew generateCompatibilityMatrix`
to regenerate [the matrix](../compatibility-matrix.md) from the same file.

## Android 5.0 — API 21 (the floor)

* `BluetoothLeScanner.startScan(…)` and `BluetoothLeAdvertiser` replace `startLeScan`, which is
  deprecated from here on.
* `BluetoothGatt.requestMtu(int)` and `requestConnectionPriority(int)` exist from day one, which is why
  BlueLib can negotiate MTU on every supported device.
* `BluetoothGattServerCallback.onNotificationSent` exists, so indications can be acknowledged.
* `BluetoothGatt.GATT_CONNECTION_CONGESTED` is defined — status 133's quieter sibling.

## Android 6.0 — API 23

* Runtime permissions arrive, which is why the gateway has two branches: install-time permissions below
  23, runtime grants above it.
* `BluetoothGattServer.onMtuChanged` / `BluetoothDevice` MTU reporting becomes usable.

## Android 8.0 — API 26

* **Extended advertising and extended scanning**: payloads beyond 31 bytes,
  `AdvertisingSetParameters`, `BluetoothLeAdvertiser.startAdvertisingSet`, and
  `BluetoothAdapter.getLeMaximumAdvertisingDataLength()` for the real budget.
* **PHY**: `BluetoothGatt.setPreferredPhy`, `BluetoothGattCallback.onPhyUpdate`, `isLe2MPhySupported`,
  `isLeCodedPhySupported`.
* **Periodic advertising** (`BluetoothLeScanner.startSync`).
* **`PendingIntent` based scans** (`startScan(filters, settings, PendingIntent)`), which is what allows
  background scanning without holding a foreground service.
* **Offloaded batching and filtering** become common hardware features, so BlueLib exposes them in the
  capability report.

## Android 10 — API 29

* **L2CAP CoC**: `BluetoothDevice.createL2capChannel(int)` and `createInsecureL2capChannel(int)`.
* `BluetoothGattService.getInstanceId()` / `BluetoothGattCharacteristic.getInstanceId()`, which is why
  BlueLib's profile model carries instance ids: two instances of the same characteristic in one service
  are otherwise indistinguishable.
* Background scan and location access tighten further, reinforcing the "ask before you scan" rule.

## Android 11 — API 30

* `BluetoothDevice.EXTRA_RSSI` becomes public API in the `ACTION_FOUND` broadcast, which is what
  `discoverClassic(includeRssi = true)` relies on. Below API 30 the field was read through a hidden
  extra, and BlueLib returns `null` instead of guessing.
* Android's scan quota (5 `startScan` calls per 30 seconds, per app) starts being enforced broadly —
  hence `ScanQuotaGovernor`.

!!! warning "`createBond(int transport)` is not an API 30 method"
    `BluetoothDevice.createBond(int transport)` looks like it arrived with the transport constants in
    API 30, but the SDK database marks the public method as `since 37.0`. On API 30–36 it existed only
    as a system API, so BlueLib calls it only on Android 17 and uses the transport-less `createBond()`
    below that. Android Lint's `NewApi` check caught this, which is why the check runs as an error in
    CI.
## Android 12 — API 31

* **The permission split**: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` replace
  `BLUETOOTH` + `BLUETOOTH_ADMIN` for apps targeting Android 12, and `ACCESS_FINE_LOCATION` stops being
  the way to scan if the app asserts `neverForLocation`.
* `BluetoothGattCallback.onServiceChanged(BluetoothGatt)` becomes usable, which lets BlueLib drop a
  stale service cache and rediscover on its own.
* Reading `BluetoothAdapter.isEnabled` and `BluetoothDevice.getName()` now require `BLUETOOTH_CONNECT`
  and throw `SecurityException` otherwise — the reason every platform call in BlueLib is wrapped.

## Android 13 — API 33

* **GATT values in callbacks**: `onCharacteristicRead(…, byte[] value, int status)`,
  `onCharacteristicChanged(…, byte[] value)`, `writeCharacteristic(characteristic, value, writeType)`
  and `writeDescriptor(descriptor, value)`. Values are only valid inside their callback before this, so
  BlueLib copies them.
* `readCharacteristic` keeps its `boolean` return; only the writes gain status-returning overloads. A
  library that assumes otherwise will not compile — this was verified with `javap` against
  `android.jar` for API 37.
* **LE Audio** support queries: `isLeAudioSupported()`, `isLeAudioBroadcastSourceSupported()`,
  `isLeAudioBroadcastAssistantSupported()`, all returning `BluetoothStatusCodes`.
* **Scan failures are typed**: `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (0x05) distinguishes platform
  throttling from real errors, and `ScanFilter` gains AD-type based matching.
* Runtime receivers must declare an export flag on Android 13+ (`Context.RECEIVER_NOT_EXPORTED` for
  system broadcasts), or registration throws.

## Android 14 — API 34

* `AdvertisingSetParameters.Builder.setDiscoverable(boolean)`.
* `ConnectionPriority.CONNECTION_PRIORITY_DCK` (distributed connection kit).
* Foreground service type `connectedDevice` becomes mandatory for long-running Bluetooth work, which is
  an application concern but shapes the recommendations in the [Scanning guide](../guides/scanning.md).

## Android 15 — API 35

* No new Bluetooth API in the diff; the release concentrates on privacy and background execution, both
  of which affect *when* an app may scan rather than *how*.

## Android 16 — API 36

* **`BluetoothSocketSettings`**: `BluetoothDevice.createUsingSocketSettings(settings)` finally lets an app
  request encryption and authentication explicitly for RFCOMM and L2CAP, instead of relying on the
  "secure" variant being secure everywhere.
* **`BluetoothDevice.EXTRA_BOND_LOSS_REASON`** and the `BOND_LOSS_REASON_*` constants, reported through
  `ACTION_BOND_STATE_CHANGED` — BlueLib maps them into `BlueLibError.BondLost`. The constants are only
  read on API 36.1+ (`ApiLevel.isAtLeast(36, 1)`), because 16.0 does not send the extra.
* **`CompanionDeviceManager.removeBond(int associationId)`**: the first public unbond API on Android,
  and the reason `unbond()` needs Android 16 rather than the Android 13 association flow.
* **Ranging**: the `android.ranging` module with `RangingManager` and
  `android.ranging.ble.cs.BleCsRangingParams` (Bluetooth Channel Sounding, plus RSSI ranging), guarded by
  the `android.permission.RANGING` runtime permission and the
  `android.hardware.bluetooth.le.channel_sounding` feature flag.
* `BluetoothDevice.getIdentityAddressWithType()`.

## Android 16.1 — API 36.1 (a *minor* SDK version)

* **Connection subrating**: `BluetoothGatt.requestSubrateMode(int)`, `BluetoothGattCallback.onSubrateChange`.
* `ACTION_KEY_MISSING`, `BluetoothDevice.getKeyMissingCount()`.
* This release is the reason `ApiLevel.isAtLeast(36, 1)` exists: `Build.VERSION.SDK_INT` stays `36`, so
  comparison against major versions alone reports "not supported" on a device that supports it.

## Android 17 — API 37 (the ceiling)

Verified with `javap` against the API 37 `android.jar`:

* `BluetoothDevice.connect()` and `disconnect()`, replacing `BluetoothGatt.connect()`/`disconnect()`.
* **`BluetoothGattConnectionSettings`** with
  `connectGatt(BluetoothGattConnectionSettings, Executor, BluetoothGattCallback)`. Every older
  `connectGatt` overload is deprecated: the settings object carries transport, auto-connect, automatic MTU
  and opportunistic connection flags in one place. BlueLib prefers it and keeps the legacy overloads for
  API 21–36.
* `BluetoothDevice.cancelBondProcess()`, `BluetoothDevice.getBondStatus(int)` returning a `BondStatus`
  object (pairing algorithm and variant, `BondStatus.BONDING_VARIANT_*`).
* `BluetoothDevice.fetchUuids(int transport)`.
* **PHY `PHY_LE_HDT`** (high data throughput) and `BluetoothAdapter.isLeHighDataThroughputPhySupported()`.
* `BluetoothAdapter.ACTION_ADAPTER_STATE_CHANGED` behaviour changes and tightened permission checks.
* **Autonomous re-pairing**: when a bond is lost, the platform may run its own re-pairing flow and
  broadcast `ACTION_KEY_MISSING` only when *that* fails. BlueLib therefore never forces a manual pairing
  flow on Android 17; it reports `BondLost(systemRepairInProgress = …)` and lets the UI decide.
* `BluetoothGattConnectionSettings` deprecations mean new code should not use `autoConnect = true` with
  the old overloads; BlueLib's `GattConnectRequest` keeps the flag explicit.

## Things that never became public

| API | Status |
| --- | --- |
| `BluetoothDevice.removeBond()` | Still hidden in API 37; use `CompanionDeviceManager.removeBond` |
| `BluetoothGatt.refresh()` | Hidden; the supported workaround is rediscovery after `GATT_DATABASE_OUT_OF_SYNC` |
| `BluetoothAdapter.enable()` / `disable()` | Deprecated and privileged; use `ACTION_REQUEST_ENABLE` |
| MTU request in the GATT server role | Does not exist; the central owns the exchange |
| `AdvertisingSet.getAdvertisingSetId()` | Not in the API 37 SDK surface — verified with `javap`, which is why BlueLib allocates its own advertising set ids |
