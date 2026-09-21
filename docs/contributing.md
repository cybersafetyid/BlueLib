# Contributing

BlueLib targets an eleven-year Android range, which makes correctness more valuable than cleverness. The
rules below exist because each one has already caused a bug somewhere.

## Getting set up

```bash
git clone https://github.com/cybersafetyid/BlueLib.git
cd BlueLib
./gradlew check                              # tests, lint, domain purity
./gradlew generateCompatibilityMatrix        # refresh the generated matrix
mkdocs serve                                 # preview the docs locally
```

Requirements: JDK 17, Android SDK with `platforms;android-37` and `build-tools;37.0.0`. AGP 9.1.1 or newer
is required to build against API 37.

## Non-negotiable rules

1. **`bluelib-core` never imports `android.*` or `androidx.*`.** A Gradle check enforces it. Logic that has
   to hold on API 21 belongs in the domain precisely so it can be tested on a plain JVM (Robolectric 4.16
   does not run API 21/22).
2. **`ApiLevel` is the only place `Build.VERSION.SDK_INT` is compared.** Use its `@ChecksSdkIntAtLeast`
   annotated helpers so Android Lint can prove a guarded call, instead of suppressing `NewApi`.
3. **Minor API levels count.** Android 16.1 is API 36.1: use `ApiLevel.isAtLeast(36, 1)` when a feature
   arrived in a QPR release.
4. **No hidden, privileged or reflected APIs.** See
   [ADR 0004](adr/0004-no-hidden-apis.md). If the platform cannot do it, the answer is a typed
   `FeatureUnsupported` error with a message that says what to do instead.
5. **Failures are data.** Return `BlueLibResult.Failure` with a new or existing `BlueLibError`, not an
   exception, unless Kotlin requires a throwable.
6. **Every new public declaration needs a KDoc that explains *why*, not *what*.** The "what" is visible in
   the signature.
7. **Every behaviour change needs a test.** Domain logic → JVM test. Platform logic → JVM test if the logic
   is pure (see `GattServerState`), otherwise an instrumentation test plus a note in the pull request.

## Working on the platform layer

The platform layer is where the Android release differences live. When adding a branch for a new API level:

* guard it with `ApiLevel.isAtLeast(...)`;
* use the *better* new API when it is genuinely better, not merely newer (Android 17's
  `BluetoothGattConnectionSettings`, Android 13's value-carrying callbacks, Android 16's
  `BluetoothSocketSettings`);
* fail with `FeatureUnsupported` when the old API level cannot express the request, rather than ignoring
  the flag;
* verify signatures against the SDK rather than the documentation — `javap` on
  `$ANDROID_HOME/platforms/android-37.0/android.jar` is authoritative and has already caught two
  documentation errors (see the research notes).

## Pull requests

* One topic per pull request; a refactor and a behaviour change in the same change is unreviewable.
* Describe the failure you saw and how you verified the fix. "Works on my Pixel" is not verification;
  "fails before, passes after, on API 30 and API 36" is.
* Run `./gradlew check` before pushing — CI runs the same thing plus a stale-matrix check.
* If you touched anything that depends on the platform version, say which API levels you exercised.

## Commit messages

```
<scope>: <imperative summary>

Why the change is needed, in one or two sentences. Mention the platform behaviour it works around
and the API levels involved.
```

Scopes: `core`, `android`, `facade`, `testing`, `build`, `docs`.

## Reporting bugs

Include:

1. BlueLib version (`BlueLib.version`).
2. Device model, Android release *and* the exact API level (`BlueLib.version` plus
   `PlatformReport.apiLevelDescription`).
3. Peripheral model and firmware version — most GATT problems are peripheral problems.
4. The `BluetoothOperation` involved and the exact `BlueLibError` (`code`, `context`, `docsAnchor`).
5. A diagnostics trace: `blueLib.diagnostics` collected around the failure. It contains the platform
   status codes, which is usually the whole diagnosis.

Issues without a `code` from the error taxonomy are much harder to act on, because "it does not connect"
has at least eight distinct causes — see [Troubleshooting](troubleshooting.md#connection-failed).

## Licence

Contributions are accepted under the Apache License 2.0. By opening a pull request you agree to license
your contribution under the same terms.
