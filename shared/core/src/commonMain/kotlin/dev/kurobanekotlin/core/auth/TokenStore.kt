package dev.kurobanekotlin.core.auth

/**
 * Bearer-token storage. On Android this is backed by the Android Keystore (see androidMain);
 * the common interface keeps the core testable with an in-memory fake.
 */
interface TokenStore {
    fun get(serverKey: String): String?
    fun set(serverKey: String, token: String)
    fun remove(serverKey: String)
}

class MemoryTokenStore : TokenStore {
    private val map = mutableMapOf<String, String>()
    override fun get(serverKey: String): String? = map[serverKey]
    override fun set(serverKey: String, token: String) { map[serverKey] = token }
    override fun remove(serverKey: String) { map.remove(serverKey) }
}
