package dev.kurobanekotlin.core.storage

/** Simple key-value persistence interface; the Android implementation uses DataStore. */
interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String)
    suspend fun remove(key: String)
}

/** Settings keys used by the app. */
object SettingsKeys {
    const val Servers = "servers.json"      // list of GatewayConnection JSON (token excluded; token lives in Keystore-backed store)
    const val ActiveServer = "active_server"
    const val Appearance = "appearance"     // system | light | dark
    const val ThemeId = "theme_id"
}
