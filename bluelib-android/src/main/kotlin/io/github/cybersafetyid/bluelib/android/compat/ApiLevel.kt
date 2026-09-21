package io.github.cybersafetyid.bluelib.android.compat

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * The **only** place in BlueLib where the Android version is compared.
 *
 * Two rules follow from the compatibility promise (Android 5.0 → Android 17):
 *
 * 1. Android now ships *minor* releases that add APIs inside the same major API level (Android 16.1
 *    is API `36.1`). Since Android 16 those are read through `Build.getMinorSdkVersion()`, so
 *    comparing `SDK_INT` alone is no longer enough — see the two argument [isAtLeast].
 * 2. Lint must be able to *prove* that every platform call is guarded. Both overloads are annotated
 *    with `@ChecksSdkIntAtLeast`, so `if (ApiLevel.isAtLeast(31)) { … }` protects the body and the
 *    `NewApi` check stays meaningful instead of being suppressed.
 */
public object ApiLevel {

    /** Running major API level, e.g. `21` on Lollipop, `37` on Android 17. */
    public val current: Int
        get() = Build.VERSION.SDK_INT

    /** Running minor API level, e.g. `1` on Android 16.1, `0` on Android 16.0 and older platforms. */
    public val currentMinor: Int
        get() = if (Build.VERSION.SDK_INT >= 36) minorOf(36) else 0

    /** `true` when the device runs at least major API level [apiLevel]. */
    @ChecksSdkIntAtLeast(parameter = 0)
    public fun isAtLeast(apiLevel: Int): Boolean = Build.VERSION.SDK_INT >= apiLevel

    /**
     * `true` when the device runs at least [apiLevel] including the minor release [minorApiLevel].
     *
     * `isAtLeast(36, 1)` is `true` on Android 16.1 and `false` on Android 16.0.
     */
    @ChecksSdkIntAtLeast(parameter = 0)
    public fun isAtLeast(apiLevel: Int, minorApiLevel: Int): Boolean {
        val current = Build.VERSION.SDK_INT
        if (current != apiLevel) return current > apiLevel
        if (minorApiLevel == 0) return true
        return minorOf(apiLevel) >= minorApiLevel
    }

    /**
     * Minor version of [majorApiLevel] as reported by the platform, or `0` when the platform cannot
     * report minor versions (Android 15 and older).
     */
    public fun minorOf(majorApiLevel: Int): Int {
        if (Build.VERSION.SDK_INT < 36) return 0
        return runCatching { Build.getMinorSdkVersion(majorApiLevel) }.getOrDefault(0)
    }

    /** Human readable version for diagnostics, e.g. `API 36.1`. */
    public fun describe(): String {
        val minor = currentMinor
        return if (minor == 0) "API $current" else "API $current.$minor"
    }

    /** `true` on Android 12 and newer, where the `BLUETOOTH_*` runtime permissions exist. */
    @ChecksSdkIntAtLeast(api = 31)
    public fun hasSplitBluetoothPermissions(): Boolean = isAtLeast(31)

    /** `true` on Android 13 and newer, where runtime receivers need an explicit export flag. */
    @ChecksSdkIntAtLeast(api = 33)
    public fun requiresReceiverExportFlag(): Boolean = isAtLeast(33)

    /** `true` on Android 6.0 (API 23) and newer, where permissions are granted at runtime. */
    @ChecksSdkIntAtLeast(api = 23)
    public fun supportsRuntimePermissions(): Boolean = isAtLeast(23)
}
