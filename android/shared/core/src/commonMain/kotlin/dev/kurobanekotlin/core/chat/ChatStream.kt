package dev.kurobanekotlin.core.chat

import dev.kurobanekotlin.core.connection.GatewayConnection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            append("&session_id=").append(GatewayConnection.urlEncode(sessionId))
            if (!sessionName.isNullOrBlank()) append("&name=").append(GatewayConnection.urlEncode(sessionName))
        }
        return http.webSocketSession(url) {
            // The gateway accepts the bearer either as an Authorization header or as the
            // `bearer.<token>` Sec-WebSocket-Protocol entry; header auth works on all engines.
            conn.token?.let { token -> header(io.ktor.http.HttpHeaders.Authorization, "Bearer $token") }
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
        session.send(Frame.Text(json.encodeToString(mapOf("type" to "message", "content" to content))))
    }

    suspend fun sendApproval(session: WebSocketSession, requestId: String, decision: String) {
        session.send(Frame.Text(json.encodeToString(mapOf(
            "type" to "approval_response", "request_id" to requestId, "decision" to decision))))
    }

    fun classify(frame: JsonObject): WsEvent {
        fun s(k: String) = frame[k]?.jsonPrimitive?.contentOrNull
        return when (s("type")) {
            "session_start" -> WsEvent.SessionStart(s("session_id") ?: "", frame["resumed"]?.jsonPrimitive?.content == "true",
                frame["message_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0, s("name"))
            "connected" -> WsEvent.Connected
            "chunk" -> WsEvent.Chunk(s("content") ?: "")
            "thinking" -> WsEvent.Thinking(s("content") ?: "")
            "tool_call" -> WsEvent.ToolCall(s("id") ?: "", s("name") ?: "", frame["args"]?.toString() ?: "")
            "tool_result" -> WsEvent.ToolResult(s("id") ?: "", s("name") ?: "", frame["output"]?.toString() ?: "")
            "approval_request" -> WsEvent.ApprovalRequest(s("request_id") ?: "", s("tool") ?: "",
                s("arguments_summary") ?: "", frame["timeout_secs"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            "done" -> WsEvent.Done(s("full_response") ?: "", s("model"), s("provider"),
                frame["cost_usd"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                frame["tokens_used"]?.jsonPrimitive?.content?.toLongOrNull())
            "aborted" -> WsEvent.Aborted
            "error" -> WsEvent.Error(s("message") ?: "Unknown gateway error", s("code"))
            else -> WsEvent.Other(frame)
        }
    }
}
