# Testing

BlueLib is designed so that the hard parts are testable without a device, and so that you can test your
own Bluetooth code the same way.

## Layered test strategy

| Layer | Runs on | What it proves | Speed |
| --- | --- | --- | --- |
| `bluelib-core` unit tests | JVM | Policies, validation, state machines, error taxonomy | milliseconds |
| `bluelib-android` unit tests | JVM | GATT server answer logic (`GattServerState`), permission gateway, scan mapping | milliseconds |
| Application tests with `bluelib-testing` | JVM | Your feature end to end against fakes | milliseconds |
| Instrumentation tests | Emulator/device | Real `android.bluetooth` interaction | seconds to minutes |
| Field tests | Two devices | RF behaviour, RSSI, throughput, OEM quirks | — |

An emulator has no Bluetooth radio, so the third and fourth rows are not interchangeable: emulator tests
verify that your code calls the right APIs, real-device tests verify that those calls work.

## Using `bluelib-testing`

```kotlin
class HeartRateViewModelTest {

    private val clock = FakeClock()
    private val diagnostics = RecordingDiagnostics(clock)
    private val gatt = FakeGattClient()
    private val deviceId = BluetoothDeviceId.of("AA:BB:CC:DD:EE:FF")

    @Test
    fun `retries a congested read`() = runTest {
        val session = FakeGattSession(deviceId)
        session.nextReadReturns(failureOf(BlueLibError.GattOperationFailed("read", GattStatus.CONNECTION_CONGESTED)))
        session.nextReadReturns(successOf(byteArrayOf(0x2A)))

        val value = retryPolicy.run { session.read(service, characteristic) }

        assertEquals(listOf<Byte>(0x2A), value.getOrThrow().toList())
    }

    @Test
    fun `reports a missing permission`() {
        val permissions = FakePermissionPort().denyAll()
        assertFalse(permissionGateway(permissions).hasPermissionFor(BluetoothOperation.SCAN))
    }
}
```

Available fakes:

| Fake | Behaviour you can script |
| --- | --- |
| `FakeClock` | `advanceBy(millis)` — expires timeouts and scan quotas without waiting |
| `RecordingDiagnostics` | `recordedEvents`, `reportedErrors`, `clear()` |
| `FakePermissionPort` | `set(permission, status)`, `grantAll()`, `denyAll(permanently = true)` |
| `FakeAdapterSource` | Feature set, API level, `setState("OFF")` mid-test |
| `FakeBleScanPort` | Scripted scan events, `failWith(error)`, `startedRequests` |
| `FakeBleAdvertisePort` | `started`, `stopped`, configurable unavailability |
| `FakeGattClient` | `connectRequests`, `failNextConnect(error)`, `sessionFor(deviceId)` |
| `FakeGattSession` | `nextReadReturns`, `nextWriteReturns`, `emitNotification`, `reads`, `writes`, `closed` |
| `FakeGattServer` / `FakeGattServerPort` | `simulateRequest`, `responses`, `notifications` |
| `FakeClassicPort` | `discoveryEvents`, `bondRequests`, `bondFailure` |

## Testing the GATT server without Android

`GattServerState` is `internal`, and the module's unit tests can reach it because they are compiled in the
same module. That is how the answering rules are verified:

```kotlin
val state = GattServerState(GattServerConfig(services = listOf(serviceDefinition)))
state.seed()
state.prepareWriteChunk(device, service, characteristic, offset = 0, payload = byteArrayOf(0x01))
assertArrayEquals(ByteArray(0), state.valueOf(service, characteristic))   // not published yet
state.executePreparedWrites(device, execute = false)                       // central cancelled
```

If you are testing *your* GATT server logic, model it the same way: keep the attribute state in a plain
Kotlin class and let the Android callback do nothing but translate and delegate.

## Robolectric: why the domain is kept pure

Robolectric 4.16 supports API 23–36 and drops API 21/22. BlueLib supports API 21, so anything that only
exists in the framework cannot be tested at the low end of the supported range. The consequence is a
design rule, not a test-time workaround: **logic that must hold on API 21 lives in `bluelib-core`**, where
it runs on a plain JVM at any API level. `BondState.fromPlatformValue`, for instance, maps the platform's
`BOND_*` constants in the domain so the mapping is tested on the JVM instead of on a Lollipop device.

For the platform layer itself, the tests cover the pieces that are pure (mapping, gateway logic, server
state) and leave the remainder to instrumentation tests with a real radio.

## Instrumentation and field testing

`androidTest` should cover the things a fake cannot:

1. Permission flows on a real device (grant, deny, permanently deny, revoke from settings).
2. Adapter state changes while an operation is in flight (turn Bluetooth off during a scan).
3. A real peripheral: connect, discover, subscribe, verify notifications, verify the profile snapshot.
4. Both roles with two devices: your server + a central, your client + a peripheral.
5. A reconnect that must restore subscriptions.

Assert on `BlueLibError.code` rather than on error messages (which are for humans), and keep
`RecordingDiagnostics` attached so a failure report contains what the platform actually said.
