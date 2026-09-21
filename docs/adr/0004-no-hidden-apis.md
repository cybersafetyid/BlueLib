# 0004 — No hidden, privileged or reflected platform APIs

* Status: accepted
* Date: 2026-09-21
* Deciders: BlueLib maintainers

## Context

Bluetooth on Android has a long tradition of hidden APIs that would make the library more capable:
`BluetoothDevice.removeBond()`, `BluetoothDevice.requestMtu()`, `BluetoothGatt.refresh()`,
`BluetoothAdapter.enable()`/`disable()`, the `BluetoothA2dp` internals, `leMaximumAdvertisingDataLength`
before it was public.

Applications that use them hit three walls: `NoSuchMethodException` on some OEM builds, silent blocking
by the platform's hidden-API enforcement, and the Play Store's restrictions on privileged operations.

## Decision

BlueLib uses **only** the public SDK surface, and when something is unavailable it says so explicitly
instead of reaching around it:

| Want | Reality | BlueLib's answer |
| --- | --- | --- |
| Unbond a device | `BluetoothDevice.removeBond()` is still not public | `unbond()` uses `CompanionDeviceManager.removeBond(associationId)` on Android 13+, otherwise a typed error explaining the association requirement |
| Clear the GATT service cache | `BluetoothGatt.refresh()` is hidden | `GattStatus.GATT_DATABASE_OUT_OF_SYNC` is retried as a service rediscovery, which is the supported path |
| Enable or disable Bluetooth | `BluetoothAdapter.enable()` is deprecated and privileged | `enableBluetoothIntent()` returns `ACTION_REQUEST_ENABLE` for the app to launch |
| Request a higher MTU from the GATT server | No such API exists in the server role | `GattServer.requestMtu` fails with `FeatureUnsupported` explaining that the central owns the exchange, and BlueLib reports the negotiated value from `onMtuChanged` |
| Read a device's MAC address on Android 12+ | Available, but with `BLUETOOTH_CONNECT` | The permission gateway asks for it up front instead of letting `SecurityException` surface |

## Consequences

**Good**

* The library behaves predictably across OEM builds, because it never depends on an implementation
  detail that a vendor may have changed.
* No `SecurityException` surprises, no Play policy problems, no reflection that R8 can break.
* Every gap becomes documentation: the [Roadmap](../roadmap.md) lists what is impossible today and why.

**Bad**

* Some things applications might want are simply not offered (clearing the GATT cache on demand, forcing
  an unpair for a device the app is not associated with).
* `CompanionDeviceManager` association is required for unbonding, which is an extra flow for apps that
  want it.

## Alternatives considered

* **Reflection with a graceful fallback.** Rejected: it produces OEM-dependent behaviour, which is
  precisely the bug class BlueLib exists to remove.
* **Shipping a `@RequiresSystemApp` module.** Rejected as out of scope for a general purpose library;
  privileged features such as LE Audio broadcast source control belong to a system app.
