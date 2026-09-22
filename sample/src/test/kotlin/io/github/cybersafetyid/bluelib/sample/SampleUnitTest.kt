package io.github.cybersafetyid.bluelib.sample

import io.github.cybersafetyid.bluelib.BlueLib
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleUnitTest {

    @Test
    fun verifyBlueLibVersion() {
        val version = BlueLib.version
        assertTrue("Version should start with BlueLib", version.startsWith("BlueLib"))
        assertEquals("BlueLib 0.1.2", version)
    }
}
