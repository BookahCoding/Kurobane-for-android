package dev.kurobanekotlin.core.chat

import dev.kurobanekotlin.core.connection.GatewayConnection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One classified event from the ZeroClaw chat WebSocket (/ws/chat, sub-protocol zeroclaw.v1). */
sealed interface WsEvent {
    data class SessionStart(val sessionId: String, val resumed: Boolean, val messageCount: Int, val name: String?) : WsEvent
    data object Connected : WsEvent
    data class Chunk(val content: String) : WsEvent
    data class Thinking(val content: String) : WsEvent
    data class ToolCall(val id: String, val name: String, val args: String) : WsEvent
    data class ToolResult(val id: String, val name: String, val output: String) : WsEvent
    data class ApprovalRequest(val requestId: String, val tool: String, val summary: String, val timeoutSecs: Int) : WsEvent
    data class Done(val fullResponse: String, val model: String?, val provider: String?, val costUsd: Double?, val tokensUsed: Long?) : WsEvent
    data object Aborted : WsEvent
    data class Error(val message: String, val code: String?) : WsEvent
    data class Other(val raw: JsonObject) : WsEvent
}

/**
 * WebSocket chat client. Wire protocol (zeroclaw.v1): connect to
 * ws(s)://host/ws/chat?agent_alias=…&session_id=…[&name=…] with the bearer token in the
 * Authorization header and/or the `bearer.<token>` sub-protocol, then send
 * {"type":"message","content":…} and read classified events.
 */
class ChatStream(
    private val http: HttpClient,
    private val conn: GatewayConnection,
    private val sessionId: String,
    private val sessionName: String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun open(): WebSocketSession {
        val url = buildString {
            append(conn.wsChatUrl)
            append("&session_id=").append(sessionId.encodeURLParameter())
            if (!sessionName.isNullOrBlank()) append("&name=").append(sessionName.encodeURLParameter())
        }
        return http.webSocketSession(url) {
            // A turn with tools can run for minutes; the shared 30 s request timeout must not apply.
            // The token goes in the Authorization header (the gateway also accepts ?token=, which would leak into logs).
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            conn.token?.let { token -> header(HttpHeaders.Authorization, "Bearer $token") }
        }
    }

    fun events(session: WebSocketSession): Flow<WsEvent> = flow {
        for (frame in session.incoming) {
            if (frame is Frame.Text) {
                val obj = runCatching { json.parseToJsonElement(frame.readText()).jsonObject }.getOrNull() ?: continue
                emit(classify(obj))
            }
        }
    }

    suspend fun sendMessage(session: WebSocketSession, content: String) {
        session.send(Frame.Text(buildJsonObject { put("type", "message"); put("content", content) }.toString()))
    }

    suspend fun sendApproval(session: WebSocketSession, requestId: String, decision: String) {
        session.send(Frame.Text(buildJsonObject {
            put("type", "approval_response"); put("request_id", requestId); put("decision", decision)
        }.toString()))
    }

    fun classify(frame: JsonObject): WsEvent {
        fun s(k: String) = (frame[k] as? JsonPrimitive)?.contentOrNull
        fun n(k: String) = (frame[k] as? JsonPrimitive)?.content
        return when (s("type")) {
            "session_start" -> WsEvent.SessionStart(s("session_id") ?: "", n("resumed") == "true", n("message_count")?.toIntOrNull() ?: 0, s("name"))
            "connected" -> WsEvent.Connected
            "chunk" -> WsEvent.Chunk(s("content") ?: "")
            "thinking" -> WsEvent.Thinking(s("content") ?: "")
            "tool_call" -> WsEvent.ToolCall(s("id") ?: "", s("name") ?: "", frame["args"]?.toString() ?: "")
            "tool_result" -> WsEvent.ToolResult(s("id") ?: "", s("name") ?: "", frame["output"]?.toString() ?: "")
            "approval_request" -> WsEvent.ApprovalRequest(s("request_id") ?: "", s("tool") ?: "",
                s("arguments_summary") ?: "", n("timeout_secs")?.toIntOrNull() ?: 0)
            "done" -> WsEvent.Done(s("full_response") ?: "", s("model"), s("provider"),
                n("cost_usd")?.toDoubleOrNull(), n("tokens_used")?.toLongOrNull())
            "aborted" -> WsEvent.Aborted
            "error" -> WsEvent.Error(s("message") ?: "Unknown gateway error", s("code"))
            else -> WsEvent.Other(frame)
        }
    }
}
