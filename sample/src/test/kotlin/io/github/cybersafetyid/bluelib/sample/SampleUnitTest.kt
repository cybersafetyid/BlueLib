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
        assertEquals("BlueLib 0.2.0", version)
    }
}

class TcpTargetTest {

    @Test
    fun parsesHostPortAndRejectsGarbage() {
        assertEquals(TcpTarget("192.168.1.50", 9100), TcpTarget.parse(" 192.168.1.50:9100 "))
        assertEquals(TcpTarget("printer.local", 515), TcpTarget.parse("printer.local:515"))
        assertEquals(TcpTarget("fe80::1", 502), TcpTarget.parse("[fe80::1]:502"))
        listOf("", "192.168.1.50", ":9100", "host:0", "host:70000", "host:abc", "/dev/ttyS3")
            .forEach { assertEquals("'$it' should be rejected", null, TcpTarget.parse(it)) }
    }
}
