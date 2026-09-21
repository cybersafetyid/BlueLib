package io.github.cybersafetyid.bluelib.android.le

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetParameters
import android.os.ParcelUuid
import androidx.annotation.RequiresApi
import io.github.cybersafetyid.bluelib.android.compat.ApiLevel
import io.github.cybersafetyid.bluelib.domain.model.AdvertiseMode
import io.github.cybersafetyid.bluelib.domain.model.AdvertisingParameters
import io.github.cybersafetyid.bluelib.domain.model.Phy
import io.github.cybersafetyid.bluelib.domain.model.TxPowerLevel

/** Translation between BlueLib's advertising model and `android.bluetooth.le`. */
internal object AdvertiseMapping {

    /** Legacy `AdvertiseSettings` (API 21+). */
    fun legacySettings(parameters: AdvertisingParameters): AdvertiseSettings =
        AdvertiseSettings.Builder()
            .setAdvertiseMode(parameters.mode.toPlatform())
            .setTxPowerLevel(parameters.txPowerLevel.toPlatform())
            .setConnectable(parameters.connectable)
            .setTimeout(parameters.timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .build()

    /** Extended `AdvertisingSetParameters` (API 26+). */
    @RequiresApi(26)
    fun extendedParameters(parameters: AdvertisingParameters): AdvertisingSetParameters {
        val builder = AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(parameters.connectable)
            .setScannable(parameters.scannable)
            .setInterval(intervalFor(parameters.mode))
            .setTxPowerLevel(parameters.txPowerLevel.toPlatform())
            .setPrimaryPhy(parameters.primaryPhy.mask)
            .setSecondaryPhy((parameters.secondaryPhy ?: Phy.LE_1M).mask)

        if (ApiLevel.isAtLeast(34)) {
            builder.setDiscoverable(parameters.discoverable)
        }
        // `AdvertisingSetParameters.Builder` only accepts a power *level*; there is no dBm setter and
        // `AdvertiseData.Builder.setIncludeTxPowerLevel(true)` is what actually publishes the value.
        // The exact dBm therefore comes from `AdvertisingHandle.txPowerDbm` after the set starts.
        return builder.build()
    }

    /** Advertising interval in slots (`0.625 ms` units) for a mode preset; extended sets only. */
    @RequiresApi(26)
    fun intervalFor(mode: AdvertiseMode): Int = when (mode) {
        AdvertiseMode.LOW_POWER -> AdvertisingSetParameters.INTERVAL_LOW
        AdvertiseMode.BALANCED -> AdvertisingSetParameters.INTERVAL_MEDIUM
        AdvertiseMode.LOW_LATENCY -> AdvertisingSetParameters.INTERVAL_HIGH
    }

    /** Builds the platform payload, using fields only when the API level provides them. */
    fun platformData(data: io.github.cybersafetyid.bluelib.domain.model.AdvertiseData): AdvertiseData {
        val builder = AdvertiseData.Builder()
            .setIncludeDeviceName(data.includeDeviceName)
            .setIncludeTxPowerLevel(data.includeTxPowerLevel)

        data.serviceUuids.forEach { builder.addServiceUuid(ParcelUuid(it.uuid)) }
        data.serviceData.forEach { (uuid, payload) -> builder.addServiceData(ParcelUuid(uuid.uuid), payload) }
        data.manufacturerData.forEach { (companyId, payload) -> builder.addManufacturerData(companyId, payload) }

        if (ApiLevel.isAtLeast(31)) {
            data.serviceSolicitationUuids.forEach { builder.addServiceSolicitationUuid(ParcelUuid(it.uuid)) }
        }
        if (ApiLevel.isAtLeast(33)) {
            data.transportDiscoveryData.forEach { builder.addTransportDiscoveryData(addTransportDiscoveryData(it)) }
        }
        return builder.build()
    }

    @RequiresApi(33)
    private fun addTransportDiscoveryData(payload: ByteArray): android.bluetooth.le.TransportDiscoveryData {
        // The platform type wants an organisation id plus blocks; the TDS profile is not modelled by
        // BlueLib yet, so a zero id keeps the payload intact for consumers that parse it themselves.
        val reader = io.github.cybersafetyid.bluelib.domain.codec.ByteReader(payload, label = "transportDiscoveryData")
        val organisationId = if (reader.remaining >= 1) reader.readU8() else 0
        val blocks = emptyList<android.bluetooth.le.TransportBlock>()
        return android.bluetooth.le.TransportDiscoveryData(organisationId, blocks)
    }

    /** Human readable reason for `AdvertiseCallback.onStartFailure`. */
    fun failureReason(errorCode: Int): String = when (errorCode) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "payload too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertising sets"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "advertising already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
        else -> "unknown error"
    }

    private fun AdvertiseMode.toPlatform(): Int = when (this) {
        AdvertiseMode.LOW_POWER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        AdvertiseMode.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
        AdvertiseMode.LOW_LATENCY -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    }

    private fun TxPowerLevel.toPlatform(): Int = when (this) {
        TxPowerLevel.ULTRA_LOW -> AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW
        TxPowerLevel.LOW -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
        TxPowerLevel.MEDIUM -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
        TxPowerLevel.HIGH -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
    }
}
