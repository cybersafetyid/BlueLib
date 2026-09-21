# Roadmap

This page is deliberately concrete about what exists today and what does not, including the parts that
are blocked by the platform rather than by effort.

## Shipped in 0.1.0

| Area | Status |
| --- | --- |
| Domain layer | Models, validation, MTU and retry policies, scan quota governor, connection and adapter state machines, error taxonomy — pure Kotlin, no Android imports |
| Permissions | Full matrix from Android 5 to 17, including denied vs never declared, `neverForLocation` support |
| Scanning | Legacy and extended scan settings, software fallback filters, presence tracking (`Observed`/`Lost`), scan quota with platform-throttle feedback, single-scan enforcement |
| Advertising | Legacy sets on API 21+, extended sets on API 26+, payload budget validation against the device maximum, discoverable flag on API 34+, cancellation-safe start |
| GATT client | Connect procedure with transport/auto-connect/opportunistic settings, Android 17 `BluetoothGattConnectionSettings`, MTU negotiation, connection priority (including DCK), PHY requests, profile discovery, reads/writes/long writes, descriptor access, subscriptions with automatic re-arming, one session per device |
| GATT server | Local services, automatic request answering with an `autoRespond = false` opt-out, CCCD tracking, long write prepare/execute, notifications and indications, MTU reporting |
| Bluetooth Classic | BR/EDR discovery with RSSI on API 30+, bonding with Android 16.1 bond-loss reasons and Android 17 autonomous re-pairing awareness, RFCOMM sockets, L2CAP channels, `BluetoothSocketSettings` on API 36+, Companion Device Manager based unbonding on API 36+ (the first release with a public `removeBond`) |
| Diagnostics | One event stream for operations, state changes, capability probes and errors, with platform status codes preserved |
| Testing | `bluelib-testing` fakes for every port, JVM tests for the domain and for the platform logic that is genuinely pure |
| Docs | This site, the generated compatibility matrix, the research notes and the ADR set |

## Next

### 1. Ranging module (`bluelib-ranging`, API 36+)

Android 16 introduced the `android.ranging` module with Bluetooth Channel Sounding
(`RangingManager`, `BleCsRangingParams`) and RSSI ranging, guarded by `android.permission.RANGING`.
BlueLib currently *reports* the capability (`LE_CHANNEL_SOUNDING`, `LE_RSSI_RANGING`) so an application can
decide, but does not wrap the API yet.

What the module needs before it ships:

* a `RangingPort` in `bluelib-core` with a `RangingResult` model that does not leak `android.ranging` types;
* capability gating through `PlatformReport` plus the `android.hardware.bluetooth.le.channel_sounding`
  feature flag, which some devices miss even on API 36;
* a decision on result streaming: Channel Sounding produces distance, angle and quality per procedure,
  which is closer to a sampling API than to a request/response one;
* on-device tests with two devices that both support Channel Sounding — hard to run in CI, so the module
  would ship with a documented device matrix instead of a green CI badge.

### 2. LE Audio / Auracast module

Capability reporting is shipped. Controlling broadcasts is not, and the reason is a privilege boundary,
not an API gap:

| Role | API | Privilege |
| --- | --- | --- |
| Broadcast source (start an Auracast stream) | `BluetoothLeBroadcast` | system app / privileged permission |
| Broadcast assistant (help a user join a broadcast) | `BluetoothLeBroadcastAssistant` | system app / privileged permission |
| Capability queries | `BluetoothAdapter.isLeAudio*Supported()` | none (Android 13+) |

Shipping a wrapper that cannot work for ordinary apps would be worse than not shipping one, so the plan is
a `bluelib-auracast` module that is explicit about its requirement: it either stays documentation-only for
third-party apps, or targets system-app builds with a clearly marked `@RequiresSystemApp` API.

### 3. Richer connection recovery

`BlueLibConfig.reconnectPolicy` exists and the state machine models `RECONNECTING`, but the library does
not yet own the reconnect loop. The next step is a `ConnectionSupervisor` that:

* retries with the configured backoff, respecting `isRetryable`;
* backs off harder on repeated status 133 and escalates to "close and reopen" rather than retrying forever;
* re-arms subscriptions after every successful reconnect (already implemented inside the session) and
  re-reads the characteristics an application declares as required.

### 4. Payload helpers

`AdvertisingPayload` validates sizes. The natural follow-up is a builder for the AD structures themselves
(flags, complete/partial UUID lists, local name, service data, manufacturer data) shared between
advertising and scan-result parsing, so applications can construct and inspect payloads with one API.

### 5. Sample applications

A peripheral app (advertising + GATT server) and a central app (scan + connect + subscribe) in one Compose
project, used as the fixture for instrumentation tests and as the reference for the documentation.

## Explicitly out of scope

| Idea | Why not |
| --- | --- |
| Clearing the GATT service cache on demand | `BluetoothGatt.refresh()` is hidden API; rediscovery after `GATT_DATABASE_OUT_OF_SYNC` is the supported path |
| Unbonding a device the app is not associated with | No public API exists; the user must forget it in system settings |
| Enabling Bluetooth programmatically | Deprecated and privileged; `ACTION_REQUEST_ENABLE` is the supported flow |
| Thread-safety guarantees beyond one platform thread | Android's Bluetooth stack does not offer them; pretending otherwise would be a lie in the type system |
| A cross-platform (non-Android) implementation | `bluelib-core` is portable by design, but a real KMP target is a project, not a feature |
