package dev.kurobanekotlin.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.kurobanekotlin.core.storage.KeyValueStore
import kotlinx.coroutines.flow.first

class DataStoreKeyValueStore(private val ds: DataStore<Preferences>) : KeyValueStore {
    override suspend fun get(key: String): String? =
        ds.data.first()[stringPreferencesKey(key)]

    override suspend fun set(key: String, value: String) {
        ds.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        ds.edit { it.remove(stringPreferencesKey(key)) }
    }
}
