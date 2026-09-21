package io.github.cybersafetyid.bluelib.domain

import io.github.cybersafetyid.bluelib.port.ClockPort

/** Test clock driven by [advance]. */
internal class FakeClock(private var now: Long = 0L) : ClockPort {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}
