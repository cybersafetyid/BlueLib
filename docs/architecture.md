# Architecture

BlueLib is a clean-architecture library: a pure domain in the middle, Android adapters around it, and a
facade on top. The point is not purity for its own sake — it is that the parts which are hard to get
right (retry policy, scan quota, MTU negotiation, payload validation, state machines) can be tested on
the JVM, in milliseconds, without a radio or an emulator.

```
┌───────────────────────────────────────────────────────────────────┐
│ app code                                                          │
│   BlueLib.create(context)  ·  scan()  ·  connect()  ·  classic     │
└───────────────────────────────┬───────────────────────────────────┘
                                │ facade + capability report
┌───────────────────────────────▼───────────────────────────────────┐
│ bluelib  (io.github.cybersafetyid:bluelib)                        │
│   BlueLib · PlatformReport                                        │
└───────────────────────────────┬───────────────────────────────────┘
                                │ wires ports to adapters
┌───────────────────────────────▼───────────────────────────────────┐
│ bluelib-android  (io.github.cybersafetyid:bluelib-android)         │
│   ApiLevel · PlatformCapabilities · PermissionGateway              │
│   AndroidAdapterSource · AndroidBleScanner · AndroidBleAdvertiser  │
│   AndroidGattClient · AndroidGattSession · GattOperationQueue      │
│   AndroidGattServer  · GattServerState  · AndroidClassicPort       │
│   AndroidDiagnostics · PlatformDispatchers                         │
└───────────────────────────────┬───────────────────────────────────┘
                                │ implements ports
┌───────────────────────────────▼───────────────────────────────────┐
│ bluelib-core  (io.github.cybersafetyid:bluelib-core) — no android.*│
│   domain/model     BluetoothAddress · BluetoothUuid · GattProfile  │
│                    ScanObservation · AdvertisingRequest · …        │
│   domain/validation AdvertisingPayload · PayloadSegmenter          │
│   domain/policy    RetryPolicy · MtuNegotiationPolicy · ScanQuota  │
│   domain/state     ConnectionStateMachine · AdapterStateMachine    │
│   domain/error     BlueLibError · BlueLibResult · GattStatus       │
│   port             BleScanPort · GattClientPort · ClassicPort · …  │
└───────────────────────────────────────────────────────────────────┘
bluelib-testing  → fakes for every port (JVM only)
```

## The dependency rule

`bluelib-core` may not import `android.*` or `androidx.*`. That is not a convention, it is a build
check:

```kotlin
// bluelib-core/build.gradle.kts
val verifyNoAndroidImports by tasks.registering { … }
tasks.named("check") { dependsOn(verifyNoAndroidImports) }
```

Two things follow, and both are load-bearing:

1. **The domain is testable.** `ScanQuotaGovernor`, `RetryPolicy`, `PayloadSegmenter` and both state
   machines are covered by fast JVM tests.
2. **The domain is portable.** A future Kotlin Multiplatform or Java-only target would move to
   `bluelib-core` unchanged, including the error taxonomy.

## Ports and adapters

Every platform capability is an interface in `bluelib-core/port`:

| Port | Responsibility | Android adapter |
| --- | --- | --- |
| `BleScanPort` | Start/stop LE scans, emit `ScanEvent` | `AndroidBleScanner` |
| `BleAdvertisePort` | Start/stop advertising sets | `AndroidBleAdvertiser` |
| `GattClientPort` | Connect, own the session registry | `AndroidGattClient` |
| `GattSession` | Reads, writes, descriptors, notifications, MTU, PHY | `AndroidGattSession` |
| `GattServerPort` / `GattServer` | Local services and request answering | `AndroidGattServerHost` / `AndroidGattServer` |
| `ClassicPort` | Discovery, bonding, RFCOMM, L2CAP | `AndroidClassicPort` |
| `AdapterAvailabilityPort` | Adapter presence, state, capability probes | `AndroidAdapterSource` |
| `PermissionPort` | Manifest/runtime permission status | `AndroidPermissionPort` |
| `DiagnosticsPort` | Observability stream | `AndroidDiagnostics` |
| `ClockPort` | Wall clock, so policies are deterministic in tests | `SystemClockPort` |

`bluelib-testing` implements all of them, which is why an application can test its Bluetooth feature
end to end without a device.

## Why the platform layer is not one class

* **`ApiLevel`** is the only place the SDK level is compared. Both overloads carry
  `@ChecksSdkIntAtLeast`, so `if (ApiLevel.isAtLeast(31)) { … }` is *proof* for Android Lint rather than
  a suppression. The two-argument form reads `Build.getMinorSdkVersion()`, which matters for Android
  16.1 (API 36.1) where connection subrating and bond-loss reasons arrive *inside* a major release.
* **`PlatformCapabilities`** separates "the platform has it" from "this hardware has it". Most
  `BluetoothAdapter.isLe*Supported()` calls need `BLUETOOTH_CONNECT` on Android 12+, and OEM stacks
  occasionally throw anyway; a probe failure is reported as unsupported *and* surfaced in diagnostics.
* **`PlatformDispatchers`** puts every `android.bluetooth` call on one dedicated thread. Android's
  Bluetooth stack is not thread safe the way most apps assume: operations issued concurrently on the
  same `BluetoothGatt` are dropped without an error, and some OEM stacks deadlock with two threads.
* **`GattOperationQueue`** serialises GATT operations per session. ATT allows exactly one outstanding
  request per connection; issuing two reads concurrently loses one of them silently.
* **`GattServerState`** holds the GATT server's attribute state with no Android types, so request
  answering, long writes and subscriptions are unit-tested on the JVM.

## State machines

`ConnectionStateMachine` and `AdapterStateMachine` live in the domain, and they are deliberately strict:

```
DISCONNECTED → CONNECTING → CONNECTED → DISCOVERING_SERVICES → READY
        ▲            │            │              │              │
        └────────────┴────────────┴──────────────┴──────────────┘  (DISCONNECTING, RECONNECTING)
                                                                   CLOSED is terminal
```

Illegal transitions are rejected rather than accepted silently, because "a callback arrived for a
connection I already closed" is the normal way Android behaves, not an exotic edge case.

## Error model

`BlueLibResult<T>` is a two-case sealed interface (`Success`, `Failure`) and `BlueLibError` is a closed
sealed interface of 24 cases with:

* `code: BlueLibErrorCode` — stable, log-safe, safe to send to analytics;
* `isRetryable` — derived from the platform status where one exists;
* `context` — structured key/value pairs for logs;
* `docsAnchor` — the section of [Troubleshooting](troubleshooting.md) that explains the fix.

Exceptions are used only where Kotlin requires a throwable: `BlueLibException` wraps an error for
`getOrThrow()` and for failing `Flow`s (a `Flow` cannot complete with an error value).

## Build layout

| Convention plugin | Purpose |
| --- | --- |
| `bluelib.kotlin.jvm` | JVM module: Java 17, JUnit 5, `-Xjsr305=strict` |
| `bluelib.android.library` | Android library: `minSdk 21`, `compileSdk 37`, lint as errors, explicit API mode for shipped code |
| `bluelib.publish` | Maven Central metadata, local staging repository, opt-in signing |
| `bluelib.api-matrix` | Regenerates [the compatibility matrix](compatibility-matrix.md) from the SDK database |

Explicit API mode (`-Xexplicit-api=strict`) applies to `main` sources only: what ships has to declare
its visibility, tests do not need to.

## Decisions

The reasoning behind the structure is recorded as ADRs:

* [0001 Multi-module clean architecture](adr/0001-clean-architecture.md)
* [0002 minSdk 21 through compileSdk 37](adr/0002-api-range.md)
* [0003 Typed errors instead of exceptions](adr/0003-typed-errors.md)
* [0004 No hidden or privileged APIs](adr/0004-no-hidden-apis.md)
