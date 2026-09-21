package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.error.failureOf
import io.github.cybersafetyid.bluelib.domain.error.successOf
import io.github.cybersafetyid.bluelib.domain.model.AutoPairFilter
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.BondState
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.model.Transport
import io.github.cybersafetyid.bluelib.port.BleScanPort
import io.github.cybersafetyid.bluelib.port.ClassicDiscoveryEvent
import io.github.cybersafetyid.bluelib.port.ClassicPort
import io.github.cybersafetyid.bluelib.port.ScanEvent
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Engine executing the auto-pairing workflow for a given [AutoPairFilter].
 *
 * Flow:
 * 1. Scans for devices matching [filter] using LE scan (or Classic discovery if [Transport.BREDR]).
 * 2. As soon as a matching device is discovered, checks its current [BondState].
 * 3. If already bonded, returns [successOf].
 * 4. Otherwise, initiates bonding via [ClassicPort.bond] with the requested transport and timeout.
 */
public object AutoPairEngine {

    public suspend fun autoPair(
        filter: AutoPairFilter,
        scanPort: BleScanPort,
        classicPort: ClassicPort,
        timeoutMillis: Long = 30_000L,
    ): BlueLibResult<BluetoothDeviceId> {
        runCatching { filter.validate() }.onFailure { error ->
            return failureOf(
                if (error is BlueLibValidationException) {
                    BlueLibError.OperationRejected(
                        reason = error.message ?: "Invalid AutoPairFilter",
                        hint = error.toString(),
                    )
                } else {
                    BlueLibError.Unexpected("AutoPairFilter validation failed", error)
                }
            )
        }

        val matchingDeviceId: BluetoothDeviceId? = if (filter.transport == Transport.BREDR) {
            findClassicDevice(filter, classicPort, timeoutMillis)
        } else {
            findBleDevice(filter, scanPort, timeoutMillis)
        }

        if (matchingDeviceId == null) {
            return failureOf(
                BlueLibError.Timeout(
                    operation = "autoPair",
                    timeoutMillis = timeoutMillis,
                )
            )
        }

        // Check current bond state
        val currentBondState = classicPort.bondState(matchingDeviceId).firstOrNull() ?: BondState.NONE
        if (currentBondState == BondState.BONDED) {
            return successOf(matchingDeviceId)
        }

        // Execute bonding
        val bondResult = classicPort.bond(
            deviceId = matchingDeviceId,
            transport = filter.transport,
            timeoutMillis = timeoutMillis,
        )

        return when (bondResult) {
            is BlueLibResult.Success -> successOf(matchingDeviceId)
            is BlueLibResult.Failure -> failureOf(bondResult.error)
        }
    }

    private suspend fun findBleDevice(
        filter: AutoPairFilter,
        scanPort: BleScanPort,
        timeoutMillis: Long,
    ): BluetoothDeviceId? {
        val scanRequest = ScanRequest(
            serviceUuids = filter.serviceUuids,
            deviceName = filter.deviceName,
            namePrefix = filter.namePrefix,
            manufacturerId = filter.manufacturerId,
            autoStopAfterMillis = timeoutMillis.coerceIn(
                ScanRequest.MIN_AUTO_STOP_MILLIS,
                ScanRequest.MAX_AUTO_STOP_MILLIS,
            ),
        )

        return withTimeoutOrNull(timeoutMillis) {
            scanPort.scan(scanRequest)
                .filterIsInstance<ScanEvent.Observed>()
                .firstOrNull { event -> filter.matches(event.observation) }
                ?.observation?.deviceId
        }
    }

    private suspend fun findClassicDevice(
        filter: AutoPairFilter,
        classicPort: ClassicPort,
        timeoutMillis: Long,
    ): BluetoothDeviceId? {
        return withTimeoutOrNull(timeoutMillis) {
            classicPort.discover(includeRssi = filter.minRssi != null)
                .filterIsInstance<ClassicDiscoveryEvent.DeviceFound>()
                .firstOrNull { event -> filter.matches(event.device, event.rssi) }
                ?.device?.deviceId
        }
    }
}
