package dev.kurobanekotlin.core.connection

import dev.kurobanekotlin.core.gateway.GatewayUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Everything needed to reach one ZeroClaw gateway: base URL + bearer token + agent alias.
 * The token is supplied by the caller (stored by the app in Android Keystore-backed storage).
 */
data class GatewayConnection(
    val name: String = "My ZeroClaw",
    val baseUrl: String,
    val agentAlias: String = "default",
    val token: String? = null,
) {
    val wsChatUrl: String
        get() = "${GatewayUrl.wsBase(baseUrl)}/ws/chat?agent_alias=${agentAlias.urlEncode()}"

    companion object {
        fun String.urlEncode(): String = java.net.URLEncoder.encode(this, "UTF-8")
    }
}

/** Low-level REST client for the ZeroClaw gateway. Every call carries the bearer token when present. */
class ZeroClawApi(private val http: HttpClient, private val conn: GatewayConnection) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer() {
        conn.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    suspend fun get(path: String): ApiResultLike = call("GET", path, null)
    suspend fun post(path: String, body: Any? = null): ApiResultLike = call("POST", path, body)
    suspend fun put(path: String, body: Any? = null): ApiResultLike = call("PUT", path, body)
    suspend fun delete(path: String): ApiResultLike = call("DELETE", path, null)

    private suspend fun call(method: String, path: String, body: Any?): ApiResultLike {
        return try {
            val resp = when (method) {
                "GET" -> http.get("${conn.baseUrl}$path") { bearer() }
                "DELETE" -> http.delete("${conn.baseUrl}$path") { bearer() }
                else -> http.request("${conn.baseUrl}$path") {
                    this.method = io.ktor.http.HttpMethod(method)
                    bearer()
                    contentType(ContentType.Application.Json)
                    if (body != null) setBody(body)
                }
            }
            val text = resp.bodyAsText()
            ApiResultLike(resp.status.value, runCatching { json.parseToJsonElement(text).jsonObject })
        } catch (e: Exception) {
            ApiResultLike(0, Result.failure(e))
        }
    }

    /** POST /api/pair — exchange a pairing code for a bearer token. Does not store it itself. */
    suspend fun pair(code: String, deviceName: String): PairResult {
        val resp = try {
            http.post("${conn.baseUrl}/api/pair") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("code" to code.trim(), "device_name" to deviceName, "device_type" to "android"))
            }
        } catch (e: Exception) {
            return PairResult.Error("Could not reach the gateway: ${e.message}")
        }
        val body = runCatching { json.parseToJsonElement(resp.bodyAsText()).jsonObject }.getOrNull()
        return when {
            resp.status.value == 200 && body?.get("paired")?.jsonPrimitive?.content == "true" ->
                PairResult.Ok(body["token"]?.jsonPrimitive?.content ?: return PairResult.Error("Paired but no token returned"))
            else -> PairResult.Error(body?.get("error")?.jsonPrimitive?.content ?: "HTTP ${resp.status.value}")
        }
    }

    suspend fun status(): ApiResultLike = get("/api/status")
    suspend fun sessions(): ApiResultLike = get("/api/sessions")
    suspend fun sessionMessages(id: String): ApiResultLike = get("/api/sessions/${id.urlEncode()}/messages")
    suspend fun deleteSession(id: String): ApiResultLike = delete("/api/sessions/${id.urlEncode()}")
    suspend fun renameSession(id: String, name: String): ApiResultLike = put("/api/sessions/${id.urlEncode()}", mapOf("name" to name))
    suspend fun abortSession(id: String): ApiResultLike = post("/api/sessions/${id.urlEncode()}/abort", emptyMap<String, String>())
    suspend fun config(): ApiResultLike = get("/api/config")
    suspend fun setProp(path: String, valueJson: String): ApiResultLike =
        put("/api/config/prop", mapOf("path" to path, "value" to json.parseToJsonElement(valueJson)))

    private fun String.urlEncode() = GatewayConnection.urlEncode(this)
}

data class ApiResultLike(val httpStatus: Int, val body: Result<JsonObject>)

sealed interface PairResult {
    data class Ok(val token: String) : PairResult
    data class Error(val message: String) : PairResult
}
