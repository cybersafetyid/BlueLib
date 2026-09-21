package io.github.cybersafetyid.bluelib.domain.error

import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import io.github.cybersafetyid.bluelib.domain.model.ScanMode
import io.github.cybersafetyid.bluelib.domain.model.ScanRequest
import io.github.cybersafetyid.bluelib.domain.policy.MtuNegotiationPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorModelTest {

    private val device = BluetoothDeviceId.of("AA:BB:CC:DD:EE:FF")

    @Test
    fun `maps known GATT statuses to retryable decisions`() {
        val genericError = GattStatus.of(133)
        assertEquals("GATT_ERROR", genericError.name)
        assertEquals(GattStatusSource.STACK, genericError.source)
        assertTrue(genericError.retryable)

        val notPermitted = GattStatus.of(0x03)
        assertFalse(notPermitted.retryable)
        assertEquals(GattStatusSource.ATT, notPermitted.source)

        assertTrue(GattStatus.of(0).isSuccess)
    }

    @Test
    fun `treats undocumented high codes as retryable transport errors`() {
        val unknown = GattStatus.of(0x91)

        assertEquals(GattStatusSource.STACK, unknown.source)
        assertTrue(unknown.retryable)
        assertTrue(unknown.name.startsWith("UNCLASSIFIED_"))
    }

    @Test
    fun `GATT failures inherit retryability from the status`() {
        val retryable = BlueLibError.GattOperationFailed("write", GattStatus.of(133), device)
        val permanent = BlueLibError.GattOperationFailed("write", GattStatus.of(0x02), device)

        assertTrue(retryable.isRetryable)
        assertFalse(permanent.isRetryable)
        assertEquals("GATT_OPERATION_FAILED", retryable.code.name)
        assertEquals(device.address.value, retryable.context["device"])
    }

    @Test
    fun `exposes documentation anchors for every error`() {
        val errors = listOf(
            BlueLibError.AdapterUnavailable(),
            BlueLibError.BluetoothDisabled,
            BlueLibError.PermissionMissing("scan", listOf("android.permission.BLUETOOTH_SCAN")),
            BlueLibError.ScanThrottled(1_000),
            BlueLibError.Timeout("discoverServices", 15_000, device),
            BlueLibError.BondLost(device),
            BlueLibError.Closed("GattSession"),
            BlueLibError.Cancelled("read"),
        )

        errors.forEach { assertTrue(it.docsAnchor.isNotBlank(), "${it.code} needs a docs anchor") }
        assertTrue(errors.map { it.docsAnchor }.distinct().size == errors.size)
    }

    @Test
    fun `reports the reason when a bond was lost after an auth failure`() {
        val error = BlueLibError.BondLost(device, BondLossReason.LE_ENCRYPT_FAILURE, systemRepairInProgress = true)

        assertTrue(error.message.contains("re-pairing"))
        assertTrue(error.isRetryable)
    }

    @Test
    fun `result helpers keep the failure path intact`() {
        val success: BlueLibResult<Int> = successOf(41)
        val failure: BlueLibResult<Int> = failureOf(BlueLibError.BluetoothDisabled)

        assertEquals(42, success.map { it + 1 }.getOrNull())
        assertIs<BlueLibResult.Failure>(failure.map { it + 1 })
        assertEquals("BLUETOOTH_DISABLED", failure.errorOrNull()?.code?.name)
        assertEquals(7, failure.fold(onSuccess = { it }, onFailure = { 7 }))
    }

    @Test
    fun `runCatching turns validation problems into typed rejections`() {
        val result = blueLibRunCatching("scan") { ScanRequest(manufacturerId = 0x1_0000) }

        val failure = assertIs<BlueLibResult.Failure>(result)
        assertEquals(BlueLibErrorCode.OPERATION_REJECTED, failure.error.code)
    }

    @Test
    fun `MTU policy clamps the plan and rejects impossible results`() {
        assertEquals(517, MtuNegotiationPolicy.plan(null))
        assertEquals(23, MtuNegotiationPolicy.plan(10))

        val negotiated = assertIs<MtuNegotiationPolicy.NegotiationOutcome.Negotiated>(
            MtuNegotiationPolicy.evaluate(plan = 517, negotiated = 247),
        )
        assertEquals(247, negotiated.result.negotiated)
        assertEquals(244, negotiated.result.usablePayloadBytes)
        assertEquals(270, negotiated.result.shortfallBytes)
        assertFalse(negotiated.result.metRequest)

        val failed = assertIs<MtuNegotiationPolicy.NegotiationOutcome.Failed>(
            MtuNegotiationPolicy.evaluate(plan = 517, negotiated = 10),
        )
        assertEquals(BlueLibErrorCode.MTU_NEGOTIATION_FAILED, failed.error.code)
    }

    @Test
    fun `scan requests are validated before they reach the platform`() {
        assertIs<BlueLibValidationException.ValueOutOfRange>(
            runCatching { ScanRequest(reportDelayMillis = -1) }.exceptionOrNull(),
        )
        assertIs<BlueLibValidationException.IllegalState>(
            runCatching {
                ScanRequest(phy = io.github.cybersafetyid.bluelib.domain.model.Phy.LE_CODED, legacyOnly = true)
            }.exceptionOrNull(),
        )
        assertIs<BlueLibValidationException.ValueOutOfRange>(
            runCatching { ScanRequest(autoStopAfterMillis = 10) }.exceptionOrNull(),
        )
        assertTrue(ScanRequest(scanMode = ScanMode.BALANCED).isUnfiltered)
    }
}
