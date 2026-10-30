package dev.kurobanekotlin.core.network

/** Result of a gateway call: either data or a typed failure the UI can show honestly. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Fail(val kind: Kind, val message: String) : ApiResult<Nothing> {
        enum class Kind { Offline, Unauthorized, RateLimited, NoRelease, Malformed, Http, Unknown }
    }
}
