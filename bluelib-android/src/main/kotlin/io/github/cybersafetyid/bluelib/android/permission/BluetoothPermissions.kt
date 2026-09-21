package io.github.cybersafetyid.bluelib.android.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.port.PermissionPort
import io.github.cybersafetyid.bluelib.port.PermissionStatus

/** Operations that may need permissions, used to build precise error messages. */
public enum class BluetoothOperation {
    /** Scanning for BLE devices. */
    SCAN,

    /** Scanning with the results delivered to a `PendingIntent` (background scans). */
    BACKGROUND_SCAN,

    /** Opening GATT connections, reading and writing. */
    CONNECT,

    /** Classic discovery and bonding. */
    CLASSIC_DISCOVERY,

    /** Advertising (peripheral role) and Classic discoverability. */
    ADVERTISE,

    /**
     * Hosting a local GATT server. It shares the permission requirements of [CONNECT], because
     * `BLUETOOTH_CONNECT` also guards the server role on Android 12+.
     */
    GATT_SERVER,

    /** Ranging (Bluetooth Channel Sounding / RSSI) through the Ranging module. */
    RANGING,
    ;

    /** Name used in errors and documentation. */
    public val operationName: String
        get() = name.lowercase().replace('_', ' ')
}

/**
 * The permission matrix BlueLib applies before touching the platform.
 *
 * Android's rules are genuinely different per version, and getting this wrong produces either a
 * `SecurityException` crash or a scan that silently returns nothing:
 *
 * | Operation | Android 5–11 | Android 12+ |
 * |---|---|---|
 * | Scan | `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` | `BLUETOOTH_SCAN` (+ `ACCESS_FINE_LOCATION` unless `neverForLocation`) |
 * | Connect | `BLUETOOTH`, `BLUETOOTH_ADMIN` | `BLUETOOTH_CONNECT` |
 * | Advertise | `BLUETOOTH`, `BLUETOOTH_ADMIN` | `BLUETOOTH_ADVERTISE` |
 * | Ranging (Android 16+) | — | `RANGING` |
 */
public object BluetoothPermissions {

    /** Permissions required for [operation] on the running API level. */
    public fun requiredFor(operation: BluetoothOperation): List<String> = when (operation) {
        BluetoothOperation.SCAN -> if (ApiLevel.hasSplitBluetoothPermissions()) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

        BluetoothOperation.BACKGROUND_SCAN -> buildList {
            addAll(requiredFor(BluetoothOperation.SCAN))
            if (!ApiLevel.hasSplitBluetoothPermissions() && ApiLevel.isAtLeast(29)) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        BluetoothOperation.CONNECT, BluetoothOperation.GATT_SERVER -> if (ApiLevel.hasSplitBluetoothPermissions()) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

        BluetoothOperation.CLASSIC_DISCOVERY -> requiredFor(BluetoothOperation.CONNECT) + if (
            !ApiLevel.hasSplitBluetoothPermissions()
        ) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            emptyList()
        }

        BluetoothOperation.ADVERTISE -> if (ApiLevel.hasSplitBluetoothPermissions()) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }

        BluetoothOperation.RANGING -> if (ApiLevel.isAtLeast(36)) {
            listOf(Manifest.permission.RANGING)
        } else {
            emptyList()
        }
    }

    /**
     * Whether the `neverForLocation` assertion is present in the merged manifest for
     * `BLUETOOTH_SCAN`. Apps that assert it do not need `ACCESS_FINE_LOCATION` on Android 12+.
     *
     * The flag lives in the manifest rather than at runtime, so BlueLib reads it from the
     * application info flags.
     */
    public fun assertsNeverForLocation(context: Context): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        // Ranging and some OEM builds place the assertion in the manifest meta-data section.
        info.metaData?.getBoolean(KEY_NEVER_FOR_LOCATION) == true
    }.getOrDefault(false)

    /** Manifest meta-data key a host app can set to tell BlueLib that `neverForLocation` is used. */
    public const val KEY_NEVER_FOR_LOCATION: String = "io.github.cybersafetyid.bluelib.neverForLocation"
}

/**
 * [PermissionPort] backed by the platform, without pulling in AndroidX `core` as a dependency.
 *
 * `Context.checkSelfPermission` only exists from Android 6.0 (API 23), so older devices fall back to
 * `PackageManager.checkPermission`, which is exactly equivalent on those versions because every
 * Bluetooth permission is install-time there.
 */
public class AndroidPermissionPort(private val context: Context) : PermissionPort {

    override fun statusOf(permission: String): PermissionStatus {
        if (isGranted(permission)) return PermissionStatus.GRANTED
        if (!declaredInManifest(permission)) return PermissionStatus.NOT_APPLICABLE
        return PermissionStatus.DENIED
    }

    private fun isGranted(permission: String): Boolean = if (ApiLevel.supportsRuntimePermissions()) {
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } else {
        context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
    }

    private fun declaredInManifest(permission: String): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        info.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)

}

/**
 * Result of checking every permission an operation needs.
 *
 * @property missing permissions that are not granted.
 * @property notDeclared permissions the app never declared, which is a manifest bug rather than a
 *   user decision: telling the user to enable such a permission only wastes their time.
 */
public data class PermissionReport(
    public val operation: BluetoothOperation,
    public val missing: List<String>,
    public val notDeclared: List<String>,
) {
    /**
     * `true` when the operation may proceed.
     *
     * A permission the app never declared counts as missing: `checkSelfPermission` reports it as denied
     * on every Android release, so treating it as granted would let the operation run until the platform
     * throws `SecurityException` — the exact failure the gateway exists to prevent.
     */
    public val isSatisfied: Boolean
        get() = missing.isEmpty() && notDeclared.isEmpty()
}

/** Checks permissions for an operation and converts failures into typed errors. */
public class PermissionGateway(
    private val port: PermissionPort,
    private val requiredFor: (BluetoothOperation) -> List<String> = BluetoothPermissions::requiredFor,
) {

    /** Full report for [operation]. */
    public fun report(operation: BluetoothOperation): PermissionReport {
        val missing = mutableListOf<String>()
        val notDeclared = mutableListOf<String>()
        requiredFor(operation).forEach { permission ->
            when (port.statusOf(permission)) {
                PermissionStatus.GRANTED -> Unit
                PermissionStatus.NOT_APPLICABLE -> notDeclared += permission
                PermissionStatus.DENIED, PermissionStatus.DENIED_PERMANENTLY -> missing += permission
            }
        }
        return PermissionReport(operation, missing, notDeclared)
    }

    /** `true` when every permission required for [operation] is granted. */
    public fun hasPermissionFor(operation: BluetoothOperation): Boolean = report(operation).isSatisfied

    /**
     * Verifies [operation] can run, returning a typed failure instead of letting the platform throw
     * `SecurityException` *after* partial work has happened.
     */
    public fun requireOrFailure(operation: BluetoothOperation): BlueLibResult<Unit> {
        val report = report(operation)
        if (report.isSatisfied) return successOf(Unit)

        return if (report.notDeclared.isNotEmpty() && report.missing.isEmpty()) {
            // The app never declared the permission: that is a manifest error, not a user decision.
            failureOf(
                BlueLibError.OperationRejected(
                    reason = "the app does not declare ${report.notDeclared.joinToString()}",
                    hint = "Add the permission to AndroidManifest.xml; see docs/guides/permissions-and-pairing.md",
                ),
            )
        } else {
            failureOf(
                BlueLibError.PermissionMissing(
                    operation = operation.operationName,
                    permissions = report.missing,
                    permanentlyDenied = report.missing.any {
                        port.statusOf(it) == PermissionStatus.DENIED_PERMANENTLY
                    },
                ),
            )
        }
    }
}
