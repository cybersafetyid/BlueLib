package io.github.cybersafetyid.bluelib

import android.content.Context
import android.content.Intent
import io.github.cybersafetyid.bluelib.BlueLib.Companion.create
import io.github.cybersafetyid.bluelib.android.adapter.AndroidAdapterSource
import io.github.cybersafetyid.bluelib.android.classic.AndroidClassicPort
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.android.gatt.AndroidGattClient
import io.github.cybersafetyid.bluelib.android.gatt.AndroidGattServerHost
import io.github.cybersafetyid.bluelib.android.le.AndroidBleAdvertiser
import io.github.cybersafetyid.bluelib.android.le.AndroidBleScanner
import io.github.cybersafetyid.bluelib.android.permission.AndroidPermissionPort
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.android.permission.PermissionGateway
import io.github.cybersafetyid.bluelib.android.permission.PermissionReport
import io.github.cybersafetyid.bluelib.android.platform.PlatformDispatchers
import io.github.cybersafetyid.bluelib.domain.BluetoothFeature
import io.github.cybersafetyid.bluelib.domain.Capability
import io.github.cybersafetyid.bluelib.domain.codec.MessageFramer
import io.github.cybersafetyid.bluelib.domain.codec.RawFramer
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.messenger.BluetoothMessenger
import io.github.cybersafetyid.bluelib.domain.messenger.ClassicMessenger
import io.github.cybersafetyid.bluelib.domain.messenger.GattMessenger
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingHandle
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingRequest
import io.github.cybersafetyid.bluelib.domain.model.AutoPairFilter
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid
import io.github.cybersafetyid.bluelib.domain.model.GattServerConfig
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.domain.model.WriteMode
import io.github.cybersafetyid.bluelib.domain.policy.AutoPairEngine
import io.github.cybersafetyid.bluelib.domain.policy.ScanQuotaGovernor
import io.github.cybersafetyid.bluelib.port.ClassicConnection
import io.github.cybersafetyid.bluelib.port.ClassicDevice
import io.github.cybersafetyid.bluelib.port.ClassicDiscoveryEvent
import io.github.cybersafetyid.bluelib.port.ClassicPort
import io.github.cybersafetyid.bluelib.port.DiagnosticEvent
import io.github.cybersafetyid.bluelib.port.GattConnectRequest
import io.github.cybersafetyid.bluelib.port.GattServer
import io.github.cybersafetyid.bluelib.port.GattSession
import io.github.cybersafetyid.bluelib.port.ScanEvent
import io.github.cybersafetyid.bluelib.port.SocketSettings
import io.github.cybersafetyid.bluelib.port.SystemClockPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * The one object an application needs.
 *
 * `BlueLib` wires the domain layer to the Android platform adapters and exposes a stable surface:
 *
 * ```kotlin
 * val blueLib = BlueLib.create(context)
 *
 * blueLib.scan(ScanRequest(timeoutMillis = 10_000))
 *     .filterIsInstance<ScanEvent.Observed>()
 *     .collect { println(it.observation.deviceId) }
 * ```
 *
 * Design rules the facade keeps:
 *
 * * **Nothing throws at construction.** A missing adapter, a disabled radio and a denied permission are
 *   *runtime facts*, not programming errors, so they are reported as typed failures from the operation
 *   that needs them. Construction only wires objects, which is why [create] cannot fail.
 * * **One instance per process is enough, but not required.** Each instance owns a [CoroutineScope]
 *   that [close] cancels, so create it where its lifetime makes sense (usually an `Application`).
 * * **Everything is observable.** [diagnostics] carries the same events the library logs, including the
 *   platform status codes, which is what makes OEM specific bugs diagnosable.
 */
public class BlueLib private constructor(
    /** Runtime configuration; see [BlueLibConfig] for what can be tuned. */
    public val config: BlueLibConfig,
    private val adapterSource: AndroidAdapterSource,
    private val permissionGateway: PermissionGateway,
    private val androidDiagnostics: AndroidDiagnostics,
    private val scanner: AndroidBleScanner,
    private val advertiser: AndroidBleAdvertiser,
    private val gattClient: AndroidGattClient,
    private val gattServerHost: AndroidGattServerHost,
    private val classic: AndroidClassicPort,
    private val platformDispatchers: PlatformDispatchers,
    private val scope: CoroutineScope,
) : AutoCloseable {

    /** All diagnostic events: platform operations, state changes and typed errors. */
    public val diagnostics: Flow<DiagnosticEvent> get() = androidDiagnostics.events

    /** Adapter state as a hot stream, backed by `ACTION_STATE_CHANGED`. */
    public val adapterState: StateFlow<String> get() = adapterSource.adapterState

    /** `true` when this device has a Bluetooth adapter at all. */
    public val hasAdapter: Boolean get() = adapterSource.isAdapterAvailable

    /** GATT connections currently open through this instance. */
    public val openConnections: List<BluetoothDeviceId> get() = gattClient.openSessions

    /** Bluetooth Classic: discovery, bonding, RFCOMM and L2CAP. */
    public val classicPort: ClassicPort get() = classic

    /** `true` while an LE scan is running for this instance. */
    public val isScanning: Boolean get() = scanner.isScanning

    /** The runtime capability report for this device, captured when the instance was created. */
    public val capabilities: PlatformReport = PlatformReport.capture(adapterSource)

    // --- Scanning ------------------------------------------------------------------------------

    /** Starts an LE scan; the flow stops the platform scan when the collector cancels. */
    public fun scan(request: ScanRequest = ScanRequest()): Flow<ScanEvent> = scanner.scan(request)

    /**
     * Marks the scanner as idle.
     *
     * Cancelling the collector of [scan] already stops the platform scan, which is the recommended
     * way to stop; this only clears the bookkeeping so a later `scan()` is not rejected as "already
     * active" when the previous collector was abandoned without cancellation.
     */
    public fun releaseScanner() {
        scanner.release()
    }

    // --- Advertising ---------------------------------------------------------------------------

    /** `true` when this device can act as an LE peripheral. */
    public val isAdvertiserAvailable: Boolean get() = advertiser.isAdvertiserAvailable

    /**
     * Starts advertising and returns the handle of the advertising set.
     *
     * @throws io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException when the payload
     *   exceeds the byte budget of the selected advertising mode, which Android would silently accept
     *   and truncate.
     */
    public suspend fun startAdvertising(request: AdvertisingRequest): AdvertisingHandle = advertiser.start(request)

    /** Stops the advertising set behind [handle]. */
    public suspend fun stopAdvertising(handle: AdvertisingHandle) {
        advertiser.stop(handle)
    }

    // --- GATT client ---------------------------------------------------------------------------

    /**
     * Opens (or reuses) a GATT connection.
     *
     * Connecting a device that already has a live session returns that session instead of opening a
     * second `BluetoothGatt`, which is the main cause of status 133 on Android.
     */
    public suspend fun connect(
        deviceId: BluetoothDeviceId,
        request: GattConnectRequest = GattConnectRequest(mtu = config.defaultMtu, timeoutMillis = config.connectTimeoutMillis),
    ): BlueLibResult<GattSession> = gattClient.connect(deviceId, request)

    // --- GATT server ---------------------------------------------------------------------------

    /** Opens a local GATT server exposing [config]. */
    public suspend fun openGattServer(config: GattServerConfig): BlueLibResult<GattServer> =
        gattServerHost.open(config)

    /** `true` while a GATT server is open for this instance. */
    public val isGattServerOpen: Boolean get() = gattServerHost.isOpen

    // --- Classic -------------------------------------------------------------------------------

    /** Starts Classic discovery; the flow cancels the platform discovery when the collector cancels. */
    public fun discoverClassic(includeRssi: Boolean = true): Flow<ClassicDiscoveryEvent> =
        classic.discover(includeRssi)

    /** Devices currently bonded to this phone. */
    public fun bondedDevices(): Flow<List<ClassicDevice>> = classic.bondedDevices()

    /** Creates a bond, waiting for the platform's confirmation dialog. */
    public suspend fun bond(
        deviceId: BluetoothDeviceId,
        transport: Transport = Transport.BREDR,
    ): BlueLibResult<Unit> = classic.bond(deviceId, transport, config.bondTimeoutMillis)

    /**
     * Discovers a device matching [filter] and creates a bond with it automatically.
     *
     * If the target device is already bonded, returns immediately with success.
     */
    public suspend fun autoPair(
        filter: AutoPairFilter,
        timeoutMillis: Long = config.bondTimeoutMillis,
    ): BlueLibResult<BluetoothDeviceId> = AutoPairEngine.autoPair(
        filter = filter,
        scanPort = scanner,
        classicPort = classic,
        timeoutMillis = timeoutMillis,
    )

    /** Opens an RFCOMM socket. */
    public suspend fun connectRfcomm(
        deviceId: BluetoothDeviceId,
        serviceUuid: BluetoothUuid,
        settings: SocketSettings = SocketSettings(),
    ): BlueLibResult<ClassicConnection> = classic.connectRfcomm(deviceId, serviceUuid, settings)

    /** Opens an L2CAP channel (Android 10+). */
    public suspend fun connectL2cap(
        deviceId: BluetoothDeviceId,
        psm: Int,
        settings: SocketSettings = SocketSettings(),
    ): BlueLibResult<ClassicConnection> = classic.connectL2cap(deviceId, psm, settings)

    // --- Messaging -----------------------------------------------------------------------------

    /** Creates a [BluetoothMessenger] wrapping a BLE GATT characteristic on [session]. */
    public fun createGattMessenger(
        session: GattSession,
        serviceUuid: BluetoothUuid,
        characteristicUuid: BluetoothUuid,
        writeMode: WriteMode = WriteMode.WITH_RESPONSE,
        framer: MessageFramer = RawFramer,
    ): BluetoothMessenger = GattMessenger(
        session = session,
        serviceUuid = serviceUuid,
        characteristicUuid = characteristicUuid,
        writeMode = writeMode,
        framer = framer,
    )

    /** Creates a [BluetoothMessenger] wrapping a Classic socket [connection]. */
    public fun createClassicMessenger(
        connection: ClassicConnection,
        framer: MessageFramer = RawFramer,
    ): BluetoothMessenger = ClassicMessenger(
        connection = connection,
        framer = framer,
    )

    // --- Permissions ---------------------------------------------------------------------------

    /** Full permission report for [operation], including permissions the app never declared. */
    public fun permissionsFor(operation: BluetoothOperation): PermissionReport = permissionGateway.report(operation)

    /** `true` when every permission [operation] needs is granted. */
    public fun hasPermissionFor(operation: BluetoothOperation): Boolean =
        permissionGateway.hasPermissionFor(operation)

    /** Intent that asks the user to turn Bluetooth on; launch it with `ActivityResultLauncher`. */
    public fun enableBluetoothIntent(): Intent = adapterSource.enableRequestIntent()

    // --- Lifecycle -----------------------------------------------------------------------------

    /**
     * Releases every resource this instance owns: GATT connections, advertising sets, the GATT server,
     * the receivers and the coroutine scope.
     *
     * Leaking a `BluetoothGatt` leaks a controller connection slot and eventually makes *every* later
     * connect fail, so this is not optional cleanup — call it when the owning component is destroyed.
     */
    override fun close() {
        runCatching { runBlocking { gattClient.closeAll() } }
        runCatching { advertiser.release() }
        runCatching { classic.stop() }
        runCatching { adapterSource.stop() }
        scope.cancel()
        // The single platform thread must go last: every adapter above may still be mid-call on it.
        runCatching { platformDispatchers.close() }
    }

    public companion object {
        /**
         * Creates a `BlueLib` instance.
         *
         * Never fails: adapter presence, radio state and permissions are checked per operation, so an
         * app can create the instance during `Application.onCreate` and react to typed errors later.
         */
        public fun create(
            context: Context,
            config: BlueLibConfig = BlueLibConfig(),
        ): BlueLib {
            val applicationContext = context.applicationContext ?: context
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val clock = SystemClockPort
            val diagnostics = AndroidDiagnostics(clock, enabled = config.diagnosticsEnabled)

            val permissions = PermissionGateway(AndroidPermissionPort(applicationContext))
            val adapterSource = AndroidAdapterSource(applicationContext, diagnostics, permissions)
            adapterSource.start()

            val platformDispatchers = PlatformDispatchers()
            val scanner = AndroidBleScanner(
                adapterSource = adapterSource,
                diagnostics = diagnostics,
                clock = clock,
                quota = ScanQuotaGovernor(
                    clock = clock,
                    maxStartsPerWindow = config.scanStartsPerWindow,
                    windowMillis = config.scanQuotaWindowMillis,
                ),
                dispatcher = platformDispatchers.bluetooth,
            )
            val advertiser = AndroidBleAdvertiser(
                adapterSource = adapterSource,
                diagnostics = diagnostics,
                scope = scope,
                dispatcher = platformDispatchers.bluetooth,
            )
            val gattClient = AndroidGattClient(
                context = applicationContext,
                adapterSource = adapterSource,
                diagnostics = diagnostics,
                clock = clock,
                sessionScope = scope,
                config = config,
                dispatcher = platformDispatchers.bluetooth,
            )
            val gattServerHost = AndroidGattServerHost(
                context = applicationContext,
                adapterSource = adapterSource,
                permissionGateway = permissions,
                diagnostics = diagnostics,
            )
            val classic = AndroidClassicPort(
                context = applicationContext,
                adapterSource = adapterSource,
                diagnostics = diagnostics,
                clock = clock,
                scope = scope,
                dispatcher = platformDispatchers.bluetooth,
            )
            classic.start()

            // The capability snapshot is what makes an OEM bug report actionable, so it is recorded
            // as diagnostics rather than being available only through the public report.
            BluetoothFeature.entries.forEach { feature ->
                diagnostics.emit(
                    DiagnosticEvent.CapabilityProbed(
                        feature = feature,
                        supported = adapterSource.isFeatureSupported(feature),
                        apiLevel = ApiLevel.current,
                        timestampMillis = clock.nowMillis(),
                    ),
                )
            }

            return BlueLib(
                config = config,
                adapterSource = adapterSource,
                permissionGateway = permissions,
                androidDiagnostics = diagnostics,
                scanner = scanner,
                advertiser = advertiser,
                gattClient = gattClient,
                gattServerHost = gattServerHost,
                classic = classic,
                platformDispatchers = platformDispatchers,
                scope = scope,
            )
        }

        /** Name and version of the library, useful in bug reports. */
        public val version: String get() = "${BlueLibVersion.LIBRARY_NAME} ${BlueLibVersion.VERSION}"
    }
}

/**
 * What this device and this Android release can actually do.
 *
 * The report exists because "is this feature supported?" has three different answers on Android: the
 * API level may be too old, the hardware may lack the feature, or the app may be missing the
 * permission. [unsupportedReason] tells them apart so a UI can explain rather than fail silently.
 */
public data class PlatformReport(
    /** Running major API level, e.g. `37` for Android 17. */
    public val apiLevel: Int,
    /** Running minor API level, e.g. `1` for Android 16.1; `0` when the platform cannot report it. */
    public val minorApiLevel: Int,
    /** Human readable version, e.g. `API 36.1`. */
    public val apiLevelDescription: String,
    /** Whether this device exposes a Bluetooth adapter. */
    public val hasAdapter: Boolean,
    /** Every feature with its availability on this device. */
    public val capabilities: List<Capability>,
) {
    /** `true` when [feature] is usable right now (platform and hardware). */
    public fun supports(feature: BluetoothFeature): Boolean =
        capabilities.firstOrNull { it.feature == feature }?.supported == true

    /** Features this device supports, in declaration order. */
    public val supportedFeatures: List<BluetoothFeature>
        get() = capabilities.filter { it.supported }.map { it.feature }

    /** Features the platform has but the hardware lacks — the usual OEM surprise. */
    public val platformWithoutHardware: List<BluetoothFeature>
        get() = capabilities.filter { it.availableInPlatform && !it.supported }.map { it.feature }

    /** Why [feature] is unavailable, or `null` when it is available. */
    public fun unsupportedReason(feature: BluetoothFeature): String? = when {
        !hasAdapter -> "This device has no Bluetooth adapter."
        supports(feature) -> null
        !capabilities.first { it.feature == feature }.availableInPlatform ->
            "Needs Android ${feature.introducedInApiLevel}" +
                (if (feature.introducedInMinorApiLevel == 0) "" else ".${feature.introducedInMinorApiLevel}") +
                "; this device runs $apiLevelDescription."
        else -> "${feature.fullName} exists in this Android release but the hardware does not support it."
    }

    public companion object {
        internal fun capture(adapterSource: AndroidAdapterSource): PlatformReport = PlatformReport(
            apiLevel = adapterSource.apiLevel,
            minorApiLevel = adapterSource.minorApiLevel,
            apiLevelDescription = ApiLevel.describe(),
            hasAdapter = adapterSource.isAdapterAvailable,
            capabilities = BluetoothFeature.entries.map { feature ->
                Capability(
                    feature = feature,
                    supported = adapterSource.isAdapterAvailable && adapterSource.isFeatureSupported(feature),
                    currentApiLevel = adapterSource.apiLevel,
                )
            },
        )
    }
}
