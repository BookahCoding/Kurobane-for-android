package dev.kurobanekotlin.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.kurobanekotlin.core.auth.TokenStore
import dev.kurobanekotlin.core.storage.KeyValueStore
import org.koin.dsl.module

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "kurobane_settings")

/** Android-backed implementations: DataStore for settings, Android Keystore for the bearer token. */
val androidModule = module {
    single<KeyValueStore> { DataStoreKeyValueStore(get<Context>().settingsDataStore) }
    single<TokenStore> { KeystoreTokenStore(get<Context>()) }
}
