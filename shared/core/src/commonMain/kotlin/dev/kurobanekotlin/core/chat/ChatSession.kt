package dev.kurobanekotlin.core.chat

import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import io.ktor.client.HttpClient
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

/** True for the frames that end a turn; the socket is closed after one of them. */
fun WsEvent.endsTurn(): Boolean = this is WsEvent.Done || this is WsEvent.Aborted || this is WsEvent.Error

/** Folds one gateway frame into the UI state. Pure, so it is unit-tested without a socket. */
fun ChatUiState.reduce(evt: WsEvent): ChatUiState = when (evt) {
    is WsEvent.Chunk -> copy(streaming = streaming + evt.content)
    is WsEvent.Thinking -> copy(status = "Thinking…")
    is WsEvent.ToolCall -> copy(status = "Tool: ${evt.name}")
    is WsEvent.ToolResult -> copy(status = "Tool done: ${evt.name}")
    is WsEvent.ApprovalRequest -> copy(pendingApproval = evt, status = "Approval needed: ${evt.tool}")
    is WsEvent.Done -> {
        val full = streaming.ifBlank { evt.fullResponse }
        copy(
            messages = if (full.isBlank()) messages else messages + ChatMessage("assistant", full),
            streaming = "", running = false, pendingApproval = null,
            status = buildString {
                append("Done")
                evt.model?.let { append(" · $it") }
                evt.tokensUsed?.let { append(" · $it tokens") }
                evt.costUsd?.let { append(" · \$").append(formatCost(it)) }
            },
        )
    }
    is WsEvent.Aborted -> copy(streaming = "", running = false, pendingApproval = null, status = "Aborted")
    is WsEvent.Error -> copy(streaming = "", running = false, pendingApproval = null, error = evt.message, status = evt.message)
    else -> this
}

private fun formatCost(usd: Double): String {
    val tenThousandths = Math.round(usd * 10_000)
    return "${tenThousandths / 10_000}.${(tenThousandths % 10_000).toString().padStart(4, '0')}"
}

/**
 * Drives one gateway session: loads persisted history, opens the WebSocket per turn, sends a message,
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
        if (r.httpStatus !in 200..299) return
        val msgs = (obj["messages"] as? JsonArray).orEmpty().mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val content = o["content"]
            ChatMessage(
                role = (o["role"] as? JsonPrimitive)?.contentOrNull ?: "",
                content = (content as? JsonPrimitive)?.contentOrNull ?: content?.toString().orEmpty(),
            )
        }
        _state.value = _state.value.copy(
            messages = msgs,
            status = if (msgs.isEmpty()) "New chat" else "Loaded ${msgs.size} messages",
        )
    }

    /** Sends one message and returns when the turn ends (done, aborted, error or a dropped connection). */
    suspend fun send(text: String): Result<Unit> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Empty message"))
        if (_state.value.running) return Result.failure(IllegalStateException("A turn is already running"))
        val s = ChatStream(http, conn, sessionId)
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage("user", text),
            streaming = "", running = true, error = null, pendingApproval = null, status = "Running…",
        )
        var ws: WebSocketSession? = null
        return try {
            ws = s.open()
            socket = ws
            stream = s
            s.sendMessage(ws, text)
            s.events(ws).onEach { evt -> _state.value = _state.value.reduce(evt) }.first { it.endsTurn() }
            Result.success(Unit)
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(running = false, pendingApproval = null)
            throw e
        } catch (e: NoSuchElementException) {
            val msg = "The connection closed before the reply finished."
            _state.value = _state.value.copy(running = false, pendingApproval = null, error = msg, status = msg)
            Result.failure(e)
        } catch (e: Exception) {
            val msg = e.message ?: "Could not reach the gateway."
            _state.value = _state.value.copy(running = false, pendingApproval = null, error = msg, status = msg)
            Result.failure(e)
        } finally {
            withContext(NonCancellable) { runCatching { ws?.close(CloseReason(CloseReason.Codes.NORMAL, "turn over")) } }
            socket = null
            stream = null
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

    /** Asks the gateway to abort; the turn ends when its `aborted` frame arrives. */
    suspend fun abort() {
        runCatching { api.abortSession(sessionId) }
        _state.value = _state.value.copy(status = "Abort requested")
    }
}
