package io.github.cybersafetyid.bluelib.domain.policy

import io.github.cybersafetyid.bluelib.domain.FakeClock
import io.github.cybersafetyid.bluelib.domain.error.BlueLibError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScanQuotaGovernorTest {

    @Test
    fun `allows five scans inside the window and throttles the sixth`() {
        val clock = FakeClock()
        val governor = ScanQuotaGovernor(clock)

        repeat(5) { index ->
            assertIs<ScanQuotaGovernor.Decision.Allowed>(
                governor.acquire(),
                "scan #${index + 1} should be allowed",
            )
            clock.advance(1_000)
        }

        val throttled = assertIs<ScanQuotaGovernor.Decision.Throttled>(governor.acquire())
        assertEquals(25_000, throttled.retryAfterMillis)
        assertEquals(0, governor.remainingBudget)
    }

    @Test
    fun `frees budget as the window slides`() {
        val clock = FakeClock()
        val governor = ScanQuotaGovernor(clock)

        repeat(5) { governor.acquire() }
        assertIs<ScanQuotaGovernor.Decision.Throttled>(governor.acquire())

        clock.advance(ScanQuotaGovernor.DEFAULT_WINDOW_MILLIS + 1)

        assertIs<ScanQuotaGovernor.Decision.Allowed>(governor.acquire())
        assertEquals(4, governor.remainingBudget)
    }

    @Test
    fun `reports the timestamp the next scan becomes possible`() {
        val clock = FakeClock()
        val governor = ScanQuotaGovernor(clock)
        repeat(5) { governor.acquire() }

        assertEquals(30_000L, governor.nextAvailableAtMillis())
    }

    @Test
    fun `records a platform throttle so the budget matches the stack`() {
        val clock = FakeClock()
        val governor = ScanQuotaGovernor(clock)

        governor.recordPlatformThrottle()

        assertEquals(0, governor.remainingBudget)
        assertIs<ScanQuotaGovernor.Decision.Throttled>(governor.acquire())
    }

    @Test
    fun `maps throttling to a typed error with a retry hint`() {
        val clock = FakeClock()
        val governor = ScanQuotaGovernor(clock)
        repeat(5) { governor.acquire() }

        val error = governor.decisionToError(governor.acquire())

        val throttled = assertIs<BlueLibError.ScanThrottled>(error)
        assertTrue(throttled.isRetryable)
        assertTrue(throttled.retryAfterMillis > 0)
    }
}
