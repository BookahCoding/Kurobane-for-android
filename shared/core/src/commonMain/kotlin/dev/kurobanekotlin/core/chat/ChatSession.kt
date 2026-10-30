package dev.kurobanekotlin.core.chat

import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.GatewayErrors
import dev.kurobanekotlin.core.connection.ZeroClawApi
import io.ktor.client.HttpClient
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
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

/** How one assistant bubble ended. User bubbles stay [BubbleState.Complete]. */
enum class BubbleState { Complete, Running, Error, Cancelled }

data class ToolTrace(val id: String, val name: String, val args: String, val result: String? = null)

/** UI-facing state of one chat: messages plus the live tail while a turn runs. */
data class ChatMessage(
    val role: String,
    val content: String,
    val thinking: String = "",
    val tools: List<ToolTrace> = emptyList(),
    val bubble: BubbleState = BubbleState.Complete,
)

/** Which plain-language problem frame the chat should show, if any. */
enum class ProblemFrame { Unreachable, Unauthorized, Provider }

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val thinking: String = "",
    val tools: List<ToolTrace> = emptyList(),
    val running: Boolean = false,
    val status: String = "",
    val pendingApproval: WsEvent.ApprovalRequest? = null,
    val error: String? = null,
    val frame: ProblemFrame? = null,
)

/** The server still asks for every always_ask tool. Shown under the Always button. */
const val ALWAYS_ASK_NOTE =
    "Always still asks on the server for every always_ask tool, including every shell call."

/**
 * A decision is sent only for the request id still on the open socket, and only once.
 * A dropped socket or a stale id must not produce a frame.
 */
object ApprovalGate {
    fun maySend(socketOpen: Boolean, pendingId: String?, tappedId: String, alreadySent: Boolean): Boolean =
        socketOpen && !alreadySent && !pendingId.isNullOrBlank() && pendingId == tappedId
}

/**
 * `error` codes the gateway sends for a rejected client frame while the turn keeps running (see ws.rs: steering
 * and frame validation). Every other `error` is a turn failure.
 */
private val nonFatalErrorCodes = setOf("STEERING_QUEUE_FULL", "STEERING_CLOSED", "INVALID_JSON", "EMPTY_CONTENT", "INVALID_APPROVAL_RESPONSE")

/** True for the frames that end a turn; the socket is closed after one of them. */
fun WsEvent.endsTurn(): Boolean = this is WsEvent.Done || this is WsEvent.Aborted ||
    (this is WsEvent.Error && code !in nonFatalErrorCodes)

/** Status line for a `done` frame. A zero cost with unpriced tokens is not shown as money. */
fun doneStatus(evt: WsEvent.Done): String = buildString {
    append("Done")
    evt.model?.let { append(" · $it") }
    evt.tokensUsed?.let { append(" · $it tokens") }
    val unpriced = evt.unpricedTokens ?: 0L
    if (unpriced > 0) append(" · $unpriced unpriced")
    val cost = evt.costUsd
    when {
        cost != null && cost > 0.0 && unpriced == 0L -> append(" · $").append(formatCost(cost))
        cost != null && (cost == 0.0 || unpriced > 0) -> append(" · the gateway did not price them")
    }
}

private fun WsEvent.Error.isProvider(): Boolean {
    val code = code.orEmpty()
    return code.contains("provider", ignoreCase = true) || message.contains("provider", ignoreCase = true)
}

/** Folds one gateway frame into the UI state. Pure, so it is unit-tested without a socket. */
fun ChatUiState.reduce(evt: WsEvent): ChatUiState = when (evt) {
    is WsEvent.Chunk -> copy(streaming = streaming + evt.content)
    is WsEvent.Thinking -> copy(thinking = thinking + evt.content, status = "Thinking…")
    is WsEvent.ToolCall -> copy(status = "Tool: ${evt.name}", tools = tools + ToolTrace(evt.id, evt.name, evt.args))
    is WsEvent.ToolResult -> copy(
        status = "Tool done: ${evt.name}",
        tools = if (evt.id.isNotBlank() && tools.any { it.id == evt.id }) {
            tools.map { if (it.id == evt.id) it.copy(result = evt.output) else it }
        } else tools + ToolTrace(evt.id, evt.name, "", evt.output),
    )
    is WsEvent.ApprovalRequest -> copy(pendingApproval = evt, status = "Approval needed: ${evt.tool}")
    is WsEvent.Done -> {
        val full = streaming.ifBlank { evt.fullResponse }
        val keep = full.isNotBlank() || thinking.isNotBlank() || tools.isNotEmpty()
        copy(
            messages = if (!keep) messages else messages + ChatMessage("assistant", full, thinking, tools, BubbleState.Complete),
            streaming = "", thinking = "", tools = emptyList(), running = false, pendingApproval = null,
            status = doneStatus(evt), frame = null,
        )
    }
    is WsEvent.Aborted -> {
        val keep = streaming.isNotBlank() || thinking.isNotBlank() || tools.isNotEmpty()
        copy(
            messages = if (!keep) messages else messages + ChatMessage("assistant", streaming, thinking, tools, BubbleState.Cancelled),
            streaming = "", thinking = "", tools = emptyList(), running = false, pendingApproval = null,
            status = "Cancelled",
        )
    }
    is WsEvent.Error -> {
        if (!evt.endsTurn()) copy(error = evt.message)
        else {
            val provider = evt.isProvider()
            val plain = if (provider) dev.kurobanekotlin.core.connection.GatewayErrors.provider(evt.message) else evt.message
            val keep = streaming.isNotBlank() || thinking.isNotBlank() || tools.isNotEmpty()
            copy(
                messages = if (!keep) messages else messages + ChatMessage(
                    "assistant", streaming, thinking, tools, BubbleState.Error,
                ),
                streaming = "", thinking = "", tools = emptyList(), running = false, pendingApproval = null,
                error = plain, status = plain, frame = if (provider) ProblemFrame.Provider else frame,
            )
        }
    }
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
    private val sentApprovals = mutableSetOf<String>()
    /** Set when the UI closes the socket on purpose, so the turn is not treated as a failed send. */
    private var suppressDrop = false

    suspend fun loadHistory() {
        val r = api.sessionMessages(sessionId)
        val obj = r.body.getOrNull()
        if (!r.ok || obj == null) {
            val detail = obj?.let { (it["error"] as? JsonPrimitive)?.contentOrNull ?: (it["message"] as? JsonPrimitive)?.contentOrNull }
            val unreachable = r.httpStatus == 0
            val unauthorized = r.httpStatus == 401
            _state.value = _state.value.copy(
                error = GatewayErrors.plain(r.httpStatus, detail),
                frame = when {
                    unreachable -> ProblemFrame.Unreachable
                    unauthorized -> ProblemFrame.Unauthorized
                    else -> _state.value.frame
                },
            )
            return
        }
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
        suppressDrop = false
        val s = ChatStream(http, conn, sessionId)
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage("user", text),
            streaming = "", thinking = "", tools = emptyList(),
            running = true, error = null, frame = null, pendingApproval = null, status = "Running…",
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
        } catch (e: Exception) {
            if (suppressDrop) {
                _state.value = _state.value.copy(running = false, pendingApproval = null)
                return Result.success(Unit)
            }
            // A dropped socket ends the turn. The message is not sent again.
            val dropped = e is NoSuchElementException || e.message.isNullOrBlank()
            val msg = if (dropped || _state.value.running) {
                "The gateway could not be reached, so the reply stopped. The message was not sent again."
            } else {
                GatewayErrors.unreachable()
            }
            _state.value = _state.value.copy(
                running = false, pendingApproval = null, error = msg, status = msg, frame = ProblemFrame.Unreachable,
            )
            Result.failure(e)
        } finally {
            withContext(NonCancellable) { runCatching { ws?.close(CloseReason(CloseReason.Codes.NORMAL, "turn over")) } }
            socket = null
            stream = null
        }
    }

    /**
     * Answer the pending approval on the live socket. decision: approve | deny | always.
     * One tap sends once. A closed socket or a request id that is no longer current sends nothing.
     */
    suspend fun answerApproval(decision: String, requestId: String) {
        val req = _state.value.pendingApproval
        val ws = socket
        val open = ws != null && stream != null && ws.isActive
        if (!ApprovalGate.maySend(open, req?.requestId, requestId, requestId in sentApprovals)) {
            if (req?.requestId == requestId && !open) {
                _state.value = _state.value.copy(
                    pendingApproval = null,
                    error = "The connection dropped, so no decision was sent.",
                    status = "The connection dropped, so no decision was sent.",
                    frame = ProblemFrame.Unreachable,
                )
            }
            return
        }
        val live = stream ?: return
        val socketNow = socket ?: return
        sentApprovals += requestId
        _state.value = _state.value.copy(pendingApproval = null, status = "Approval sent: $decision")
        try {
            live.sendApproval(socketNow, requestId, decision)
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                error = "The connection dropped, so no decision was sent.",
                status = "The connection dropped, so no decision was sent.",
                frame = ProblemFrame.Unreachable,
            )
        }
    }

    /** Drops the live socket without sending a decision or another message. */
    suspend fun close() {
        suppressDrop = true
        val ws = socket
        socket = null
        stream = null
        withContext(NonCancellable) { runCatching { ws?.close(CloseReason(CloseReason.Codes.NORMAL, "closed")) } }
        _state.value = _state.value.copy(running = false, pendingApproval = null)
    }

    /** Asks the gateway to abort; the turn ends when its `aborted` frame arrives. */
    suspend fun abort() {
        runCatching { api.abortSession(sessionId) }
        _state.value = _state.value.copy(status = "Abort requested")
    }
}
