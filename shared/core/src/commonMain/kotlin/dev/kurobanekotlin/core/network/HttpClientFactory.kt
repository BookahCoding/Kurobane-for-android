package dev.kurobanekotlin.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
    fun create(engine: HttpClientEngine? = null, json: Json = Json { ignoreUnknownKeys = true }): HttpClient {
        val config: HttpClientConfig.() -> Unit = {
            install(ContentNegotiation) { json(json) }
            install(WebSockets) { pingInterval = 20_000 }
            install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
        }
        return if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }
}
