package dev.kurobanekotlin.core.connection

import dev.kurobanekotlin.core.gateway.GatewayUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
        get() = "${GatewayUrl.wsBase(baseUrl)}/ws/chat?agent_alias=${agentAlias.encodeURLParameter()}"
}

/** Low-level REST client for the ZeroClaw gateway. Every call carries the bearer token when present. */
class ZeroClawApi(private val http: HttpClient, private val conn: GatewayConnection) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun HttpRequestBuilder.bearer() {
        conn.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    suspend fun get(path: String): ApiResultLike = call(HttpMethod.Get, path, null)
    suspend fun post(path: String, body: JsonObject? = null): ApiResultLike = call(HttpMethod.Post, path, body)
    suspend fun put(path: String, body: JsonObject? = null): ApiResultLike = call(HttpMethod.Put, path, body)
    suspend fun delete(path: String): ApiResultLike = call(HttpMethod.Delete, path, null)

    private suspend fun call(method: HttpMethod, path: String, body: JsonObject?): ApiResultLike {
        return try {
            val resp = http.request("${conn.baseUrl}$path") {
                this.method = method
                bearer()
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
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
            http.request("${conn.baseUrl}/api/pair") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("code", code.trim())
                    put("device_name", deviceName)
                    put("device_type", "android")
                }.toString())
            }
        } catch (e: Exception) {
            return PairResult.Error("Could not reach the gateway: ${e.message}")
        }
        val body = runCatching { json.parseToJsonElement(resp.bodyAsText()).jsonObject }.getOrNull()
        return parsePairResponse(resp.status.value, body)
    }

    suspend fun status(): ApiResultLike = get("/api/status")
    suspend fun sessions(): ApiResultLike = get("/api/sessions")
    suspend fun sessionMessages(id: String): ApiResultLike = get("/api/sessions/${id.encodeURLPathPart()}/messages")
    suspend fun deleteSession(id: String): ApiResultLike = delete("/api/sessions/${id.encodeURLPathPart()}")
    suspend fun renameSession(id: String, name: String): ApiResultLike =
        put("/api/sessions/${id.encodeURLPathPart()}", buildJsonObject { put("name", name) })
    suspend fun abortSession(id: String): ApiResultLike = post("/api/sessions/${id.encodeURLPathPart()}/abort", JsonObject(emptyMap()))
    suspend fun config(): ApiResultLike = get("/api/config")
}

/** Maps the /api/pair answer to a [PairResult]; the gateway uses 400/403/429 with an `error` field for failures. */
fun parsePairResponse(status: Int, body: JsonObject?): PairResult {
    val paired = (body?.get("paired") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    if (status == 200 && paired) {
        val token = (body.get("token") as? kotlinx.serialization.json.JsonPrimitive)?.content
        return if (token.isNullOrEmpty()) PairResult.Error("Paired but no token returned") else PairResult.Ok(token)
    }
    val message = (body?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
    return PairResult.Error(
        when {
            status == 403 || status == 400 -> message ?: "Invalid pairing code"
            status == 429 -> message ?: "Too many attempts. Wait a minute and try again."
            else -> message ?: "HTTP $status"
        }
    )
}

data class ApiResultLike(val httpStatus: Int, val body: Result<JsonObject>) {
    val ok: Boolean get() = httpStatus in 200..299 && body.isSuccess
}

sealed interface PairResult {
    data class Ok(val token: String) : PairResult
    data class Error(val message: String) : PairResult
}
