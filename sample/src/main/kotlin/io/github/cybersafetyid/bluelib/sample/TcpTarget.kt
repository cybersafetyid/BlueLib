package io.github.cybersafetyid.bluelib.sample

/** `host:port` typed by the user; IPv6 hosts go in brackets, e.g. `[fe80::1]:9100`. */
data class TcpTarget(val host: String, val port: Int) {
    companion object {
        fun parse(text: String): TcpTarget? {
            val trimmed = text.trim()
            val host = trimmed.substringBeforeLast(':', "").removePrefix("[").removeSuffix("]")
            val port = trimmed.substringAfterLast(':', "").toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65_535) return null
            return TcpTarget(host, port)
        }
    }
}
