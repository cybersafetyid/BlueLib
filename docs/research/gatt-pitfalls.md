# GATT pitfalls on real devices

Every rule below is enforced somewhere in BlueLib's platform layer, with a pointer to the code that
enforces it. They are collected here because they are the difference between a demo that works on one
phone and a library that works on a fleet.

## 1. Connect failures are status codes, not exceptions

`BluetoothGattCallback.onConnectionStateChange(gatt, status, newState)` delivers `status != 0` for a
*failed* connection attempt and `status == 0` with `STATE_DISCONNECTED` for a link that dropped. Both
arrive on the same callback, so "connected" is only true when `status == 0 && state == STATE_CONNECTED`.

**Enforced in** `AndroidGattClient` (connect procedure waits for the connected deferred, which is failed
with `BlueLibError.ConnectionFailed(device, status)`) and `GattMapping.statusOf` (code → `GattStatus`).

## 2. Status 133 is almost always a resource leak

`GATT_ERROR` (133) is the most reported Bluetooth error on Android and has no single cause, but the
dominant one is a `BluetoothGatt` that was never closed: each one holds a controller connection slot,
and after a handful the stack refuses new connections. The second cause is connecting the same device
twice.

**Enforced in** `AndroidGattClient`: a session per device (a second `connect` returns the live session),
`close()` always disconnecting and closing, and `openSessions` for leak assertions in tests.

## 3. One outstanding ATT request per connection

ATT allows a single outstanding request. Issuing a second read or write before the first callback returns
loses one of them, silently, with no error on most stacks.

**Enforced in** `GattOperationQueue`: one operation at a time per session, each with a timeout, and the
name of the operation in flight exposed as `currentOperation` for diagnostics.

## 4. GATT values are only valid inside their callback

Before Android 13, `characteristic.value` is overwritten by the next operation, and `onCharacteristicChanged`
hands over a buffer that is reused. Copying is mandatory; keeping the array is a data race.

**Enforced in** `AndroidGattSession`: every callback copies (`value.copyOf()`) before completing the
deferred, including notifications.

## 5. The service cache goes stale

Android caches the GATT database per device. A peripheral that changed its services keeps answering with
the old tree until the cache is invalidated, and a handle-based read then fails with
`GATT_DATABASE_OUT_OF_SYNC` (0x12). `BluetoothGatt.refresh()` — the usual internet advice — is hidden API.

**Enforced in** `AndroidGattSession`: `onServiceChanged` (Android 12+) clears the cached profile and
rediscovers, and `GattStatus.GATT_DATABASE_OUT_OF_SYNC` is marked retryable so `RetryPolicy` reissues the
discovery.

## 6. Notifications must be re-armed after a reconnect

Reconnecting does not restore subscriptions: the peripheral stops sending unless the Client
Characteristic Configuration descriptor is written again. Apps that forget this show a UI that works
once and then goes quiet.

**Enforced in** `AndroidGattSession`: `subscribe()` records the CCCD value and `restoreSubscriptions()`
re-writes it on every `STATE_CONNECTED`.

## 7. A GATT server that does not answer hangs the central

A request from a central must be answered exactly once. Silence costs the central a 30 second ATT
timeout, and several stacks stop accepting further requests from that device meanwhile. Answering twice
is rejected by the platform (`sendResponse` returns `false`).

**Enforced in** `AndroidGattServer` + `GattServerState`: every request is answered from the local model by
default, with `autoRespond = false` for applications that answer by hand, and a rejected
`sendResponse` is reported as `OperationRejected`.

## 8. Write commands must not be answered

`BluetoothGattServerCallback.onCharacteristicWriteRequest(…, responseNeeded = false)` is an ATT Write
Command: answering it is a protocol error and the platform rejects the call.

**Enforced in** `AndroidGattServer.emitRequest(…, allowResponse = responseNeeded)`.

## 9. A long write is invisible until it is executed

The ATT prepare/execute sequence accumulates chunks and publishes them only on Execute Write. Publishing
per chunk corrupts the value when the central cancels, and the 512 byte ATT limit on the total value is
frequently ignored.

**Enforced in** `GattServerState.prepareWriteChunk` / `executePreparedWrites`, tested for the cancelled
case, the out-of-order chunk case and the single-write case (`GattServerStateTest`).

## 10. Values longer than MTU − 3 are truncated by the client

`writeCharacteristic` with a payload larger than `MTU − 3` writes a prefix and reports success. The
`longWrite` helper has the same shape but needs the ATT long-write procedure, which is limited to 512
bytes.

**Enforced in** `PayloadSegmenter.validateWrite` / `segment`: an oversized single write is rejected with a
validation error that says how to split it, and `WriteMode.LONG` chunks the payload explicitly.

## 11. The paired-device list and adapter state need `BLUETOOTH_CONNECT`

From Android 12, `adapter.bondedDevices`, `adapter.isEnabled`, `device.name` and `device.address` need
`BLUETOOTH_CONNECT`, and some OEM builds throw even when it is granted.

**Enforced in** `AndroidAdapterSource.requireReady`, `AndroidClassicPort.refreshBondedDevices` and the
`PermissionGateway`, all of which convert the failure into `PermissionMissing` instead of letting a
`SecurityException` escape.

## 12. `autoConnect = true` hides failures

With `autoConnect = true` the platform keeps a "background" connection attempt that never times out and
reports no failure, so a failed connect looks like a connect that is still trying.

**Enforced in** `GattConnectRequest`: `autoConnect` defaults to `false` and the KDoc says why. Android 17's
`BluetoothGattConnectionSettings` keeps the same default.

## 13. Scanning is quota-limited and exclusive

Android allows one `BluetoothLeScanner` scan per app and throttles starts to five per 30 seconds,
reporting `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` when the limit is hit. A second `startScan` without
stopping the first replaces the callback silently.

**Enforced in** `ScanQuotaGovernor` (budget before the platform call, `recordPlatformThrottle` when the
platform still refuses) and `AndroidBleScanner` (`BlueLibError.ScanAlreadyActive` for a second concurrent
scan, and the platform scan always stopped when the collector is cancelled).

## 14. Advertising payload budgets are strict and silent

Legacy advertising is 31 bytes total *including* the flags field Android prepends; extended advertising
is up to `BluetoothAdapter.getLeMaximumAdvertisingDataLength()` (usually 1650) but often less on OEM
stacks. Exceeding the budget fails with `ADVERTISE_FAILED_DATA_TOO_LARGE` on some devices and truncates
silently on others.

**Enforced in** `AdvertisingPayload.validate`, called by `AndroidBleAdvertiser` before every platform call
with the device's real maximum.
