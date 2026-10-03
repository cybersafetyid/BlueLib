package io.github.cybersafetyid.bluelib.transport

import io.github.cybersafetyid.bluelib.BlueLibInternal
import io.github.cybersafetyid.bluelib.domain.model.Parity
import io.github.cybersafetyid.bluelib.domain.model.SerialSettings
import io.github.cybersafetyid.bluelib.domain.model.StopBits
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Register values are checked against the ones the Linux kernel drivers send for the same settings. */
@OptIn(BlueLibInternal::class)
class UsbSerialProtocolTest {

    private data class Transfer(val requestType: Int, val request: Int, val value: Int, val index: Int, val data: List<Byte>?)

    private fun record(settings: SerialSettings, driver: UsbSerialDriver, chipVersion: Int = 0x31): List<Transfer> {
        val transfers = mutableListOf<Transfer>()
        val failure = UsbSerialProtocol.configure(driver, { type, request, value, index, data ->
            if (type and 0x80 != 0) data!![0] = chipVersion.toByte()
            transfers += Transfer(type, request, value, index, data?.toList())
            data?.size ?: 0
        }, settings, index = 0)
        assertNull(failure)
        return transfers
    }

    @Test
    fun `FTDI divisors match the well known values`() {
        assertEquals(0x4138, UsbSerialProtocol.ftdiBaudDivisor(9600))
        assertEquals(0x001A, UsbSerialProtocol.ftdiBaudDivisor(115_200))
        assertEquals(0, UsbSerialProtocol.ftdiBaudDivisor(3_000_000))
        assertEquals(1, UsbSerialProtocol.ftdiBaudDivisor(2_000_000))
        assertNull(UsbSerialProtocol.ftdiBaudDivisor(4_000_000))
    }

    @Test
    fun `CH34x divisors match the Linux ch341 driver`() {
        assertEquals(0xB202, UsbSerialProtocol.ch34xBaudValue(9600))
        assertEquals(0xCC03, UsbSerialProtocol.ch34xBaudValue(115_200))
    }

    @Test
    fun `CDC-ACM sends line coding then raises DTR and RTS`() {
        val transfers = record(SerialSettings(115_200, 8, StopBits.ONE, Parity.EVEN), UsbSerialDriver.CDC_ACM)
        assertEquals(listOf(0x20, 0x22), transfers.map { it.request })
        assertContentEquals(byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 2, 8), transfers[0].data!!.toByteArray())
        assertEquals(0x0003, transfers[1].value)
    }

    @Test
    fun `CP210x encodes 7E1 line control`() {
        val transfers = record(SerialSettings(9600, 7, StopBits.ONE, Parity.EVEN), UsbSerialDriver.CP210X)
        assertEquals(0x0720, transfers.first { it.request == 0x03 }.value)
    }

    @Test
    fun `CH34x skips the LCR register on old chips and sets the no-buffering bit on new ones`() {
        val old = record(SerialSettings(), UsbSerialDriver.CH34X, chipVersion = 0x27)
        assertEquals(listOf(0x5F, 0xA1, 0x9A, 0xA4), old.map { it.request })
        assertEquals(0x1312, old[2].value)
        assertEquals(0xB202, old[2].index)

        val new = record(SerialSettings(), UsbSerialDriver.CH34X, chipVersion = 0x31)
        assertEquals(0xB282, new.first { it.value == 0x1312 }.index)
        assertEquals(0xC3, new.first { it.value == 0x2518 }.index)
    }

    @Test
    fun `stops at the first rejected transfer`() {
        var calls = 0
        val failure = UsbSerialProtocol.configure(UsbSerialDriver.CP210X, { _, _, _, _, _ -> calls++; -1 }, SerialSettings(), 0)
        assertEquals("IFC_ENABLE was rejected by the device", failure)
        assertEquals(1, calls)
    }

    @Test
    fun `strips FTDI status bytes from every packet`() {
        val data = byteArrayOf(1, 2, 10, 11, 1, 2, 12)
        assertContentEquals(byteArrayOf(10, 11, 12), UsbSerialProtocol.stripStatusBytes(data, data.size, packetSize = 4, statusBytes = 2))
    }

    @Test
    fun `detects drivers by vendor and interface`() {
        assertEquals(UsbSerialDriver.FTDI, UsbSerialProtocol.detect(0x0403, 0x6001, false, true))
        assertEquals(UsbSerialDriver.CH34X, UsbSerialProtocol.detect(0x1A86, 0x7523, false, true))
        assertEquals(UsbSerialDriver.CDC_ACM, UsbSerialProtocol.detect(0x1A86, 0x55D4, true, true))
        assertNull(UsbSerialProtocol.detect(0x067B, 0x2303, false, true))
        assertEquals(UsbSerialDriver.RAW_BULK, UsbSerialProtocol.detect(0x04B8, 0x0202, false, true))
    }
}
