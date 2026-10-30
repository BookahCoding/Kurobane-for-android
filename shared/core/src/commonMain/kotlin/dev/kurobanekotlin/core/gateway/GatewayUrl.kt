package dev.kurobanekotlin.core.gateway

import java.net.InetAddress
import java.net.URI

/**
 * Normalizes a user-entered gateway address and enforces the cleartext policy.
 *
 * Android's network security config cannot express IP ranges (a `<domain>` entry is a hostname, so
 * `100.64.0.0/10` there matches nothing). The OS therefore permits cleartext globally and this object is
 * the gatekeeper: `http://`/`ws://` is accepted only for loopback, private (RFC 1918), Tailscale
 * (100.64.0.0/10, fd7a:115c:a1e0::/48) addresses and `*.ts.net` names; every other host must use `https://`.
 */
object GatewayUrl {
    fun normalize(input: String): Result<String> {
        var s = input.trim()
        if (s.isEmpty()) return Result.failure(IllegalArgumentException("Enter the gateway address"))
        if (!s.contains("://")) {
            val bareHost = hostOf("http://$s")
            s = (if (bareHost != null && isPrivateIpLiteral(bareHost)) "http://" else "https://") + s
        }
        val uri = runCatching { URI(s) }.getOrElse {
            return Result.failure(IllegalArgumentException("Not a valid address: $input"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.failure(IllegalArgumentException("Use http:// or https://"))
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalArgumentException("Missing host in $input"))
        if (scheme == "http" && !isCleartextAllowed(host)) {
            return Result.failure(IllegalArgumentException(
                "Plain http:// is only allowed for Tailscale, private-network and local addresses. Use https:// for $host."))
        }
        return Result.success(s.trimEnd('/'))
    }

    fun wsBase(baseUrl: String): String =
        baseUrl.replaceFirst(Regex("^http"), "ws") // http->ws, https->wss

    /** True when plain HTTP to [host] stays on a private/encrypted-overlay network. */
    fun isCleartextAllowed(host: String): Boolean {
        val h = host.trim('[', ']').lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".ts.net")) return true
        return isPrivateIpLiteral(h)
    }

    private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    private fun isPrivateIpLiteral(host: String): Boolean {
        val h = host.trim('[', ']')
        parseIpv4(h)?.let { (a, b) ->
            return a == 127 || a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127)
        }
        if (h.contains(':')) {
            // Only a literal reaches here, so InetAddress does no DNS lookup.
            val bytes = runCatching { InetAddress.getByName(h).address }.getOrNull() ?: return false
            if (bytes.size != 16) return false
            val loopback = bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()
            val tailscaleUla = bytes[0] == 0xfd.toByte() && bytes[1] == 0x7a.toByte() &&
                bytes[2] == 0x11.toByte() && bytes[3] == 0x5c.toByte() &&
                bytes[4] == 0xa1.toByte() && bytes[5] == 0xe0.toByte()
            return loopback || tailscaleUla
        }
        return false
    }

    /** Strict dotted-quad: four decimal octets 0–255; returns the first two. */
    private fun parseIpv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.takeIf { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 0..255 } ?: return null }
        return n[0] to n[1]
    }
}
