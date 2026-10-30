package dev.kurobanekotlin.core.gateway

/** Normalizes a user-entered gateway address: adds a scheme when missing, keeps any port, strips trailing slashes. */
object GatewayUrl {
    fun normalize(input: String): Result<String> {
        var s = input.trim()
        if (s.isEmpty()) return Result.failure(IllegalArgumentException("Enter the gateway address"))
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { java.net.URI(s) }.getOrElse {
            return Result.failure(IllegalArgumentException("Not a valid address: $input"))
        }
        if (uri.scheme !in listOf("http", "https")) return Result.failure(IllegalArgumentException("Use http:// or https://"))
        if (uri.host.isNullOrBlank()) return Result.failure(IllegalArgumentException("Missing host in $input"))
        return Result.success(s.trimEnd('/'))
    }

    fun wsBase(baseUrl: String): String =
        baseUrl.replaceFirst(Regex("^http"), "ws") // http->ws, https->wss
}
