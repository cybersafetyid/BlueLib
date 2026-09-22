# Getting started

## 1. Add the dependency

```kotlin
dependencies {
    implementation("io.github.cybersafetyid:bluelib:0.1.2")

    // Optional: fakes for every port, for unit tests without a device.
    testImplementation("io.github.cybersafetyid:bluelib-testing:0.1.2")
}
```

!!! note "Toolchain"
    BlueLib is compiled with AGP 9.1.1+ against `compileSdk = 37`. Your app can still keep a lower
    `compileSdk`, but the 37 toolchain is required to consume the artifact. `minSdk` may be as low as 21.

## 2. Declare the permissions

BlueLib never adds permissions to your manifest for you: an AAR cannot, and a library that silently
expands a permission set is a library you cannot ship to a privacy review. Copy what you need:

=== "Android 12+ (API 31+)"

    ```xml
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />   <!-- peripheral role -->
    <uses-permission android:name="android.permission.RANGING" />                <!-- Android 16 ranging -->
    ```

=== "Android 11 and older (API 30-)"

    ```xml
    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />
    ```

=== "Optional hardware declarations"

    ```xml
    <uses-feature android:name="android.hardware.bluetooth" android:required="true" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    <uses-feature android:name="android.hardware.bluetooth.le.channel_sounding" android:required="false" />
    ```

`neverForLocation` is a promise to Google Play that scan results are not used to derive location. If you
make it, set the same flag for BlueLib so the permission gateway stops asking for
`ACCESS_FINE_LOCATION`:

```xml
<meta-data
    android:name="io.github.cybersafetyid.bluelib.neverForLocation"
    android:value="true" />
```

## 3. Create the library

```kotlin
class MyApplication : Application() {
    lateinit var blueLib: BlueLib
        private set

    override fun onCreate() {
        super.onCreate()
        blueLib = BlueLib.create(this)
    }
}
```

`BlueLib.create` cannot fail. A missing adapter, a powered-off radio and a denied permission are
runtime facts, so they come back as typed errors from the operation that needs them:

```kotlin
when (val error = result.errorOrNull()) {
    BlueLibError.BluetoothDisabled -> requestEnable()          // launch blueLib.enableBluetoothIntent()
    is BlueLibError.PermissionMissing -> askForPermission(error.permissions, error.permanentlyDenied)
    is BlueLibError.ScanThrottled -> delay(error.retryAfterMillis)
    null -> {}
    else -> log(error.code, error.context)
}
```

## 4. Ask for the permission before the operation

```kotlin
val report = blueLib.permissionsFor(BluetoothOperation.SCAN)
if (!report.isSatisfied) {
    // `notDeclared` is a manifest bug; `missing` is a user decision.
    launcher.launch(report.missing.toTypedArray())
}
```

`report.notDeclared` never overlaps with `report.missing`, so a UI can tell "the user said no" apart from
"you forgot the manifest entry" — see [Permissions and pairing](guides/permissions-and-pairing.md).

## 5. Scan

```kotlin
lifecycleScope.launch {
    blueLib.scan(
        ScanRequest(
            serviceUuids = listOf(BluetoothUuid.HEART_RATE),
            timeoutMillis = 10_000,
        ),
    ).collect { event ->
        when (event) {
            is ScanEvent.Observed -> render(event.observation)
            is ScanEvent.Lost -> remove(event.observation.deviceId)
            is ScanEvent.Failed -> report(event.error)
        }
    }
}
```

Cancelling the collecting coroutine stops the platform scan. Nothing keeps running in the background,
and the scan quota is not spent twice — see [Scanning](guides/scanning.md).

## 6. Connect and talk GATT

```kotlin
val session = blueLib.connect(deviceId).getOrThrow()          // closes the session, not the app, on failure

session.subscribe(service, characteristic)
    .onEach { bytes -> render(bytes) }
    .launchIn(lifecycleScope)

session.write(service, characteristic, payload, WriteMode.WITH_RESPONSE)
    .onFailure { error -> snackbar("${error.code}") }
```

Sessions are reference-counted by device: connecting the same device twice returns the live session,
which is what keeps Android from opening a second `BluetoothGatt` and eventually failing every connect
with status 133. Always `close()` what you open — [GATT client](guides/gatt-client.md) explains when.

## 7. Release

```kotlin
override fun onTerminate() {
    // Closes every GATT session, stops advertising, shuts the GATT server down and cancels the scope.
    blueLib.close()
}
```

## Next

* [Architecture](architecture.md) — the ports, the modules and the reason for each split.
* [Troubleshooting](troubleshooting.md) — one section per error code.
