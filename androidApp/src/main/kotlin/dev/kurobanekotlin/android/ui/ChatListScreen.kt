package dev.kurobanekotlin.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.chat.ALWAYS_ASK_NOTE
import dev.kurobanekotlin.core.chat.BubbleState
import dev.kurobanekotlin.core.chat.ChatMessage
import dev.kurobanekotlin.core.chat.ChatSession
import dev.kurobanekotlin.core.chat.Slash
import dev.kurobanekotlin.core.chat.SlashCommand
import dev.kurobanekotlin.core.chat.SlashRoute
import dev.kurobanekotlin.core.chat.ImageSniff
import dev.kurobanekotlin.core.chat.MarkdownParser
import dev.kurobanekotlin.core.chat.MdBlock
import dev.kurobanekotlin.core.chat.MdSpan
import dev.kurobanekotlin.core.chat.ProblemFrame
import dev.kurobanekotlin.core.chat.ToolTrace
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.gateway.StatusParser
import dev.kurobanekotlin.core.sessions.GatewaySession
import dev.kurobanekotlin.core.storage.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun draftKey(baseUrl: String, sessionId: String) = "draft:$baseUrl:$sessionId"

/**
 * The open chat. [chat] is owned by the home screen so an approval stays alive on Needs you
 * when this screen is not the current page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    api: ZeroClawApi,
    conn: GatewayConnection,
    session: GatewaySession,
    chat: ChatSession,
    store: KeyValueStore,
    work: CoroutineScope,
    advanced: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onOpenPage: (String) -> Unit,
    onRenameChat: (String) -> Unit,
    onAdvanced: (Boolean) -> Unit,
) {
    val state by chat.state.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val key = remember(conn.baseUrl, session.sessionId) { draftKey(conn.baseUrl, session.sessionId) }
    var draft by remember(key) { mutableStateOf("") }
    var draftReady by remember(key) { mutableStateOf(false) }
    var pending by remember(key) { mutableStateOf<Pair<ByteArray, String>?>(null) }
    var attachError by remember(key) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var commandNote by remember { mutableStateOf<String?>(null) }
    var unknownText by remember { mutableStateOf<String?>(null) }
    var modelSeed by remember { mutableStateOf("") }
    var modelLabel by remember(conn.agentAlias) { mutableStateOf("Model") }
    var modelProvider by remember(conn.agentAlias) { mutableStateOf<String?>(null) }

    fun reloadModel() {
        work.launch {
            val agent = api.status(conn.agentAlias)
            val parsed = agent.body.getOrNull()?.let { StatusParser.parse(it) }
            if (agent.ok && parsed != null && parsed.model.isNotBlank()) {
                modelLabel = parsed.model
                modelProvider = parsed.modelProvider
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            attachError = "The image could not be read from this phone."
            return@rememberLauncherForActivityResult
        }
        if (bytes.size > ImageSniff.MAX_BYTES) {
            attachError = ImageSniff.TOO_BIG
            return@rememberLauncherForActivityResult
        }
        val mime = ImageSniff.mime(bytes)
        if (mime == null) {
            attachError = ImageSniff.REFUSAL
            return@rememberLauncherForActivityResult
        }
        pending = bytes to mime
        attachError = null
    }

    LaunchedEffect(key) {
        draft = store.get(key).orEmpty()
        draftReady = true
    }
    LaunchedEffect(conn.agentAlias) { reloadModel() }

    fun clearDraft() {
        draft = ""
        work.launch { store.set(key, "") }
    }

    fun runRoute(route: SlashRoute) {
        when (route) {
            SlashRoute.Help -> showHelp = true
            SlashRoute.NewChat -> onNewChat()
            SlashRoute.Abort -> work.launch { chat.abort() }
            SlashRoute.OpenModels -> showModels = true
            is SlashRoute.SetModel -> {
                modelSeed = route.model
                commandNote = "Review ${route.model} before it is written."
                showModels = true
            }
            SlashRoute.OpenSettings -> onOpenSettings()
            SlashRoute.OpenMenu -> onOpenDrawer()
            SlashRoute.Status -> work.launch {
                val agent = api.status(conn.agentAlias)
                val parsed = agent.body.getOrNull()?.let { StatusParser.parse(it) }
                commandNote = if (!agent.ok || parsed == null) {
                    apiMessage(agent.httpStatus, agent.body.getOrNull())
                } else {
                    reloadModel()
                    val provider = parsed.modelProvider?.let { " · $it" }.orEmpty()
                    "Agent ${conn.agentAlias}: ${parsed.model.ifBlank { "no model" }}$provider"
                }
            }
            is SlashRoute.Rename -> onRenameChat(route.name)
            is SlashRoute.OpenPage -> onOpenPage(route.page)
            is SlashRoute.SetAdvanced -> onAdvanced(route.advanced)
            is SlashRoute.NeedsArgument -> commandNote = route.hint
            SlashRoute.Unknown -> unknownText = draft.trim()
        }
    }

    fun submitComposer() {
        val command = if (pending == null) SlashCommand.parse(draft) else null
        if (command != null) {
            val route = Slash.route(command)
            if (route is SlashRoute.Unknown) {
                unknownText = draft.trim()
            } else {
                clearDraft()
                runRoute(route)
            }
            return
        }
        val text = draft
        val image = pending
        work.launch {
            sending = true
            try {
                var outgoing = text.trim()
                if (image != null) {
                    val uploaded = api.uploadImage(conn.agentAlias, image.first, image.second)
                    val marker = uploaded.getOrNull()?.marker
                    if (marker == null) {
                        attachError = uploaded.exceptionOrNull()?.message ?: "The image was not uploaded."
                        return@launch
                    }
                    outgoing = if (outgoing.isBlank()) marker else "$outgoing\n$marker"
                    pending = null
                }
                if (outgoing.isBlank()) return@launch
                clearDraft()
                chat.send(outgoing)
            } finally {
                sending = false
            }
        }
    }
    LaunchedEffect(draft, draftReady) {
        if (draftReady) store.set(key, draft)
    }
    LaunchedEffect(state.messages.size, state.streaming, state.tools.size) {
        val extra = if (state.running || state.streaming.isNotBlank()) 1 else 0
        val total = state.messages.size + extra
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    if (showModels) {
        ModelPickerDialog(api, conn, work, initialTyped = modelSeed, onDismiss = { showModels = false; modelSeed = "" }, onSaved = { saved ->
            modelLabel = saved
            commandNote = "Wrote the model for ${conn.agentAlias}. Reloading what the gateway reports."
            reloadModel()
        })
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Commands") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(Slash.helpText() + "\n\nAn unknown / line is not sent until you choose Send as a message.")
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
        )
    }
    unknownText?.let { text ->
        AlertDialog(
            onDismissRequest = { unknownText = null },
            title = { Text("Not a command") },
            text = { Text("“$text” is not a Kurobane command. Send it to the agent as a message?") },
            confirmButton = {
                TextButton(onClick = {
                    unknownText = null
                    clearDraft()
                    work.launch { chat.send(text) }
                }) { Text("Send as a message") }
            },
            dismissButton = { TextButton(onClick = { unknownText = null }) { Text("Keep editing") } },
        )
    }

    val slashRows = if (pending == null) Slash.suggestions(draft) else emptyList()
    val slashReady = pending == null && SlashCommand.parse(draft) != null

    Scaffold(
        containerColor = KurobanePalette.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            session.displayName,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = KurobanePalette.text,
                        )
                        Text(
                            modelLabel + (modelProvider?.let { " · $it" } ?: ""),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = KurobanePalette.textMuted,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open sidebar", tint = KurobanePalette.text)
                    }
                },
                actions = {
                    IconButton(onClick = { showModels = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Change model", tint = KurobanePalette.text)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = KurobanePalette.text)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Chat menu", tint = KurobanePalette.text)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("New chat") }, onClick = { menuOpen = false; onNewChat() })
                        DropdownMenuItem(text = { Text("Commands") }, onClick = { menuOpen = false; showHelp = true })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menuOpen = false
                            commandNote = "Type /rename and the new name."
                            draft = "/rename "
                        })
                        if (state.running) {
                            DropdownMenuItem(text = { Text("Stop reply") }, onClick = { menuOpen = false; work.launch { chat.abort() } })
                        }
                        DropdownMenuItem(text = { Text("Gateway status") }, onClick = { menuOpen = false; runRoute(SlashRoute.Status) })
                        DropdownMenuItem(text = { Text(if (advanced) "Simple mode" else "Advanced mode") }, onClick = {
                            menuOpen = false
                            onAdvanced(!advanced)
                        })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onOpenSettings() })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KurobanePalette.bg),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().imePadding().padding(padding)) {
            if (state.status.isNotBlank()) Text(
                state.status,
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
            )
            val frame = state.frame
            val err = state.error
            if (frame != null && !err.isNullOrBlank()) {
                ProblemCard(frameTitle(frame) + "\n" + err)
            } else if (!err.isNullOrBlank()) {
                Text(
                    err, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = KurobanePalette.danger, style = MaterialTheme.typography.bodySmall,
                )
            }
            state.pendingApproval?.let { ar ->
                ApprovalCard(ar.tool, ar.summary, ar.requestId) { decision ->
                    work.launch { chat.answerApproval(decision, ar.requestId) }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.messages.size) { i -> ChatBubble(state.messages[i]) }
                if (state.running || state.streaming.isNotBlank() || state.thinking.isNotBlank() || state.tools.isNotEmpty()) {
                    item {
                        ChatBubble(
                            ChatMessage(
                                "assistant",
                                state.streaming,
                                state.thinking,
                                state.tools,
                                BubbleState.Running,
                            ),
                        )
                    }
                }
            }
            attachError?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = KurobanePalette.danger, style = MaterialTheme.typography.bodySmall)
            }
            pending?.let { (bytes, _) ->
                AttachedPreview(bytes) { pending = null }
            }
            commandNote?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
            }
            if (slashRows.isNotEmpty()) {
                SlashSuggestionList(slashRows) { row ->
                    val command = SlashCommand.parse(row.text)
                    if (command != null && Slash.route(command) is SlashRoute.NeedsArgument) {
                        draft = row.text + " "
                    } else if (command != null && command.name == "model") {
                        clearDraft()
                        showModels = true
                    } else {
                        draft = row.text
                        clearDraft()
                        command?.let { runRoute(Slash.route(it)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (!draft.trimStart().startsWith("/")) draft = "/"
                }) { Text("Commands  /") }
                TextButton(onClick = { showModels = true }, modifier = Modifier.weight(1f)) {
                    Text("Model · $modelLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { picker.launch("image/*") }, enabled = !sending) {
                    Icon(Icons.Filled.Image, contentDescription = "Attach image", tint = KurobanePalette.text)
                }
                OutlinedTextField(
                    draft, { draft = it },
                    modifier = Modifier.weight(1f),
                    label = { Text(if (slashReady) "Command" else "Message") },
                    placeholder = { Text("Message, or / for commands") },
                    maxLines = 8,
                )
                Button(
                    onClick = { submitComposer() },
                    enabled = (draft.isNotBlank() || pending != null) && (!state.running || slashReady) && !sending,
                ) { Text(if (slashReady) "Run" else "Send") }
            }
        }
    }
}

private fun frameTitle(frame: ProblemFrame): String = when (frame) {
    ProblemFrame.Unreachable -> "Gateway unreachable"
    ProblemFrame.Unauthorized -> "Not signed in"
    ProblemFrame.Provider -> "Provider error"
}

@Composable
internal fun ApprovalCard(tool: String, summary: String, requestId: String, onDecision: (String) -> Unit) {
    var armed by remember(requestId) { mutableStateOf(true) }
    fun tap(decision: String) {
        if (!armed) return
        armed = false
        onDecision(decision)
    }
    Card(
        Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = KurobanePalette.surfaceRaised),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Needs you", style = MaterialTheme.typography.labelLarge, color = KurobanePalette.textMuted)
            Text(tool, style = MaterialTheme.typography.titleSmall, color = KurobanePalette.text)
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { tap("approve") }, enabled = armed) { Text("Approve") }
                TextButton(onClick = { tap("deny") }, enabled = armed) { Text("Deny") }
                TextButton(onClick = { tap("always") }, enabled = armed) { Text("Always") }
            }
            Text(ALWAYS_ASK_NOTE, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
        }
    }
}

@Composable
private fun AttachedPreview(bytes: ByteArray, onRemove: () -> Unit) {
    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "Attached image preview", modifier = Modifier.heightIn(max = 160.dp).fillMaxWidth())
        } else {
            Text("Image attached. Preview is not available for this file.", style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
        }
        TextButton(onClick = onRemove) { Text("Remove image") }
    }
}

@Composable
private fun ChatBubble(m: ChatMessage) {
    val isUser = m.role == "user"
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, if (isUser) 4.dp else 16.dp, 16.dp, if (isUser) 16.dp else 4.dp),
            color = if (isUser) KurobanePalette.accent else KurobanePalette.surface,
            contentColor = if (isUser) KurobanePalette.onAccent else KurobanePalette.text,
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isUser) BubbleLabel(m.bubble)
                SelectionContainer {
                    if (m.content.isBlank()) {
                        if (m.bubble == BubbleState.Running) Text("Running…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        MarkdownBlocks(m.content, isUser)
                    }
                }
                if (m.thinking.isNotBlank()) ExpandBlock("Thinking", m.thinking)
                m.tools.forEach { tool -> ToolBlock(tool) }
                if (!isUser && m.content.isNotBlank()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(m.content)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy reply", tint = KurobanePalette.textMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun BubbleLabel(state: BubbleState) {
    val label = when (state) {
        BubbleState.Running -> "Running"
        BubbleState.Error -> "Error"
        BubbleState.Cancelled -> "Cancelled"
        BubbleState.Complete -> return
    }
    val color = when (state) {
        BubbleState.Error -> KurobanePalette.danger
        BubbleState.Cancelled -> KurobanePalette.warning
        else -> KurobanePalette.textMuted
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun ExpandBlock(title: String, body: String) {
    var open by remember(title, body) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide $title" else title) }
    if (open) {
        SelectionContainer {
            Text(body, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.text2)
        }
    }
}

@Composable
private fun ToolBlock(tool: ToolTrace) {
    var open by remember(tool.id, tool.name) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) {
        Text(if (open) "Hide ${tool.name}" else tool.name)
    }
    if (open) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Arguments", style = MaterialTheme.typography.labelSmall, color = KurobanePalette.textMuted)
                Text(tool.args.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = KurobanePalette.text)
                Text("Result", style = MaterialTheme.typography.labelSmall, color = KurobanePalette.textMuted)
                Text(tool.result ?: "Still running", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = KurobanePalette.text)
            }
        }
    }
}

@Composable
private fun MarkdownBlocks(src: String, onAccent: Boolean) {
    val color = if (onAccent) KurobanePalette.onAccent else KurobanePalette.text
    val blocks = remember(src) { MarkdownParser.parse(src) }
    if (blocks.isEmpty()) {
        Text(src, style = MaterialTheme.typography.bodyMedium, color = color)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inline(block.text, onAccent),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    color = color,
                )
                is MdBlock.Paragraph -> Text(inline(block.text, onAccent), style = MaterialTheme.typography.bodyMedium, color = color)
                is MdBlock.Bullet -> Column {
                    block.items.forEach { item ->
                        Text(inline("• $item", onAccent), style = MaterialTheme.typography.bodyMedium, color = color)
                    }
                }
                is MdBlock.Numbered -> Column {
                    block.items.forEachIndexed { index, item ->
                        Text(inline("${index + 1}. $item", onAccent), style = MaterialTheme.typography.bodyMedium, color = color)
                    }
                }
                is MdBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun inline(text: String, onAccent: Boolean): AnnotatedString {
    val base = if (onAccent) KurobanePalette.onAccent else KurobanePalette.text
    val link = if (onAccent) KurobanePalette.onAccent else KurobanePalette.accent
    return buildAnnotatedString {
        MarkdownParser.inlines(text).forEach { span -> appendSpan(span, base, link) }
    }
}

private fun AnnotatedString.Builder.appendSpan(
    span: MdSpan,
    base: androidx.compose.ui.graphics.Color,
    link: androidx.compose.ui.graphics.Color,
) {
    val style = SpanStyle(
        color = if (span.href != null) link else base,
        fontWeight = if (span.bold) FontWeight.Bold else null,
        fontStyle = if (span.italic) FontStyle.Italic else null,
        fontFamily = if (span.code) FontFamily.Monospace else null,
    )
    val href = span.href
    if (href != null) {
        withLink(LinkAnnotation.Url(href, TextLinkStyles(style = style))) { append(span.text) }
    } else {
        withStyle(style) { append(span.text) }
    }
}
