# 0001 — Multi-module clean architecture

* Status: accepted
* Date: 2026-09-21
* Deciders: BlueLib maintainers

## Context

Android's Bluetooth surface changed in every release from 5.0 to 17, and it is full of behaviour that
cannot be unit tested without a device: status codes delivered inside callbacks, one-outstanding-request
limits on ATT, scan quotas enforced by the platform, payload budgets that are truncated rather than
rejected.

A single-module library would put that behaviour next to `android.bluetooth` calls, where it can only be
exercised by an emulator or a shelf of real devices.

## Decision

Split the library into modules along the dependency rule:

* `bluelib-core` — domain, validation, policies, state machines, ports, error taxonomy. Pure Kotlin, and
  the build fails if it ever imports `android.*` or `androidx.*`.
* `bluelib-android` — the adapters that implement the ports with `android.bluetooth`, plus the
  permission gateway, capability probes and the platform dispatcher.
* `bluelib` — the `BlueLib` facade that wires the adapters and exposes a stable surface.
* `bluelib-testing` — fakes for every port.

## Consequences

**Good**

* Policies and state machines are covered by fast JVM tests; the GATT server's answer logic is tested
  without a device because `GattServerState` holds the attribute state with no Android types.
* The compatibility story is centralised: `ApiLevel` and `PlatformCapabilities` are the only places the
  SDK level matters, and they are small enough to review as a whole.
* Applications can test their own Bluetooth feature with `bluelib-testing` and no hardware.
* A future non-Android target reuses `bluelib-core` unchanged.

**Bad**

* More Gradle plumbing, more modules to publish, and three artifacts where a competitor ships one.
  Mitigated by a single dependency: `bluelib` brings the others transitively.
* Some types are `public` only because Gradle modules cannot share `internal` visibility. Those carry
  the `@BlueLibInternal` opt-in annotation so the intent is explicit and applications are steered to the
  facade.

## Alternatives considered

* **One module with `internal` visibility.** Rejected: `internal` does not cross module boundaries, so
  the facade could not be a separate artifact or the platform layer would have to be public anyway —
  while still being untestable in isolation.
* **Pure reflection-based wrapper.** Rejected as brittle and liable to be blocked by future Android
  releases; the whole point is to use the public SDK surface correctly.
