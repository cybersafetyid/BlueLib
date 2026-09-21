# GATT client

```kotlin
val session = blueLib.connect(
    deviceId,
    GattConnectRequest(
        transport = Transport.LE,
        mtu = 517,
        connectionPriority = ConnectionPriority.HIGH,
        preferHighThroughputPhy = true,
    ),
).getOrThrow()

val value = session.read(service, characteristic).getOrThrow()
session.write(service, characteristic, payload, WriteMode.WITH_RESPONSE).getOrThrow()
session.close()
```

## Connect semantics

`connect` performs the whole procedure, not just the platform call:

1. Permission and adapter check (`BLUETOOTH_CONNECT`, radio on) — failures come back as
   `PermissionMissing`/`BluetoothDisabled` rather than a `SecurityException`.
2. Transport setup. Android 17's `BluetoothGattConnectionSettings` is used when available (transport,
   auto-connect, automatic MTU, opportunistic flags in one object); the legacy overloads are used on
   API 21–36.
3. Wait for `onConnectionStateChange` with `status == 0` inside `GattConnectRequest.timeoutMillis`, with
   `BlueLibError.ConnectionFailed(device, status)` when the platform reports a status code, and
   `BlueLibError.Timeout` when nothing arrives.
4. MTU negotiation (`MtuNegotiationPolicy` rejects a granted MTU below 23 as `MtuNegotiationFailed`).
5. Connection priority and PHY requests, if asked for.
6. Service discovery, awaited so the returned session already has its `profile`.

Connecting the same device twice returns the **existing session**:

```kotlin
val first = blueLib.connect(deviceId).getOrThrow()
val second = blueLib.connect(deviceId).getOrThrow()
check(first === second)          // no second BluetoothGatt, no leaked connection slot
blueLib.openConnections          // diagnostic: what is still open
```

## Subscriptions

```kotlin
session.subscribe(service, characteristic)
    .onEach { bytes -> render(bytes) }
    .launchIn(scope)
```

* The Client Characteristic Configuration descriptor is written for you, with `0x01 0x00` for
  notifications and `0x02 0x00` for indications.
* Subscribing to a characteristic that declares neither `NOTIFY` nor `INDICATE` fails immediately with
  `CharacteristicNotNotifiable` (carrying the property bits) instead of a flow that never emits.
* Subscriptions are **re-armed automatically** after a reconnect. This is the single most common cause of
  "it worked once, then went quiet".
* The flow is backed by a bounded buffer (`DROP_OLDEST`, 32 values), so a slow collector cannot block
  the binder thread. If your UI must not lose a value, process quickly or hand off to a channel.

## Reads, writes and long writes

| Mode | ATT procedure | Use when |
| --- | --- | --- |
| `WriteMode.WITH_RESPONSE` | Write Request | the default: you need confirmation |
| `WriteMode.WITHOUT_RESPONSE` | Write Command | low latency, fire and forget, no confirmation |
| `WriteMode.LONG` | Prepare/Execute Write | the value is longer than `MTU − 3` (ATT caps the total at 512 bytes) |

BlueLib validates against the current MTU before writing, because Android silently truncates an oversized
value. If the payload does not fit, you get a validation error that says how many chunks you need.

## Connection priority and PHY

```kotlin
session.requestConnectionPriority(ConnectionPriority.HIGH)   // DCK needs Android 14+
session.requestPhy(phy = Phy.LE_2M)
session.readPhy()
```

* `ConnectionPriority.DCK` fails with `FeatureUnsupported` below API 34 instead of being ignored.
* PHY requests below API 26 fail the same way.
* `readPhy()` reports the last PHY BlueLib requested, because `onPhyRead` is not delivered reliably on
  several OEM stacks — a fact worth knowing before you build a UI on it.

## Disconnecting and closing

```kotlin
session.close()      // disconnect + close + cancel the session scope: always do this
```

Leaking a session leaks a controller connection slot, and after a few leaks *every* connection fails with
status 133 until the app process restarts. `BlueLib.close()` closes everything the instance owns, which is
why the instance should live in `Application`, not in an activity.

`session.state` is a `StateFlow<ConnectionState>`; `CLOSED` is terminal, and connecting the device again
after that creates a fresh session.

## Reconnection

`BlueLibConfig.reconnectPolicy` describes the intended behaviour, and the state machine exposes
`ConnectionState.RECONNECTING`. The recommended application pattern is:

```kotlin
session.state
    .filter { it == ConnectionState.DISCONNECTED }
    .collect { reconnectWithBackoff() }
```

Do not set `autoConnect = true` to "make it reconnect for you": with auto-connect the platform reports no
connect failure and can hold the attempt for a very long time, which turns a retry loop into a silent
hang. `GattConnectRequest.autoConnect` exists for the cases that genuinely need it, and defaults to
`false`.

## Status codes you will actually see

| Status | Meaning | What BlueLib does |
| --- | --- | --- |
| `0` | success | — |
| `2` | read not permitted | non-retryable; the characteristic does not allow reading |
| `5` / `15` | authentication / encryption insufficient | retryable via `EncryptionFailed`; usually a bond problem |
| `8` | request timeout | retryable |
| `19` | remote user terminated | non-retryable; the peripheral refused |
| `34` (`GATT_CONN_LMP_TIMEOUT`) | link supervision timeout | retryable |
| `133` (`GATT_ERROR`) | generic error | retryable with backoff, but treat repeated 133s as a leaked connection |
| `137` (`GATT_CONN_TERMINATE_LOCAL_HOST`) | local stack closed the link | retryable |
| `143` (`GATT_CONN_TERMINATE_PEER_USER`) | the peripheral closed it | retryable |
| `0x12` (`GATT_DATABASE_OUT_OF_SYNC`) | cached services are stale | BlueLib rediscovers |

`GattStatus.isRetryable` encodes this table, and `RetryPolicy` uses it, so a non-retryable failure is
never retried silently.
