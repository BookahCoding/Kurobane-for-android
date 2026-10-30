package dev.kurobanekotlin.core.connection

/** Plain sentences for the failures the chat and the pages have to explain. */
object GatewayErrors {
    fun unreachable(): String =
        "The gateway could not be reached. Check the address and that this phone can see it."

    fun unauthorized(): String =
        "The gateway refused the saved token (HTTP 401). Pair this phone again."

    fun provider(detail: String): String =
        "The model provider returned an error. ${detail.trim()}"

    fun plain(httpStatus: Int, detail: String? = null): String = when (httpStatus) {
        0 -> unreachable()
        401 -> unauthorized()
        403 -> "The gateway refused this request (HTTP 403). " +
            (detail?.takeIf { it.isNotBlank() } ?: "Pair this phone again if the token is no longer valid.")
        else -> detail?.takeIf { it.isNotBlank() } ?: "The gateway returned HTTP $httpStatus."
    }
}
