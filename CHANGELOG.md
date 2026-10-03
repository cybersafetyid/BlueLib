# Changelog

All notable changes to BlueLib are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.0.0 onwards.

## [Unreleased]

## [0.2.0] — 2026-10-03

### Added

* **TCP/IP transport** — `blueLib.connectTcp(host, port)` opens a TCP client link (Wi-Fi or Ethernet/RJ45) for network printers, Modbus TCP, serial device servers and ESP32 firmware.
* **USB serial transport** — `blueLib.usbDevices()` and `blueLib.connectUsbSerial()` with built-in drivers for CDC-ACM, FTDI, CP210x and CH34x chips plus raw bulk devices; no root and no third-party dependency. Handles the USB permission dialog.
* **Native UART transport** — `blueLib.openUart("/dev/ttyS1")` for the built-in RS-232/RS-485 ports of POS and industrial boards.
* **`ByteConnection`** — one transport-agnostic connection type; `ClassicConnection` now extends it. `SerialSettings` (baud, data bits, parity, stop bits) is shared by USB and UART.
* **`StreamMessenger`** and `blueLib.createMessenger(connection)` — the existing text/hex/binary/Base64 messaging and framers over any link.
* **`BlueLibError.LinkFailed`** (`LINK_FAILED`) for links that cannot be opened.
* **`FakeByteConnection`** in `bluelib-testing`.
* Docs: [TCP/IP, USB and serial guide](docs/guides/wired-and-network.md) and [device communication research](docs/research/device-communication.md).

### Changed

* `ClassicMessenger` is now a deprecated alias of `StreamMessenger`. Its `connection` property is typed `ByteConnection`.
* Classic sockets share the `StreamConnection` read loop: writes are serialized, the connection closes itself when the peer goes away, and up to 64 incoming chunks are buffered (was 16).
* `ClassicConnection` implementations must provide `endpoint`.

### Fixed

* **Bluetooth broadcasts never arrived** — receivers were registered with `RECEIVER_NOT_EXPORTED`, but `android.bluetooth.*` broadcasts come from the Bluetooth module (its own uid), so the app was left out of the recipient list. `bond()` always timed out, Classic discovery found no devices and bond/adapter changes were missed. All Bluetooth receivers now use `RECEIVER_EXPORTED` (they are protected broadcasts).
* **`GattSession.state` stayed `DISCONNECTED`** — the session never sent `CONNECT_REQUESTED`, so the state machine rejected `CONNECTED` and every later transition. It now reports `CONNECTING → CONNECTED → DISCOVERING_SERVICES → READY`, including autoConnect reconnects.
* **GATT notifications were never delivered** — `subscribe()` wrote the CCCD but never called `setCharacteristicNotification`, so Android dropped incoming notifications. Also applied when subscriptions are restored after a reconnect.
* **`ApiLevel.describe()` reported `API 37.36`** — `Build.getMinorSdkVersion` was given a major level instead of `SDK_INT_FULL`, which also made `isAtLeast(36, 1)` true on Android 16.0.
* **Classic discovery permission** — `CLASSIC_DISCOVERY` now requires `BLUETOOTH_SCAN` on Android 12+ (needed by `startDiscovery()`); `bond()` only requires `BLUETOOTH_CONNECT`.
* **`bondedDevices()` was empty** when `BLUETOOTH_CONNECT` was granted after `BlueLib.create()`; the list is now re-read on every collection.

### Sample app

* Redesigned with Material 3 in blue and white: Home (adapter, runtime permissions, capability report, codec playground), Scan & Pair (BLE scan, Classic discovery, paired devices, manual pair/unpair, auto pair with every filter), Connect (GATT client with services, read/write/notify, MTU, priority, PHY, GATT messenger; RFCOMM and L2CAP sockets), Peripheral (advertising, GATT server with a Nordic UART echo service), Links (TCP/IP, USB serial, UART) and an event log with BlueLib diagnostics.
* `DelimiterFramer.LINE_FEED`, `CRLF` and `NULL_BYTE` are deprecated in favor of `DelimiterFramer.lineFeed()`, `crlf()` and `nullByte()`, and now return a new framer on each access. Before, they were shared singletons: two messengers using the same one mixed their partial messages, and closing one messenger wiped the other's buffer.

## [0.1.2] — 2026-09-22

### Added

* **Sample App Demos** — Added interactive UI controls and flows in the `sample` application to demonstrate Dynamic Auto Pairing (`AutoPairFilter`) and Multi-Format Data Codecs (`DataCodec` / `DelimiterFramer`).

### Changed

* **Release Workflow Automation** — Updated GitHub Actions release workflow (`.github/workflows/release.yml`) to automatically extract release notes directly from `CHANGELOG.md` for GitHub release creation, enforcing changelog entry presence before release execution.
* **Code Robustness & Maintenance** — Refactored core domain models and Android platform adapters (`GattServerRequest.WriteDescriptor`, `PayloadSegmenter`, `AndroidGattClient`, `PlatformCapabilities`) for improved memory efficiency, sequence processing, explicit value equality checks, and cleaner API visibility.

## [0.1.1] — 2026-09-21

### Added

* **Dynamic Auto Pairing** — `AutoPairFilter` and `AutoPairEngine` providing dynamic discovery and automatic bonding based on MAC address, exact device name, name prefix, service UUIDs, manufacturer ID, and RSSI proximity threshold (`minRssi`).
* **Unified Bluetooth Messenger API** — `BluetoothMessenger` interface with `GattMessenger` (for BLE GATT characteristics) and `ClassicMessenger` (for RFCOMM/L2CAP sockets) to enable unified bi-directional messaging over connected Bluetooth sessions.
* **Multi-Format Data Codecs** — `DataCodec` providing encoding/decoding and strict validation for Text (UTF-8, ASCII, ISO-8859-1), Hex, Binary bit-streams, Base64 (standard and URL-safe), and Raw bytes with typed error reporting (`BlueLibValidationException.InvalidPayload`).
* **Packet Message Framers** — `MessageFramer` implementations including `DelimiterFramer` (`LINE_FEED`, `CRLF`, `NULL_BYTE`, or custom byte delimiters) and `LengthPrefixedFramer` (1, 2, or 4-byte headers) to handle message framing and stream packet reconstruction without memory overflow.
* **Facade Extensions** — Exposed `blueLib.autoPair()`, `blueLib.createGattMessenger()`, and `blueLib.createClassicMessenger()` on the primary `BlueLib` facade.

### Changed

* **Build Dependencies** — Refactored build logic to use Version Catalog references (`libs.kotlinx.coroutines.core` and `libs.kotlinx.coroutines.test`).
* **Validation Exceptions** — Refactored `BlueLibValidationException` constructor parameters for cleaner property access and added `BlueLibValidationException.EmptyFilter`.

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

[Unreleased]: https://github.com/cybersafetyid/BlueLib/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/cybersafetyid/BlueLib/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/cybersafetyid/BlueLib/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/cybersafetyid/BlueLib/releases/tag/v0.1.0
