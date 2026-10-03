package io.github.cybersafetyid.bluelib.sample

import android.view.View
import android.widget.ArrayAdapter
import com.google.android.material.chip.Chip
import io.github.cybersafetyid.bluelib.android.usb.UsbDeviceInfo
import io.github.cybersafetyid.bluelib.domain.error.BlueLibResult
import io.github.cybersafetyid.bluelib.domain.model.Parity
import io.github.cybersafetyid.bluelib.domain.model.SerialSettings
import io.github.cybersafetyid.bluelib.domain.model.StopBits
import io.github.cybersafetyid.bluelib.port.ByteConnection
import io.github.cybersafetyid.bluelib.sample.databinding.PageLinksBinding
import kotlinx.coroutines.launch

/** TCP/IP, USB serial and native UART links, all driven through the same messenger terminal. */
class LinksPage(private val b: PageLinksBinding, private val host: SampleHost) {

    private val blueLib get() = host.blueLib
    private val context get() = b.root.context
    private val terminal = TerminalController(b.terminal, host)
    private var usbDevices = emptyList<UsbDeviceInfo>()

    init {
        b.serialBaud.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, BAUD_RATES.map(Int::toString)))
        b.serialLine.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, LINE_FORMATS))
        b.btnTcp.setOnClickListener { connectTcp() }
        b.btnUsbRefresh.setOnClickListener { refreshUsb() }
        b.btnUsb.setOnClickListener { openUsb() }
        b.btnUart.setOnClickListener { openUart() }
        refreshUsb()
    }

    private fun connectTcp() {
        val target = TcpTarget.parse(b.tcpTarget.text?.toString().orEmpty())
            ?: return host.say("Enter host:port, e.g. 192.168.1.50:9100")
        host.log.add("TCP connecting to ${target.host}:${target.port}")
        attach { blueLib.connectTcp(target.host, target.port) }
    }

    fun refreshUsb() {
        usbDevices = blueLib.usbDevices()
        b.usbDevices.removeAllViews()
        usbDevices.forEachIndexed { index, device ->
            b.usbDevices.addView(
                Chip(context).apply {
                    id = View.generateViewId()
                    tag = index
                    isCheckable = true
                    isChecked = index == 0
                    text = "${device.productName ?: "USB %04X:%04X".format(device.vendorId, device.productId)} · ${device.driver ?: "unsupported"}"
                },
            )
        }
        b.usbEmpty.visibility = if (usbDevices.isEmpty()) View.VISIBLE else View.GONE
        host.log.add("USB devices: ${usbDevices.size}")
    }

    private fun openUsb() {
        val chip = b.usbDevices.findViewById<Chip>(b.usbDevices.checkedChipId)
        val device = (chip?.tag as? Int)?.let(usbDevices::getOrNull)
            ?: return host.say("Plug in a USB serial adapter (OTG) and tap Refresh.")
        val settings = serialSettings() ?: return
        host.log.add("USB opening ${device.deviceName} (${device.driver}) with $settings")
        attach { blueLib.connectUsbSerial(device, settings) }
    }

    private fun openUart() {
        val settings = serialSettings() ?: return
        val path = b.uartPath.text?.toString()?.trim().orEmpty()
        host.log.add("UART opening $path with $settings")
        attach { blueLib.openUart(path, settings) }
    }

    private fun attach(open: suspend () -> BlueLibResult<ByteConnection>) {
        host.busy(true)
        host.scope.launch {
            open()
                .onSuccess { connection ->
                    host.log.add("Linked ${connection.endpoint}")
                    terminal.attach(connection.endpoint, blueLib.createMessenger(connection, terminal.framer()))
                }
                .onFailure { error ->
                    host.log.add("Link failed ${error.describe()}")
                    host.say(error.message)
                }
            host.busy(false)
        }
    }

    private fun serialSettings(): SerialSettings? {
        val baud = b.serialBaud.text?.toString()?.toIntOrNull()
        val line = parseLineFormat(b.serialLine.text?.toString().orEmpty())
        if (baud == null || baud <= 0 || line == null) {
            host.say("Pick a baud rate and a line format such as 8N1")
            return null
        }
        return SerialSettings(baudRate = baud, dataBits = line.first, parity = line.second, stopBits = line.third)
    }

    fun close() = terminal.close()

    companion object {
        val BAUD_RATES = listOf(1200, 2400, 4800, 9600, 19_200, 38_400, 57_600, 115_200, 230_400, 460_800, 921_600)
        val LINE_FORMATS = listOf("8N1", "8E1", "8O1", "7E1", "7O1", "8N2")

        /** `8N1` → (8 data bits, no parity, 1 stop bit). */
        fun parseLineFormat(text: String): Triple<Int, Parity, StopBits>? {
            val match = Regex("^([5-8])([NOEMS])([12])$").matchEntire(text.trim().uppercase()) ?: return null
            val (bits, parity, stop) = match.destructured
            return Triple(
                bits.toInt(),
                when (parity) {
                    "O" -> Parity.ODD
                    "E" -> Parity.EVEN
                    "M" -> Parity.MARK
                    "S" -> Parity.SPACE
                    else -> Parity.NONE
                },
                if (stop == "2") StopBits.TWO else StopBits.ONE,
            )
        }
    }
}
