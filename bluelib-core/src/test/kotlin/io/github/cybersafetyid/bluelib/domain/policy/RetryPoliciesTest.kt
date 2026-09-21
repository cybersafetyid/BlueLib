package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import io.github.cybersafetyid.bluelib.domain.error.BondLossReason
import io.github.cybersafetyid.bluelib.domain.error.GattStatus
import io.github.cybersafetyid.bluelib.domain.model.BluetoothDeviceId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RetryPoliciesTest {

    private val device = BluetoothDeviceId.of("AA:BB:CC:DD:EE:FF")

    @Test
    fun `backoff grows exponentially and is capped`() {
        val backoff = BackoffPolicy(
            initialDelayMillis = 500,
            maxDelayMillis = 4_000,
            multiplier = 2.0,
            jitter = BackoffPolicy.Jitter.NONE,
        )

        assertEquals(500, backoff.delayFor(1))
        assertEquals(1_000, backoff.delayFor(2))
        assertEquals(2_000, backoff.delayFor(3))
        assertEquals(4_000, backoff.delayFor(4))
        assertEquals(4_000, backoff.delayFor(9))
    }

    @Test
    fun `full jitter stays inside the ceiling and can pick short delays`() {
        val backoff = BackoffPolicy(initialDelayMillis = 1_000, maxDelayMillis = 10_000, jitter = BackoffPolicy.Jitter.FULL)
        val random = Random(42)

        val samples = (1..50).map { backoff.delayFor(4, random) }

        assertTrue(samples.all { it in 0..8_000 }, "jitter must stay within the ceiling: $samples")
        assertTrue(samples.distinct().size > 10, "full jitter should not collapse to one value")
    }

    @Test
    fun `retries retryable failures until the attempt limit`() {
        val policy = RetryPolicy(maxAttempts = 3, backoff = BackoffPolicy(jitter = BackoffPolicy.Jitter.NONE))
        val retryable = BlueLibError.GattOperationFailed("read", GattStatus.of(GattStatus.GATT_ERROR), device)

        val first = assertIs<RetryDecision.Retry>(policy.afterFailure(1, retryable))
        assertEquals(2, first.attempt)
        val second = assertIs<RetryDecision.Retry>(policy.afterFailure(2, retryable))
        assertEquals(3, second.attempt)
        val giveUp = assertIs<RetryDecision.GiveUp>(policy.afterFailure(3, retryable))
        assertTrue(giveUp.reason.contains("attempt limit"))
    }

    @Test
    fun `never retries non retryable failures`() {
        val policy = RetryPolicy(maxAttempts = 5)

        val decisions = listOf(
            BlueLibError.FeatureUnsupported("LE_HDT_PHY", currentApiLevel = 34, requiredApiLevel = 37),
            BlueLibError.ServiceNotFound(io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid.HEART_RATE),
            BlueLibError.CharacteristicNotNotifiable(io.github.cybersafetyid.bluelib.domain.model.BluetoothUuid.HEART_RATE, 0x02),
            BlueLibError.OperationRejected("scan already stopped"),
        ).map { policy.afterFailure(1, it) }

        decisions.forEach { assertIs<RetryDecision.GiveUp>(it) }
    }

    @Test
    fun `a missing permission is retryable because the user may grant it`() {
        val policy = RetryPolicy(maxAttempts = 3)

        val decision = policy.afterFailure(
            attempt = 1,
            error = BlueLibError.PermissionMissing("scan", listOf("android.permission.BLUETOOTH_SCAN")),
        )

        assertIs<RetryDecision.Retry>(decision)
    }

    @Test
    fun `reconnect policy stops when the bond was lost and does not reconnects on its own`() {
        val policy = ReconnectPolicy()

        val decision = policy.onConnectionLost(attempt = 1, bondLossReason = BondLossReason.BREDR_AUTH_FAILURE)

        val stop = assertIs<ReconnectPolicy.Decision.Stop>(decision)
        assertTrue(stop.reason.contains("user consent"))
    }

    @Test
    fun `reconnect policy waits for Android 17 autonomous re-pairing`() {
        val policy = ReconnectPolicy()

        val decision = policy.onConnectionLost(attempt = 1, systemRepairInProgress = true)

        val await = assertIs<ReconnectPolicy.Decision.AwaitSystemRepair>(decision)
        assertTrue(await.reason.contains("Android 17"))
    }

    @Test
    fun `reconnect policy schedules a bounded number of attempts`() {
        val policy = ReconnectPolicy(
            maxAttempts = 2,
            backoff = BackoffPolicy(jitter = BackoffPolicy.Jitter.NONE),
        )

        assertIs<ReconnectPolicy.Decision.Schedule>(policy.onConnectionLost(attempt = 1))
        assertEquals(
            ReconnectPolicy.Decision.Stop("reached the 2 reconnect attempt limit"),
            policy.onConnectionLost(attempt = 2),
        )
    }
}
