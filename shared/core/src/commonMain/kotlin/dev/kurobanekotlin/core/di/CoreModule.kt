package dev.kurobanekotlin.core.di

import dev.kurobanekotlin.core.auth.MemoryTokenStore
import dev.kurobanekotlin.core.auth.TokenStore
import dev.kurobanekotlin.core.network.HttpClientFactory
import io.ktor.client.HttpClient
import org.koin.dsl.module

/** Common DI: platform modules override [platformModule] for Keystore/DataStore-backed implementations. */
val coreModule = module {
    single<HttpClient> { HttpClientFactory.create() }
    single<TokenStore> { MemoryTokenStore() } // replaced by the Android Keystore-backed store in androidApp
}
