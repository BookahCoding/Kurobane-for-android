package dev.kurobanekotlin.core.connection

import dev.kurobanekotlin.core.gateway.GatewayUrl
import dev.kurobanekotlin.core.catalog.ReadModels
import dev.kurobanekotlin.core.catalog.UploadPayload
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
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
    suspend fun patch(path: String, body: JsonObject? = null): ApiResultLike = call(HttpMethod.Patch, path, body)

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
    suspend fun tools(agentAlias: String? = null): ApiResultLike =
        get("/api/tools" + (agentAlias?.takeIf { it.isNotBlank() }?.let { "?agent=${it.encodeURLParameter()}" } ?: ""))

    suspend fun health(): ApiResultLike = get("/api/health")
    suspend fun runningSessions(): ApiResultLike = get("/api/sessions/running")
    suspend fun cron(): ApiResultLike = get("/api/cron")
    suspend fun memory(agent: String): ApiResultLike = get("/api/memory?agent=${agent.encodeURLParameter()}")
    suspend fun cost(agent: String): ApiResultLike = get("/api/cost?agent=${agent.encodeURLParameter()}")
    suspend fun plugins(): ApiResultLike = get("/api/plugins")
    suspend fun skillBundles(): ApiResultLike = get("/api/skills/bundles")
    suspend fun agentSkills(alias: String): ApiResultLike = get("/api/agents/${alias.encodeURLPathPart()}/skills")
    suspend fun personality(agent: String): ApiResultLike = get("/api/personality?agent=${agent.encodeURLParameter()}")
    suspend fun devices(): ApiResultLike = get("/api/devices")
    suspend fun configProp(path: String): ApiResultLike = get("/api/config/prop?path=${path.encodeURLParameter()}")

    /** PUT /api/config/prop. The caller must refuse a masked secret before calling this. */
    suspend fun setConfigProp(path: String, value: JsonElement): ApiResultLike =
        put("/api/config/prop", buildJsonObject {
            put("path", path)
            put("value", value)
        })

    /** POST /api/cron. Schedule is a cron expression string on gateway 0.8.7. */
    suspend fun addCron(agent: String, schedule: String, name: String, prompt: String?, command: String?): ApiResultLike =
        post("/api/cron", buildJsonObject {
            put("agent", agent)
            put("schedule", schedule)
            if (name.isNotBlank()) put("name", name)
            if (!prompt.isNullOrBlank()) {
                put("job_type", "agent")
                put("prompt", prompt)
            } else {
                put("job_type", "shell")
                put("command", command ?: "")
            }
        })

    suspend fun setCronEnabled(id: String, enabled: Boolean): ApiResultLike =
        patch("/api/cron/${id.encodeURLPathPart()}", buildJsonObject { put("enabled", enabled) })

    suspend fun deleteCron(id: String): ApiResultLike = delete("/api/cron/${id.encodeURLPathPart()}")

    suspend fun cronRuns(id: String): ApiResultLike = get("/api/cron/${id.encodeURLPathPart()}/runs")

    suspend fun personalityFile(agent: String, filename: String): ApiResultLike =
        get("/api/personality/${filename.encodeURLPathPart()}?agent=${agent.encodeURLParameter()}")

    suspend fun putPersonality(agent: String, filename: String, content: String, expectedMtimeMs: Long?): ApiResultLike =
        put("/api/personality/${filename.encodeURLPathPart()}?agent=${agent.encodeURLParameter()}", buildJsonObject {
            put("content", content)
            if (expectedMtimeMs != null) put("expected_mtime_ms", expectedMtimeMs)
        })

    /** POST /api/upload?agent= with the raw image bytes. The marker is what the chat message must contain. */
    suspend fun uploadImage(agent: String, bytes: ByteArray, mime: String): Result<UploadPayload> {
        val resp = try {
            http.request("${conn.baseUrl}/api/upload?agent=${agent.encodeURLParameter()}") {
                method = HttpMethod.Post
                bearer()
                timeout { requestTimeoutMillis = 60_000 }
                contentType(ContentType.parse(mime))
                setBody(bytes)
            }
        } catch (_: Exception) {
            return Result.failure(IllegalStateException(GatewayErrors.unreachable()))
        }
        val obj = runCatching { json.parseToJsonElement(resp.bodyAsText()).jsonObject }.getOrNull()
        if (resp.status.value !in 200..299 || obj == null) {
            val detail = (obj?.get("message") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (obj?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
            return Result.failure(IllegalStateException(GatewayErrors.plain(resp.status.value, detail)))
        }
        val payload = ReadModels.upload(obj)
            ?: return Result.failure(IllegalStateException("The gateway accepted the image but did not return a marker."))
        return Result.success(payload)
    }
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
