package dev.kurobanekotlin.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
    /**
     * REST calls time out after 30 s. Long-lived requests (the chat WebSocket, APK downloads) override
     * the request timeout per call, so a slow tool-using turn or a big download is never cut off.
     */
    fun create(engine: HttpClientEngine? = null, json: Json = Json { ignoreUnknownKeys = true }): HttpClient {
        val config: HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(json) }
            install(WebSockets) { pingIntervalMillis = 20_000 }
            install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
        }
        return if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }
}
