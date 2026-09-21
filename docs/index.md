# BlueLib

**Bluetooth for Android 5.0 (API 21) through Android 17 (API 37).** Coroutines first, typed errors,
and a compatibility story that is verified against the Android SDK database rather than remembered.

```kotlin
val blueLib = BlueLib.create(context)

blueLib.scan(ScanRequest(timeoutMillis = 10_000))
    .filterIsInstance<ScanEvent.Observed>()
    .collect { println(it.observation.deviceId) }
```

## What is in the box

| Capability | Platform support handled by BlueLib |
| --- | --- |
| LE scanning | Android 5.0+, `PendingIntent` background scans on 8.0+, legacy and extended scan settings, Android 13 AD-type filters, scan quota (5 starts / 30 s) |
| Advertising | Legacy advertising on 5.0+, extended advertising sets on 8.0+, discoverable sets on 14+, payload budgets validated per mode |
| GATT client | GATT over LE, `BluetoothGattConnectionSettings` on Android 17, MTU negotiation, PHY requests (2M and coded on 8.0+, high data throughput on 17), connection priority including DCK on 14+, notifications with automatic re-arming |
| GATT server | Local services, automatic request answering, Client Characteristic Configuration tracking, long writes, notifications and indications |
| Bluetooth Classic | BR/EDR discovery, bonding (including Android 16.1 bond-loss reasons and Android 17 autonomous re-pairing), RFCOMM and L2CAP sockets, `BluetoothSocketSettings` on Android 16+ |
| Permissions | The complete matrix from Android 5 to 17, including "denied" vs "never declared" |
| Diagnostics | Every platform operation, state transition and typed error on one flow, with status codes preserved |

## The design in one page

```
bluelib                 BlueLib facade: one object, one lifecycle
  └── bluelib-android   Platform adapters (android.bluetooth) → ports
        └── bluelib-core Domain: models, validation, policies, state machines (no Android imports)
bluelib-testing         Fakes for every port
```

* **The domain never imports `android.*`.** A Gradle check (`verifyNoAndroidImports`) fails the build if
  it ever does, which is what makes the interesting logic unit-testable on the JVM.
* **The platform layer owns every version check.** `ApiLevel` is the only place `Build.VERSION.SDK_INT`
  is compared, and its helpers carry `@ChecksSdkIntAtLeast` so Android Lint can prove each guarded call.
* **Errors are data.** `BlueLibResult<T>` carries a `BlueLibError` with a stable `code`, an
  `isRetryable` flag, structured `context` and a `docsAnchor` pointing at the matching section of
  [Troubleshooting](troubleshooting.md).

## Where to go next

<div class="grid cards" markdown>

- **[Getting started](getting-started.md)** — install, permissions, first scan.
- **[Architecture](architecture.md)** — why the modules are split this way.
- **[Compatibility matrix](compatibility-matrix.md)** — every platform API BlueLib relies on and the
  release that introduced it, generated from `api-versions.xml`.
- **[Troubleshooting](troubleshooting.md)** — start from the error code you saw.

</div>
