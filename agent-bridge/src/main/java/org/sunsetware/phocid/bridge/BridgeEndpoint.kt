package org.sunsetware.phocid.bridge

/**
 * Pure helpers for the configurable listen address.
 *
 * The bridge accepts only IPv4/IPv6 literals (and the `localhost` convenience alias). Host names
 * are deliberately rejected so validation and binding never perform a DNS lookup on the main
 * thread.
 */
object BridgeEndpoint {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 8765
    val PORT_RANGE = 1024..65535

    fun normalizeHost(raw: String): String {
        val trimmed = raw.trim().removeSurrounding("[", "]").trim()
        return if (trimmed.equals("localhost", ignoreCase = true)) DEFAULT_HOST else trimmed
    }

    fun hostError(raw: String): String? {
        val host = normalizeHost(raw)
        if (host.isEmpty()) return "Enter a listen address"
        if (host.contains("://") || host.contains('/')) return "Enter an IP address, not a URL"
        if (!isNumericHost(host)) return "Use an IPv4 or IPv6 address"
        return null
    }

    fun portError(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Enter a port"
        val port = trimmed.toIntOrNull() ?: return "Port must be a number"
        if (port !in PORT_RANGE) return "Port must be 1024-65535"
        return null
    }

    fun isNumericHost(raw: String): Boolean {
        val host = raw.trim().removeSurrounding("[", "]").trim()
        return isIpv4(host) || isIpv6(host)
    }

    fun isWildcard(raw: String): Boolean {
        val host = normalizeHost(raw)
        return host == "0.0.0.0" || host == "::"
    }

    fun isLoopback(raw: String): Boolean {
        val host = normalizeHost(raw)
        return host == "127.0.0.1" || host.startsWith("127.") || host == "::1"
    }

    fun httpUrl(host: String, port: Int): String {
        val normalized = normalizeHost(host)
        val displayHost =
            when (normalized) {
                "0.0.0.0" -> "127.0.0.1"
                "::" -> "::1"
                else -> normalized
            }
        val formattedHost =
            if (displayHost.contains(':') && !displayHost.startsWith("[")) "[$displayHost]"
            else displayHost
        return "http://$formattedHost:$port/mcp"
    }

    private fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() &&
                part.length <= 3 &&
                part.all { it in '0'..'9' } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isIpv6(host: String): Boolean {
        if (!host.contains(':')) return false
        if (host.contains(":::")) return false
        val doubleColon = host.indexOf("::")
        if (doubleColon >= 0 && host.indexOf("::", doubleColon + 1) >= 0) return false

        if (doubleColon < 0) {
            val groups = host.split(':')
            return groups.size == 8 && groups.all { isHexGroup(it) }
        }

        val sections = host.split("::", limit = 2)
        val left = sections[0].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList()
        val right = sections[1].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList()
        return left.size + right.size <= 7 && (left + right).all { isHexGroup(it) }
    }

    private fun isHexGroup(group: String): Boolean =
        group.isNotEmpty() &&
            group.length <= 4 &&
            group.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
}
