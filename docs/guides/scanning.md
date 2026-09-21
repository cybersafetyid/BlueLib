# Scanning

```kotlin
blueLib.scan(
    ScanRequest(
        serviceUuids = listOf(BluetoothUuid.HEART_RATE),
        mode = ScanMode.LOW_LATENCY,
        timeoutMillis = 15_000,
        namePrefixes = listOf("HR-"),
    ),
).collect { event -> … }
```

## One scan at a time, and it stops when you stop

Android allows one LE scan per app per `BluetoothLeScanner`, and a second `startScan` silently *replaces*
the callback of the first. BlueLib makes that an error:

* a second concurrent `scan()` emits `ScanEvent.Failed(BlueLibError.ScanAlreadyActive)`;
* cancelling the collecting coroutine stops the platform scan (`stopScan` plus `flushPendingScanResults`);
* `ScanRequest.timeoutMillis` stops the scan on its own, which matters when the collector is a long-lived
  scope that nobody cancels.

`blueLib.isScanning` tells you whether the platform scan is currently running.

## The 5-per-30-seconds quota

Android throttles `startScan` calls to five per app per 30 seconds and reports
`SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (0x05) when you exceed it. OEM builds sometimes throttle harder.

`ScanQuotaGovernor` keeps the budget in the domain layer: the sixth start inside the window is rejected
*before* the platform call with the exact delay to wait.

```kotlin
is ScanEvent.Failed -> when (val error = event.error) {
    is BlueLibError.ScanThrottled -> delay(error.retryAfterMillis)
    is BlueLibError.ScanFailed -> when (error.reason) {
        ScanFailureReason.SCANNING_TOO_FREQUENTLY -> delay(1_000)
        else -> report(error)
    }
    else -> report(error)
}
```

Tune the window through `BlueLibConfig(scanStartsPerWindow = …, scanQuotaWindowMillis = …)` only if an OEM
documents different limits. Restarting a scan is what costs budget — keep one scan running and filter in
software instead.

## Filters: platform first, software second

`ScanRequest` splits filters into the two kinds that exist for real:

| Filter | Where it runs | Notes |
| --- | --- | --- |
| `serviceUuids` | platform | Offloaded to the controller when `isOffloadedFilteringSupported` |
| `deviceAddresses` | platform | Address filters |
| `manufacturerData` | platform | Manufacturer-specific data filters |
| `namePrefixes` / `nameExact` | BlueLib | Software filter, because Android cannot express name matching in a `ScanFilter` and silently ignoring it is worse |
| `adTypeFilters` (Android 13+) | platform | `ScanFilter` AD-type matching on API 33+ |
| `rssiAtLeast` | BlueLib | Software, applied to every result |

Every observation carries the raw payload as well as the parsed form, so a consumer never loses data on
OEM stacks that drop part of the parsed record:

```kotlin
data class ScanObservation(
    val deviceId: BluetoothDeviceId,
    val rssi: Int,
    val txPower: Int?,
    val connectable: Boolean?,
    val name: String?,
    val serviceUuids: List<BluetoothUuid>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<BluetoothUuid, ByteArray>,
    val rawBytes: ByteArray?,          // the complete advertising payload
    val phy: Phy?,                     // reported on API 26+
)
```

## Presence tracking

`ScanEvent.Lost(observation, reason)` is emitted when a device stops being seen — the reason is either
`NOT_SEEN_FOR_A_WHILE` (`ScanRequest.DEFAULT_LOST_AFTER_MILLIS`, 10 s) or `SCAN_STOPPED`. This is what a
device-list UI wants; the alternative (an ever-growing list) is the most common scanning bug in BLE apps.

## Background scanning

On Android 8+ the platform offers a `PendingIntent` based scan that delivers results to a broadcast
receiver, which is the supported way to scan without holding a foreground service. On Android 14+
a long-running scan in the background generally needs:

* the `connectedDevice` foreground service type, declared **and** with the matching permission
  (`FOREGROUND_SERVICE_CONNECTED_DEVICE`) and a justification when the service starts;
* or a `PendingIntent` scan if the app is exempt from background scan restrictions.

BlueLib keeps the platform scan alive for as long as the flow is collected, so the correct pattern is:

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
scope.launch { blueLib.scan(request).collect { … } }
// and later
scope.cancel()   // stops the platform scan
```

Do not start a scan from a broadcast receiver that lives for milliseconds; the scan dies with the
receiver.

## Common traps

* **Scanning while connecting.** Discovery and an LE connection compete for the radio: a scan running
  during a connection attempt raises the failure rate for both. Stop scanning before connecting.
* **Trusting `rssi` as a distance.** It is a rough signal strength; use it for ordering, not for
  centimetres (see the [Roadmap](../roadmap.md) for Channel Sounding, which is the real answer).
* **Ignoring `SCAN_FAILED_APP_REGISTRATION_FAILED`.** It means the app's scan registration was rejected —
  usually too many registered scanners from the same process, and it never recovers without restarting
  the scan.
