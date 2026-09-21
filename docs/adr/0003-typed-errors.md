# 0003 — Typed errors instead of exceptions

* Status: accepted
* Date: 2026-09-21
* Deciders: BlueLib maintainers

## Context

Android's Bluetooth API fails in three different ways:

1. **A status code inside a callback** — `onCharacteristicRead(…, status)` with `status = 133`. Nothing
   throws, and the caller has to remember that non-zero means failure.
2. **A `boolean` return value** — `requestMtu()` returns `false` and the platform forgets the request.
3. **An exception** — `SecurityException` when `BLUETOOTH_CONNECT` is missing, `IOException` from a
   socket, `IllegalArgumentException` for a malformed UUID.

Applications must make decisions about these failures: retry, ask for a permission, tell the user the
device is out of range, or give up. A raw `int` or a bare exception is not enough to decide.

## Decision

Every fallible operation returns `BlueLibResult<T>` (`Success` / `Failure`), and every failure is a
`BlueLibError` with:

* a stable `code` (`BlueLibErrorCode`) that is safe to log or forward to analytics,
* `isRetryable`, derived from the platform status when one exists,
* structured `context` (device, operation, status, attempt),
* `docsAnchor` pointing at the matching [Troubleshooting](../troubleshooting.md) section.

The hierarchy is `sealed`, so a `when` over it stays exhaustive as the library grows.

Exceptions remain for the two cases where Kotlin requires a throwable:

* `BlueLibException(error)` for `getOrThrow()` and for failing a `Flow` mid-stream,
* `BlueLibValidationException` for input that is wrong regardless of the device (a malformed address, an
  out-of-range MTU), which is a programming error and should fail loudly in development.

## Consequences

**Good**

* A UI can be driven entirely by the error taxonomy: `UNEXPECTED` → report, `PERMISSION_MISSING` → ask,
  `SCAN_THROTTLED` → wait `retryAfterMillis`, `CONNECTION_LOST` → offer reconnect.
* Diagnostics and errors share one representation, so what a bug report contains is what the code saw.
* Retry logic (`RetryPolicy`) keys off `isRetryable`, so a non-retryable failure is never retried
  silently.

**Bad**

* Kotlin has no language-level support for "result types", so call sites use `.getOrThrow()`,
  `.getOrNull()` or `onFailure { }` instead of exceptions propagating naturally.
* Interop with Java callers is less idiomatic; they see `BlueLibResult` and have to check `isSuccess`.

## Alternatives considered

* **Throw everywhere.** Rejected: it hides the availability question ("is this retryable?") and forces
  callers to catch a dozen exception types that the platform never documented.
* **Kotlin's `Result<T>`.** Rejected: its failure case is a `Throwable`, so the taxonomy would have to be
  carried inside a throwable anyway, losing exhaustiveness and structured context.
