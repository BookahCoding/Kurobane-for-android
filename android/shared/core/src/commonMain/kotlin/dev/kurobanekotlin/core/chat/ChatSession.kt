package dev.kurobanekotlin.core.chat

import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import io.ktor.client.HttpClient
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** UI-facing state of one chat: messages plus the live tail while a turn runs. */
data class ChatMessage(val role: String, val content: String)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    val status: String = "",
    val pendingApproval: WsEvent.ApprovalRequest? = null,
    val error: String? = null,
)

/**
 * Drives one gateway session: loads persisted history, opens the WebSocket, sends a message,
 * and folds chunk/thinking/tool/approval/done frames into a [ChatUiState].
 */
class ChatSession(
    private val http: HttpClient,
    private val api: ZeroClawApi,
    private val conn: GatewayConnection,
    val sessionId: String,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var socket: WebSocketSession? = null
    private var stream: ChatStream? = null

    suspend fun loadHistory() {
        val r = api.sessionMessages(sessionId)
        val obj = r.body.getOrNull() ?: return
        val msgs = (obj["messages"] as? JsonArray).orEmpty().map {
            val o = it.jsonObject
            ChatMessage(o["role"]?.jsonPrimitive?.content ?: "", o["content"]?.jsonPrimitive?.content ?: "")
        }
        _state.value = _state.value.copy(
            messages = msgs,
            status = if (msgs.isEmpty()) "New chat" else "Loaded ${msgs.size} messages",
        )
    }

    suspend fun send(text: String): Result<Unit> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Empty message"))
        if (_state.value.running) return Result.failure(IllegalStateException("A turn is already running"))
        val s = ChatStream(http, conn, sessionId)
        return try {
            val ws = s.open()
            socket = ws
            stream = s
            _state.value = _state.value.copy(
                messages = _state.value.messages + ChatMessage("user", text),
                streaming = "", running = true, error = null, status = "Running…",
            )
            s.sendMessage(ws, text)
            var acc = ""
            s.events(ws).collect { evt ->
                when (evt) {
                    is WsEvent.Chunk -> { acc += evt.content; _state.value = _state.value.copy(streaming = acc) }
                    is WsEvent.Thinking -> _state.value = _state.value.copy(status = "Thinking…")
                    is WsEvent.ToolCall -> _state.value = _state.value.copy(status = "Tool: ${evt.name}")
                    is WsEvent.ToolResult -> _state.value = _state.value.copy(status = "Tool done: ${evt.name}")
                    is WsEvent.ApprovalRequest -> _state.value = _state.value.copy(pendingApproval = evt, status = "Approval needed: ${evt.tool}")
                    is WsEvent.Done -> {
                        val full = if (acc.isBlank()) evt.fullResponse else acc
                        _state.value = _state.value.copy(
                            messages = _state.value.messages + ChatMessage("assistant", full),
                            streaming = "", running = false, pendingApproval = null,
                            status = buildString {
                                append("Done")
                                evt.model?.let { append(" · $it") }
                                evt.tokensUsed?.let { append(" · $it tokens") }
                                evt.costUsd?.let { append(" · $${'$'}%.4f".format(it)) }
                            },
                        )
                    }
                    is WsEvent.Aborted -> _state.value = _state.value.copy(running = false, status = "Aborted")
                    is WsEvent.Error -> _state.value = _state.value.copy(running = false, error = evt.message, status = evt.message)
                    else -> {}
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = _state.value.copy(running = false, error = e.message, status = e.message ?: "Error")
            Result.failure(e)
        }
    }

    /** Answer the pending approval on the live socket. decision: approve | deny | always */
    suspend fun answerApproval(decision: String) {
        val s = stream ?: return
        val ws = socket ?: return
        val req = _state.value.pendingApproval ?: return
        s.sendApproval(ws, req.requestId, decision)
        _state.value = _state.value.copy(pendingApproval = null, status = "Approval sent: $decision")
    }

    suspend fun abort() {
        runCatching { api.abortSession(sessionId) }
        _state.value = _state.value.copy(running = false, status = "Abort requested")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
