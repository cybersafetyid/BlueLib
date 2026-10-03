package io.github.cybersafetyid.bluelib.sample

import android.view.inputmethod.EditorInfo
import io.github.cybersafetyid.bluelib.domain.codec.DelimiterFramer
import io.github.cybersafetyid.bluelib.domain.codec.MessageFramer
import io.github.cybersafetyid.bluelib.domain.codec.RawFramer
import io.github.cybersafetyid.bluelib.domain.error.BlueLibException
import io.github.cybersafetyid.bluelib.domain.error.BlueLibValidationException
import io.github.cybersafetyid.bluelib.domain.messenger.BluetoothMessenger
import io.github.cybersafetyid.bluelib.sample.databinding.ViewTerminalBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives one terminal card over any [BluetoothMessenger]: GATT characteristic, Classic socket, TCP,
 * USB serial or UART all look the same here, which is the point of the messenger API.
 */
class TerminalController(private val b: ViewTerminalBinding, private val host: SampleHost) {

    private var messenger: BluetoothMessenger? = null
    private var receiveJob: Job? = null
    private val lines = ArrayDeque<String>()

    /** Called after the user closed the link from the terminal. */
    var onClosed: (() -> Unit)? = null

    private val format: PayloadFormat
        get() = when (b.terminalFormat.checkedButtonId) {
            b.formatHex.id -> PayloadFormat.HEX
            b.formatBase64.id -> PayloadFormat.BASE64
            else -> PayloadFormat.TEXT
        }

    init {
        b.terminalSend.setOnClickListener { send() }
        b.terminalInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) send()
            actionId == EditorInfo.IME_ACTION_SEND
        }
        b.terminalClose.setOnClickListener { close() }
    }

    /** Framer for the next messenger: a fresh line framer when "line mode" is on. */
    fun framer(): MessageFramer = if (b.terminalNewline.isChecked) DelimiterFramer.lineFeed() else RawFramer

    fun attach(endpoint: String, newMessenger: BluetoothMessenger) {
        detach()
        messenger = newMessenger
        lines.clear()
        b.terminalOutput.text = ""
        b.terminalEndpoint.text = b.root.context.getString(R.string.terminal_connected, endpoint)
        setEnabled(true)
        append("● linked to $endpoint")
        receiveJob = host.scope.launch {
            try {
                newMessenger.incomingBytes.collect { bytes -> append("◀ ${bytes.render(format)}") }
            } catch (failure: BlueLibException) {
                // E.g. a GATT characteristic that can be written but not subscribed: sending still works.
                append("ⓘ receive unavailable: ${failure.error.message}")
            } catch (failure: BlueLibValidationException) {
                append("✖ framing error: ${failure.message}")
            }
        }
    }

    /** Closes the messenger (and with it the stream link) and resets the card. */
    fun close() {
        if (messenger == null) return
        detach()
        append("○ closed")
        onClosed?.invoke()
    }

    private fun detach() {
        receiveJob?.cancel()
        receiveJob = null
        messenger?.close()
        messenger = null
        b.terminalEndpoint.setText(R.string.terminal_idle)
        setEnabled(false)
    }

    private fun send() {
        val current = messenger ?: return
        val text = b.terminalInput.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val chosen = format
        host.scope.launch {
            val result = when (chosen) {
                PayloadFormat.TEXT -> current.sendText(text)
                PayloadFormat.HEX -> current.sendHex(text.replace(" ", ""))
                PayloadFormat.BASE64 -> current.sendBase64(text)
            }
            result.onSuccess {
                append("▶ $text")
                b.terminalInput.text = null
            }.onFailure { append("✖ ${it.describe()}") }
        }
    }

    private fun setEnabled(linked: Boolean) {
        b.terminalSend.isEnabled = linked
        b.terminalClose.isEnabled = linked
        // The framer is chosen when the messenger is created, so it cannot change mid-link.
        b.terminalNewline.isEnabled = !linked
    }

    private fun append(line: String) {
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        b.terminalOutput.text = lines.joinToString("\n")
    }

    private companion object {
        const val MAX_LINES = 60
    }
}
