# Changelog

All notable changes to BlueLib are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.0.0 onwards.

## [Unreleased]

## [0.1.0] — 2026-09-21

First public release. Supports Android 5.0 (API 21) through Android 17 (API 37).

### Added

* **Modules** — `bluelib` (facade), `bluelib-android` (platform adapters), `bluelib-core` (pure Kotlin
  domain), `bluelib-testing` (fakes for every port).
* **Permissions** — the complete Android 5 → 17 permission matrix, a gateway that distinguishes a denied
  permission from one the app never declared, `neverForLocation` support, and an
  `ACTION_REQUEST_ENABLE` helper instead of the deprecated `BluetoothAdapter.enable()`.
* **Scanning** — `scan(ScanRequest)` returning `ScanEvent.Observed`/`Lost`/`Failed`, platform filters plus
  a documented software fallback for name prefixes, presence tracking, single-scan enforcement, and a
  `ScanQuotaGovernor` that predicts Android's five-starts-per-30-seconds throttle.
* **Advertising** — legacy and extended advertising sets, payload validation against the device's real
  maximum before the platform call, discoverable sets on API 34+, cancellation-safe start, and locally
  allocated advertising set ids (the platform does not expose them on API 37).
* **GATT client** — a full connect procedure (transport, auto-connect, MTU, priority, PHY, discovery),
  Android 17 `BluetoothGattConnectionSettings`, one session per device to avoid status 133, a serialised
  operation queue, byte-array-preserving callbacks on Android 13+, and subscriptions that re-arm
  themselves after a reconnect.
* **GATT server** — local services, automatic request answering (`autoRespond = false` to opt out),
  Client Characteristic Configuration tracking, ATT prepare/execute long writes, notifications and
  indications with MTU validation, and an honest `FeatureUnsupported` for server-initiated MTU requests.
* **Bluetooth Classic** — BR/EDR discovery with RSSI on API 30+, bonding that waits for the platform's
  confirmation, bond-loss reasons from Android 16.1, Android 17 autonomous re-pairing awareness,
  RFCOMM and L2CAP sockets, `BluetoothSocketSettings` on API 36+, and Companion Device Manager based
  unbonding on API 33+.
* **Diagnostics** — one bounded event stream carrying operations, state transitions, capability probes and
  typed errors, with platform status codes preserved.
* **Errors** — 24 typed errors with stable codes, `isRetryable`, structured context and a documentation
  anchor, plus `GattStatus` with the full status-to-action mapping.
* **Docs** — getting started, architecture, seven guides, a troubleshooting reference keyed by error
  anchor, research notes for every Android release from 5 to 17, four ADRs, the release process, the
  roadmap, and a compatibility matrix generated from the Android SDK `api-versions.xml`.
* **Build** — Gradle 9.5, AGP 9.3.2, Kotlin with built-in AGP support, `minSdk 21`, `compileSdk 37`,
  lint `NewApi`/`MissingPermission` as errors, explicit API mode for shipped code, a
  `verifyNoAndroidImports` domain check, Maven Central publishing with opt-in signing, and CI that also
  fails on a stale compatibility matrix.

[Unreleased]: https://github.com/cybersafetyid/BlueLib/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/cybersafetyid/BlueLib/releases/tag/v0.1.0
