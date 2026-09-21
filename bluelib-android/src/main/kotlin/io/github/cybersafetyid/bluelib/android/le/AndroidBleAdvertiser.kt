package io.github.cybersafetyid.bluelib.android.le

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import io.github.cybersafetyid.bluelib.android.adapter.AndroidAdapterSource
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.android.diagnostics.AndroidDiagnostics
import io.github.cybersafetyid.bluelib.android.permission.BluetoothOperation
import io.github.cybersafetyid.bluelib.domain.BluetoothFeature
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibException
import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingHandle
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingRequest
import io.github.cybersafetyid.bluelib.domain.validation.AdvertisingPayload
import io.github.cybersafetyid.bluelib.port.BleAdvertisePort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Peripheral mode advertising.
 *
 * * **Payload validation first**: advertising data is checked against the exact byte budget
 *   (31 bytes legacy, `BluetoothAdapter.getLeMaximumAdvertisingDataLength()` for extended sets)
 *   *before* the platform call. Android's `ADVERTISE_FAILED_DATA_TOO_LARGE` is not reported by every
 *   OEM stack, so relying on it produces silent truncation.
 * * **Legacy and extended paths**: extended sets (Android 8.0+) are used when requested, otherwise
 *   legacy `startAdvertising` keeps Android 5.0–7.1 working.
 * * **Discoverable flag**: forwarded only on Android 14+, and rejected with a typed feature error
 *   below that instead of being ignored.
 * * **Cancellation safe**: a caller that cancels mid-start gets the advertising set stopped, so a
 *   cancelled coroutine cannot leave the radio advertising.
 */
// `startAdvertising` verifies the adapter state and `BLUETOOTH_ADVERTISE` through `requireReady` before
// any platform call, and every failure becomes a typed error; Lint cannot follow that gate.
@SuppressLint("MissingPermission")
public class AndroidBleAdvertiser(
    private val adapterSource: AndroidAdapterSource,
    private val diagnostics: AndroidDiagnostics,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher? = null,
) : BleAdvertisePort {

    private val activeSets = ConcurrentHashMap<Int, ActiveSet>()

    /**
     * BlueLib allocates advertising set ids itself.
     *
     * `AdvertisingSet.getAdvertisingSetId()` is not part of the SDK 37 surface (`javap` on
     * `android.jar` shows only the setters), so reading the platform id is not an option. The ids are
     * therefore local handles, unique per advertiser instance and never sent to the platform.
     */
    private val nextSetId = java.util.concurrent.atomic.AtomicInteger(1)

    private class ActiveSet(
        val handle: AdvertisingHandle,
        val callback: AdvertiseCallback?,
        val setCallback: AdvertisingSetCallback?,
        val advertisingSet: AdvertisingSet?,
    )

    override val isAdvertiserAvailable: Boolean
        get() = adapterSource.isFeatureSupported(BluetoothFeature.MULTIPLE_ADVERTISEMENT)

    override suspend fun start(request: AdvertisingRequest): AdvertisingHandle {
        val work = suspend {
            val startedAt = System.currentTimeMillis()
            diagnostics.operationStarted(OPERATION)

            val adapter = adapterSource.requireReady(BluetoothOperation.ADVERTISE)
            val readyAdapter = adapter.getOrNull() ?: throw BlueLibException(adapter.errorOrNull()!!)

            if (!isAdvertiserAvailable) {
                throw BlueLibException(
                    BlueLibError.FeatureUnsupported(
                        feature = BluetoothFeature.MULTIPLE_ADVERTISEMENT.fullName,
                        currentApiLevel = adapterSource.apiLevel,
                        requiredApiLevel = BluetoothFeature.MULTIPLE_ADVERTISEMENT.introducedInApiLevel,
                        message = "This device cannot act as an LE peripheral (no multiple advertisement support).",
                    ),
                )
            }

            val advertiser = readyAdapter.bluetoothLeAdvertiser
                ?: throw BlueLibException(
                    BlueLibError.FeatureUnsupported("LE advertising", adapterSource.apiLevel, 21),
                )

            val useExtended = request.parameters.useExtendedAdvertising
            if (useExtended && !adapterSource.isFeatureSupported(BluetoothFeature.LE_EXTENDED_ADVERTISING)) {
                throw BlueLibException(
                    BlueLibError.FeatureUnsupported(
                        BluetoothFeature.LE_EXTENDED_ADVERTISING.fullName,
                        adapterSource.apiLevel,
                        BluetoothFeature.LE_EXTENDED_ADVERTISING.introducedInApiLevel,
                    ),
                )
            }
            if (request.parameters.discoverable && !ApiLevel.isAtLeast(34)) {
                throw BlueLibException(
                    BlueLibError.FeatureUnsupported(
                        BluetoothFeature.ADVERTISING_SET_DISCOVERABLE.fullName,
                        adapterSource.apiLevel,
                        BluetoothFeature.ADVERTISING_SET_DISCOVERABLE.introducedInApiLevel,
                    ),
                )
            }

            validatePayload(request, extended = useExtended, maxExtendedBytes = maxPayloadLength())

            val handle = if (useExtended) {
                startExtended(advertiser, request)
            } else {
                startLegacy(advertiser, request)
            }

            diagnostics.operationFinished(OPERATION, startedAtMillis = startedAt, success = true)
            handle
        }

        return if (dispatcher != null) withContext(dispatcher) { work() } else work()
    }

    override suspend fun stop(handle: AdvertisingHandle) {
        val active = activeSets.remove(handle.setId) ?: return
        stopInternal(active)
    }

    override suspend fun stopAll() {
        activeSets.keys.toList().forEach { setId ->
            activeSets.remove(setId)?.let { stopInternal(it) }
        }
    }

    /** Releases every set when the owning `BlueLib` instance is closed. */
    public fun release() {
        scope.launch { stopAll() }
    }

    private fun stopInternal(active: ActiveSet) {
        val adapter = adapterSource.adapter ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        runCatching {
            when {
                active.setCallback != null && ApiLevel.isAtLeast(26) -> advertiser.stopAdvertisingSet(active.setCallback)
                active.callback != null -> advertiser.stopAdvertising(active.callback)
            }
        }.onFailure { throwable ->
            diagnostics.error(BlueLibError.Unexpected("Stopping advertising failed", throwable))
        }
    }

    private suspend fun startLegacy(advertiser: android.bluetooth.le.BluetoothLeAdvertiser, request: AdvertisingRequest): AdvertisingHandle =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    val handle = AdvertisingHandle(setId = LEGACY_SET_ID, isExtended = false)
                    activeSets[handle.setId] = ActiveSet(handle, this, null, null)
                    if (continuation.isActive) continuation.resume(handle)
                }

                override fun onStartFailure(errorCode: Int) {
                    val error = BlueLibError.AdvertiseFailed(AdvertiseMapping.failureReason(errorCode), errorCode)
                    diagnostics.error(error)
                    if (continuation.isActive) continuation.resumeWithException(BlueLibException(error))
                }
            }

            val data = AdvertiseMapping.platformData(request.advertiseData)
            val scanResponse = request.scanResponse?.let { AdvertiseMapping.platformData(it) }

            runCatching {
                advertiser.startAdvertising(AdvertiseMapping.legacySettings(request.parameters), data, scanResponse, callback)
            }.onFailure { throwable ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        BlueLibException(
                            BlueLibError.Unexpected("startAdvertising was rejected", throwable, operation = OPERATION),
                        ),
                    )
                }
            }

            continuation.invokeOnCancellation { runCatching { advertiser.stopAdvertising(callback) } }
        }

    private suspend fun startExtended(
        advertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        request: AdvertisingRequest,
    ): AdvertisingHandle {
        if (!ApiLevel.isAtLeast(26)) {
            throw BlueLibException(
                BlueLibError.FeatureUnsupported(
                    BluetoothFeature.LE_EXTENDED_ADVERTISING.fullName,
                    adapterSource.apiLevel,
                    26,
                ),
            )
        }
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                    if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        val error = BlueLibError.AdvertiseFailed(AdvertiseMapping.failureReason(status), status)
                        diagnostics.error(error)
                        if (continuation.isActive) continuation.resumeWithException(BlueLibException(error))
                        return
                    }
                    val handle = AdvertisingHandle(
                        setId = nextSetId.getAndIncrement(),
                        isExtended = true,
                        txPowerDbm = txPower,
                    )
                    activeSets[handle.setId] = ActiveSet(handle, null, this, set)
                    if (continuation.isActive) continuation.resume(handle)
                }

                override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                    // The platform does not expose the set id, so look the entry up by callback identity.
                    val entry = activeSets.entries.firstOrNull { it.value.setCallback === this } ?: return
                    activeSets.remove(entry.key)
                }

                override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
                    if (!enable && status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        diagnostics.error(BlueLibError.AdvertiseFailed("advertising set disabled", status))
                    }
                }
            }

            val parameters = AdvertiseMapping.extendedParameters(request.parameters)
            val data = AdvertiseMapping.platformData(request.advertiseData)

            runCatching {
                // Signature: (parameters, advertiseData, scanResponse, periodicParameters,
                // periodicData, duration, maxExtendedAdvertisingEvents, callback). The duration is the
                // sixth argument: the 6 argument overload instead wants periodic data there, and the
                // compiler prefers that one, so the two trailing `null`s are required.
                advertiser.startAdvertisingSet(
                    parameters,
                    data,
                    null,
                    null,
                    null,
                    request.parameters.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    0,
                    callback,
                )
            }.onFailure { throwable ->
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        BlueLibException(
                            BlueLibError.Unexpected("startAdvertisingSet was rejected", throwable, operation = OPERATION),
                        ),
                    )
                }
            }

            continuation.invokeOnCancellation {
                runCatching { advertiser.stopAdvertisingSet(callback) }
            }
        }
    }

    private fun maxPayloadLength(): Int {
        if (!ApiLevel.isAtLeast(26)) return AdvertisingPayload.LEGACY_MAX_BYTES
        val length = runCatching { adapterSource.adapter?.leMaximumAdvertisingDataLength }.getOrNull()
        return length ?: AdvertisingPayload.EXTENDED_MAX_BYTES
    }

    private fun validatePayload(request: AdvertisingRequest, extended: Boolean, maxExtendedBytes: Int) {
        val budget = if (extended) maxExtendedBytes else AdvertisingPayload.LEGACY_MAX_BYTES
        AdvertisingPayload.validate(
            data = request.advertiseData,
            section = "advertiseData",
            budget = budget,
            nameLength = request.advertiseData.let { if (it.includeDeviceName) nameLength() else null },
            includeFlags = !extended,
        )
        request.scanResponse?.let { response ->
            AdvertisingPayload.validate(
                data = response,
                section = "scanResponse",
                budget = AdvertisingPayload.LEGACY_MAX_BYTES,
                nameLength = if (response.includeDeviceName) nameLength() else null,
                includeFlags = false,
            )
        }
    }

    private fun nameLength(): Int {
        val name = runCatching { adapterSource.adapter?.name }.getOrNull() ?: return 0
        if (name.isEmpty()) {
            throw BlueLibValidationException.ValueOutOfRange(
                parameter = "deviceName.length",
                value = 0,
                allowed = 1L..(AdvertisingPayload.LEGACY_MAX_BYTES - 2).toLong(),
            )
        }
        return name.toByteArray(Charsets.UTF_8).size
    }

    private companion object {
        /** Legacy advertising has no set id in the platform API. */
        const val LEGACY_SET_ID = 0

        const val OPERATION = "advertise"
    }
}
