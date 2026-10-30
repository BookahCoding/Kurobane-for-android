package dev.kurobanekotlin.android

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.kurobanekotlin.core.auth.TokenStore
import java.io.File

/**
 * Bearer tokens are stored in EncryptedSharedPreferences (Android Keystore-backed, AES-256),
 * never in plain settings. One file per Windows-user equivalent: the app-private prefs.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "kurobane_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(serverKey: String): String? = prefs.getString(serverKey, null)
    override fun set(serverKey: String, token: String) { prefs.edit().putString(serverKey, token).apply() }
    override fun remove(serverKey: String) { prefs.edit().remove(serverKey).apply() }
}
