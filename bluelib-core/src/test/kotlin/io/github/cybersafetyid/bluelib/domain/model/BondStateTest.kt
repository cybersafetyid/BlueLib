package io.github.cybersafetyid.bluelib.domain.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The bond state mapping is a small piece of glue that is very easy to get wrong: `BOND_NONE` is `10`
 * and `BOND_BONDED` is `12`, so "the bigger number wins" looks plausible and is wrong.
 */
class BondStateTest {

    @Test
    fun `maps the platform constants`() {
        assertEquals(BondState.NONE, BondState.fromPlatformValue(BondState.PLATFORM_NONE))
        assertEquals(BondState.BONDING, BondState.fromPlatformValue(BondState.PLATFORM_BONDING))
        assertEquals(BondState.BONDED, BondState.fromPlatformValue(BondState.PLATFORM_BONDED))
    }

    @Test
    fun `the platform constants keep the values the framework has always used`() {
        // Guarded because these numbers travel through broadcasts and across processes.
        assertEquals(10, BondState.PLATFORM_NONE)
        assertEquals(11, BondState.PLATFORM_BONDING)
        assertEquals(12, BondState.PLATFORM_BONDED)
    }

    @Test
    fun `unknown values degrade to NONE instead of reporting a bond`() {
        listOf(0, 1, 9, 13, 99, -1).forEach { value ->
            assertEquals(BondState.NONE, BondState.fromPlatformValue(value), "value $value")
        }
    }
}
