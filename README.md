# BlueLib

[![CI](https://github.com/cybersafetyid/BlueLib/actions/workflows/ci.yml/badge.svg)](https://github.com/cybersafetyid/BlueLib/actions/workflows/ci.yml)
[![Docs](https://github.com/cybersafetyid/BlueLib/actions/workflows/docs.yml/badge.svg)](https://github.com/cybersafetyid/BlueLib/actions/workflows/docs.yml)
[![Release](https://github.com/cybersafetyid/BlueLib/actions/workflows/release.yml/badge.svg)](https://github.com/cybersafetyid/BlueLib/actions/workflows/release.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.cybersafetyid/bluelib)](https://central.sonatype.com/artifact/io.github.cybersafetyid/bluelib)
[![Documentation](https://img.shields.io/website?url=https%3A%2F%2Fcybersafetyid.github.io%2FBlueLib%2F&label=documentation)](https://cybersafetyid.github.io/BlueLib/)
[![API](https://img.shields.io/badge/API-21%E2%86%9237-3DDC84)](https://cybersafetyid.github.io/BlueLib/compatibility-matrix/)
[![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE)
<!-- The Maven Central badge is live and switches to v0.1.0 by itself once the release publishes; until then it reads 'not found', which is the truth. -->

**A Bluetooth library for Android that runs on Android 5.0 (API 21) all the way to Android 17 (API 37).**

BlueLib wraps the whole Android Bluetooth surface — BLE scanning and advertising, GATT client and
server, Bluetooth Classic discovery, bonding, RFCOMM and L2CAP — behind one coroutine-first API with
typed errors, explicit compatibility handling and no hidden APIs.

```kotlin
val blueLib = BlueLib.create(context)

blueLib.scan(ScanRequest(timeoutMillis = 10_000))
    .filterIsInstance<ScanEvent.Observed>()
    .collect { println("${it.observation.deviceId} rssi=${it.observation.rssi}") }

val session = blueLib.connect(deviceId).getOrThrow()
session.write(service, characteristic, payload, WriteMode.WITH_RESPONSE)
    .onFailure { error -> println("${error.code}: ${error.message}") }
```

## Why another Bluetooth library?

Because Android's Bluetooth API is 11 years wide and its failure modes are invisible. BlueLib exists
to make those failures explicit:

| Problem in the platform | What BlueLib does |
| --- | --- |
| `connectGatt` failures arrive as `status` values inside callbacks | Every GATT status is mapped to a typed `BlueLibError` with `isRetryable` and a documentation anchor |
| `status 133` from a leaked `BluetoothGatt` | One session per device, always closed, with leak reporting in `openConnections` |
| Android throttles apps to 5 scan starts per 30 seconds | `ScanQuotaGovernor` refuses a sixth start *before* the platform does, and reports the exact retry delay |
| Advertising payloads are silently truncated | The byte budget (31 legacy, `leMaximumAdvertisingDataLength` extended) is validated before the platform call |
| Notifications stop after a reconnect | Subscriptions are remembered and re-armed with the Client Characteristic Configuration descriptor |
| `BLUETOOTH`/`ACCESS_FINE_LOCATION` vs `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` | A permission gateway that distinguishes *denied by the user* from *never declared in the manifest* |
| New APIs appear mid-generation (Android 16.1 is API 36.1) | `ApiLevel.isAtLeast(36, 1)` reads `getMinorSdkVersion()`, so minor releases are handled instead of ignored |
| A GATT server can hang a central for 30 seconds | Requests are answered from the local model by default, with `autoRespond = false` as the escape hatch |

## Modules

| Artifact | Contents |
| --- | --- |
| `io.github.cybersafetyid:bluelib` | The `BlueLib` facade: one object wiring everything, plus the capability report |
| `io.github.cybersafetyid:bluelib-android` | Platform adapters: permissions, adapter state, scanner, advertiser, GATT client/server, Classic |
| `io.github.cybersafetyid:bluelib-core` | Pure Kotlin domain: models, validation, policies, state machines, error taxonomy. No Android imports at all |
| `io.github.cybersafetyid:bluelib-testing` | Fakes for every port, so application code can be tested without a radio |

```kotlin
dependencies {
    implementation("io.github.cybersafetyid:bluelib:0.1.0")
    testImplementation("io.github.cybersafetyid:bluelib-testing:0.1.0")
}
```

## Requirements

- **minSdk 21** (Android 5.0 Lollipop). Every platform call is guarded by an API level check, and the
  Android Lint `NewApi`/`MissingPermission` checks run as errors in CI.
- **compileSdk 37** and **AGP 9.1.1+** (the first toolchain that ships API 37).
- **Java 17** for the build; the artifacts target JVM 17 bytecode, which Android desugars down to the
  running API level.

## Documentation

The full manual is published at **[cybersafetyid.github.io/BlueLib](https://cybersafetyid.github.io/BlueLib/)**
and redeploys automatically on every push to `main` that touches the docs. The same pages, browsable in
this repository:

| Guide | What it covers |
| --- | --- |
| [Getting started](docs/getting-started.md) | Install, first scan, permissions, lifecycle |
| [Architecture](docs/architecture.md) | Clean architecture layout, ports and adapters, why the modules are split |
| [Permissions and pairing](docs/guides/permissions-and-pairing.md) | The permission matrix from Android 5 to 17, bonding, Android 17 autonomous re-pairing |
| [Scanning](docs/guides/scanning.md) | Filters, batching, background scans, the scan quota |
| [Advertising](docs/guides/advertising.md) | Legacy vs extended sets, payload budgets, Auracast positioning |
| [GATT client](docs/guides/gatt-client.md) | Connect, MTU, PHY, reads/writes, notifications, reconnection |
| [GATT server](docs/guides/gatt-server.md) | Local services, auto-answering, subscriptions, long writes |
| [Bluetooth Classic](docs/guides/classic.md) | Discovery, bonding, RFCOMM and L2CAP sockets |
| [Testing](docs/guides/testing.md) | Using `bluelib-testing`, Robolectric and on-device test strategy |
| [Compatibility matrix](docs/compatibility-matrix.md) | Generated from the Android SDK `api-versions.xml` — every API BlueLib depends on, and when it arrived |
| [Troubleshooting](docs/troubleshooting.md) | One section per error code, with the action that fixes it |
| [Research notes](docs/research/android-17-bluetooth.md) | What each Android release from 5 to 17 changed for Bluetooth, with sources |
| [Roadmap](docs/roadmap.md) | What is shipped today and what comes next |

## Building

```bash
./gradlew check                              # tests, lint, domain purity checks
./gradlew generateCompatibilityMatrix        # refresh docs/compatibility-matrix.md
./gradlew publishAllPublicationsToStagingRepository   # produce a Maven Central bundle
mkdocs serve                                 # preview the documentation site
```

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).

BlueLib is not affiliated with the Bluetooth SIG, Google or any device vendor. It is an independent
open source effort by [cybersafetyid](https://github.com/cybersafetyid).
