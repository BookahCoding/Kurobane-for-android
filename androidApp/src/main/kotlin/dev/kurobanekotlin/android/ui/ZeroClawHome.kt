package dev.kurobanekotlin.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.android.AppVisibility
import dev.kurobanekotlin.android.ApprovalNotifier
import dev.kurobanekotlin.core.catalog.ReadModels
import dev.kurobanekotlin.core.chat.ChatSession
import dev.kurobanekotlin.core.chat.ChatUiState
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.sessions.GatewaySession
import dev.kurobanekotlin.core.sessions.SessionsParser
import dev.kurobanekotlin.core.sessions.formatLastActivity
import dev.kurobanekotlin.core.storage.KeyValueStore
import dev.kurobanekotlin.core.storage.SettingsKeys
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject
import java.util.UUID

/** Sidebar destinations. Advanced pages stay hidden in Simple. */
enum class Destination(val title: String, val advanced: Boolean) {
    Chat("Chat", false),
    NeedsYou("Needs you", false),
    Dashboard("Dashboard", true),
    Agents("Agents", true),
    Tools("Tools", true),
    Skills("Skills", true),
    Jobs("Jobs", true),
    Memory("Memory", true),
    Usage("Usage", true),
    Plugins("Plugins", true),
    Personality("Personality", true),
    Config("Gateway config", true),
    Devices("Devices", true),
    Settings("Settings", false),
}

/**
 * Signed-in home. The open [ChatSession] stays above the current page so Needs you and the chat
 * share one approval, and leaving the chat does not drop the socket.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroClawHome(
    http: HttpClient,
    conn: GatewayConnection,
    advanced: Boolean,
    appearance: String,
    themeId: String,
    aliasNote: String?,
    onAliasNoteDismiss: () -> Unit,
    onAppearance: (String) -> Unit,
    onTheme: (String) -> Unit,
    onAdvanced: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val store: KeyValueStore = koinInject()
    val context = LocalContext.current
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var destination by remember { mutableStateOf(Destination.Chat) }
    var openSession by remember { mutableStateOf<GatewaySession?>(null) }
    var live by remember { mutableStateOf<Pair<GatewaySession, ChatSession>?>(null) }
    val idle = remember { MutableStateFlow(ChatUiState()) }
    val chatState by (live?.second?.state ?: idle).collectAsState()
    var notified by remember { mutableStateOf<String?>(null) }
    val resumed by AppVisibility.resumed.collectAsState()
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(advanced) {
        if (!advanced && destination.advanced) destination = Destination.Chat
    }
    val pending = chatState.pendingApproval
    LaunchedEffect(pending?.requestId, resumed) {
        val req = pending ?: return@LaunchedEffect
        if (!resumed && req.requestId != notified) {
            ApprovalNotifier.notify(context, req.tool, req.requestId)
            notified = req.requestId
        }
    }
    DisposableEffect(live?.second) {
        val session = live?.second
        onDispose { scope.launch { session?.close() } }
    }

    fun closeDrawer() {
        scope.launch { drawer.close() }
    }
    fun openDrawer() {
        scope.launch { drawer.open() }
    }
    fun select(session: GatewaySession) {
        val current = live
        if (current?.first?.sessionId != session.sessionId) {
            live = session to ChatSession(http, api, conn, session.sessionId)
            scope.launch { live?.second?.loadHistory() }
        }
        openSession = session
        destination = Destination.Chat
        closeDrawer()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = KurobanePalette.surfaceSidebar) {
                SessionsSidebar(
                    api = api,
                    conn = conn,
                    store = store,
                    advanced = advanced,
                    openSessionKey = openSession?.sessionKey,
                    pendingTool = pending?.tool,
                    pendingSummary = pending?.summary,
                    pendingId = pending?.requestId,
                    onAnswer = { decision, id -> scope.launch { live?.second?.answerApproval(decision, id) } },
                    onNewChat = {
                        select(
                            GatewaySession(
                                "kb-" + UUID.randomUUID().toString().take(12), "", "", "", 0, conn.agentAlias, null,
                            ),
                        )
                    },
                    onOpenSession = ::select,
                    onPickDestination = { d ->
                        destination = d
                        closeDrawer()
                    },
                    onSessionDeleted = { id ->
                        val cur = live?.first
                        if (cur != null && (cur.sessionKey == id || cur.sessionId == id)) {
                            openSession = null
                            live = null
                        }
                    },
                )
            }
        },
    ) {
        Surface(Modifier.fillMaxSize(), color = KurobanePalette.bg) {
            Column(Modifier.fillMaxSize()) {
                aliasNote?.let { note ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(note, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = KurobanePalette.text)
                        TextButton(onClick = onAliasNoteDismiss) { Text("Dismiss") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (destination) {
                        Destination.Chat -> {
                            val s = openSession
                            val chat = live?.second
                            if (s == null || chat == null) EmptyChatHome()
                            else ChatScreen(api, conn, s, chat, store, scope, onBack = { openSession = null })
                        }
                        Destination.NeedsYou -> PageScaffold(Destination.NeedsYou.title, ::openDrawer) {
                            NeedsYouPage(pending?.tool, pending?.summary, pending?.requestId) { decision, id ->
                                scope.launch { live?.second?.answerApproval(decision, id) }
                            }
                        }
                        Destination.Dashboard -> PageScaffold(Destination.Dashboard.title, ::openDrawer) { DashboardBody(http, conn) }
                        Destination.Agents -> PageScaffold(Destination.Agents.title, ::openDrawer) { AgentsBody(http, conn) }
                        Destination.Tools -> PageScaffold(Destination.Tools.title, ::openDrawer) { ToolsBody(http, conn) }
                        Destination.Skills -> PageScaffold(Destination.Skills.title, ::openDrawer) { SkillsBody(http, conn) }
                        Destination.Jobs -> PageScaffold(Destination.Jobs.title, ::openDrawer) { JobsBody(http, conn) }
                        Destination.Memory -> PageScaffold(Destination.Memory.title, ::openDrawer) { MemoryBody(http, conn) }
                        Destination.Usage -> PageScaffold(Destination.Usage.title, ::openDrawer) { UsageBody(http, conn) }
                        Destination.Plugins -> PageScaffold(Destination.Plugins.title, ::openDrawer) { PluginsBody(http, conn) }
                        Destination.Personality -> PageScaffold(Destination.Personality.title, ::openDrawer) { PersonalityBody(http, conn) }
                        Destination.Config -> PageScaffold(Destination.Config.title, ::openDrawer) { ConfigBody(http, conn) }
                        Destination.Devices -> PageScaffold(Destination.Devices.title, ::openDrawer) { DevicesBody(http, conn) }
                        Destination.Settings -> PageScaffold(Destination.Settings.title, ::openDrawer) {
                            SettingsScreen(
                                conn, onForget, appearance, themeId, advanced,
                                onAppearance, onTheme, onAdvanced,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedsYouPage(tool: String?, summary: String?, requestId: String?, onAnswer: (String, String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (tool == null || requestId == null) {
            Text("Nothing is waiting for you.", style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.textMuted)
        } else {
            ApprovalCard(tool, summary.orEmpty(), requestId) { decision -> onAnswer(decision, requestId) }
        }
    }
}

@Composable
private fun EmptyChatHome() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No chat open", style = MaterialTheme.typography.titleMedium, color = KurobanePalette.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Open the sidebar to pick a chat\nor start a new one.",
            style = MaterialTheme.typography.bodyMedium,
            color = KurobanePalette.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SessionsSidebar(
    api: ZeroClawApi,
    conn: GatewayConnection,
    store: KeyValueStore,
    advanced: Boolean,
    openSessionKey: String?,
    pendingTool: String?,
    pendingSummary: String?,
    pendingId: String?,
    onAnswer: (String, String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSession: (GatewaySession) -> Unit,
    onPickDestination: (Destination) -> Unit,
    onSessionDeleted: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<GatewaySession>?>(null) }
    var renaming by remember { mutableStateOf<GatewaySession?>(null) }
    var deleting by remember { mutableStateOf<GatewaySession?>(null) }
    var labeling by remember { mutableStateOf<GatewaySession?>(null) }
    var query by remember { mutableStateOf("") }
    var labels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var running by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refresh() {
        scope.launch {
            val r = api.sessions()
            sessions = if (r.ok && r.body.getOrNull() != null) SessionsParser.parse(r.body.getOrNull()!!) else null
            val live = api.runningSessions()
            val body = live.body.getOrNull()
            running = if (live.ok && body != null) ReadModels.runningIds(body) else emptySet()
        }
    }
    LaunchedEffect(conn) {
        labels = store.get(SettingsKeys.PhoneProjects)
            ?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty()
        refresh()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SidebarHeader(conn)
        Spacer(Modifier.height(12.dp))
        SidebarAction(Icons.Filled.Add, "New chat", KurobanePalette.accent, onNewChat)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search chats") },
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Text("Needs you", style = MaterialTheme.typography.labelMedium, color = KurobanePalette.textMuted)
        if (pendingTool != null && pendingId != null) {
            ApprovalCard(pendingTool, pendingSummary.orEmpty(), pendingId) { decision -> onAnswer(decision, pendingId) }
        } else {
            Text("Nothing is waiting.", style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
        }
        SidebarLink(Icons.Filled.Notifications, Destination.NeedsYou.title) { onPickDestination(Destination.NeedsYou) }
        Spacer(Modifier.height(12.dp))
        Text("Chats", style = MaterialTheme.typography.labelMedium, color = KurobanePalette.textMuted)
        Spacer(Modifier.height(4.dp))
        val list = sessions
        val shown = list?.let { rows ->
            val q = query.trim()
            if (q.isEmpty()) rows else rows.filter { s ->
                val phone = labels[labelKey(s)].orEmpty()
                s.displayName.contains(q, true) || s.sessionId.contains(q, true) || phone.contains(q, true)
            }
        }
        when {
            list == null -> Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
            list.isEmpty() -> Text("No chats yet", style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
            shown.isNullOrEmpty() -> Text("No chats match.", style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
            else -> shown.forEach { s ->
                SessionRow(
                    s,
                    open = s.sessionKey.isNotBlank() && s.sessionKey == openSessionKey,
                    phoneLabel = labels[labelKey(s)],
                    running = s.sessionId in running || s.sessionKey in running,
                    onOpen = { onOpenSession(s) },
                    onRename = { renaming = s },
                    onDelete = { deleting = s },
                    onLabel = { labeling = s },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(if (advanced) "Advanced" else "App", style = MaterialTheme.typography.labelMedium, color = KurobanePalette.textMuted)
        Spacer(Modifier.height(4.dp))
        if (advanced) {
            SidebarLink(Icons.Filled.Dashboard, Destination.Dashboard.title) { onPickDestination(Destination.Dashboard) }
            SidebarLink(Icons.Filled.SmartToy, Destination.Agents.title) { onPickDestination(Destination.Agents) }
            SidebarLink(Icons.Filled.Build, Destination.Tools.title) { onPickDestination(Destination.Tools) }
            SidebarLink(Icons.Filled.Menu, Destination.Skills.title) { onPickDestination(Destination.Skills) }
            SidebarLink(Icons.Filled.Schedule, Destination.Jobs.title) { onPickDestination(Destination.Jobs) }
            SidebarLink(Icons.Filled.Storage, Destination.Memory.title) { onPickDestination(Destination.Memory) }
            SidebarLink(Icons.Filled.Toll, Destination.Usage.title) { onPickDestination(Destination.Usage) }
            SidebarLink(Icons.Filled.Extension, Destination.Plugins.title) { onPickDestination(Destination.Plugins) }
            SidebarLink(Icons.Filled.Person, Destination.Personality.title) { onPickDestination(Destination.Personality) }
            SidebarLink(Icons.Filled.Tune, Destination.Config.title) { onPickDestination(Destination.Config) }
            SidebarLink(Icons.Filled.Devices, Destination.Devices.title) { onPickDestination(Destination.Devices) }
        }
        SidebarLink(Icons.Filled.Settings, Destination.Settings.title) { onPickDestination(Destination.Settings) }
    }

    renaming?.let { s ->
        RenameDialog(initial = s.name ?: "", onDismiss = { renaming = null }, onConfirm = { name ->
            scope.launch { api.renameSession(s.sessionKey, name.trim()); refresh() }
            renaming = null
        })
    }
    deleting?.let { s ->
        DeleteDialog(name = s.displayName, onDismiss = { deleting = null }, onConfirm = {
            val id = s.sessionKey.ifBlank { s.sessionId }
            scope.launch { api.deleteSession(s.sessionKey); refresh() }
            onSessionDeleted(id)
            deleting = null
        })
    }
    labeling?.let { s ->
        LabelDialog(
            initial = labels[labelKey(s)].orEmpty(),
            onDismiss = { labeling = null },
            onConfirm = { value ->
                val next = labels.toMutableMap()
                val key = labelKey(s)
                if (value.isBlank()) next.remove(key) else next[key] = value.trim()
                labels = next
                scope.launch { store.set(SettingsKeys.PhoneProjects, Json.encodeToString(next)) }
                labeling = null
            },
        )
    }
}

private fun labelKey(s: GatewaySession) = s.sessionKey.ifBlank { s.sessionId }

@Composable
private fun SidebarHeader(conn: GatewayConnection) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = KurobanePalette.accent) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Text("K", color = KurobanePalette.onAccent, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Kurobane", style = MaterialTheme.typography.titleMedium, color = KurobanePalette.text)
            Text(
                conn.name + " · " + conn.agentAlias,
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SidebarAction(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(10.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
    }
}

@Composable
private fun SessionRow(
    s: GatewaySession,
    open: Boolean,
    phoneLabel: String?,
    running: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onLabel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (open) KurobanePalette.surfaceRaised else KurobanePalette.surface)
            .clickable(onClick = onOpen)
            .padding(10.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                s.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = KurobanePalette.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!phoneLabel.isNullOrBlank()) {
                Text(phoneLabel, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("On this phone", style = MaterialTheme.typography.labelSmall, color = KurobanePalette.textMuted)
            }
            Text(
                (if (running) "Running · " else "") + formatLastActivity(s.lastActivity),
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
            )
        }
        IconButton(onClick = onLabel, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Label, contentDescription = "Label on this phone", tint = KurobanePalette.textMuted, modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = KurobanePalette.textMuted, modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = KurobanePalette.textMuted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SidebarLink(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(10.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = KurobanePalette.textMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete chat?") },
        text = { Text("\"$name\" and its transcript will be removed from the gateway.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabelDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label on this phone") },
        text = {
            Column {
                Text(
                    "On this phone. The gateway does not see this label.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KurobanePalette.textMuted,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Project") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
