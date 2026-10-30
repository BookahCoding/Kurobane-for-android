package dev.kurobanekotlin.android.ui

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.config.AgentsParser
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.gateway.GatewayStatus
import dev.kurobanekotlin.core.gateway.StatusParser
import dev.kurobanekotlin.core.sessions.GatewaySession
import dev.kurobanekotlin.core.sessions.SessionsParser
import dev.kurobanekotlin.core.sessions.formatLastActivity
import dev.kurobanekotlin.core.tools.GatewayTool
import dev.kurobanekotlin.core.tools.ToolsParser
import dev.kurobanekotlin.designsystem.ThemeCatalog
import dev.kurobanekotlin.designsystem.ThemeColors
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import java.util.UUID

/** The destinations the sidebar offers below the chat list. */
enum class Destination(val title: String) { Chat("Chat"), Dashboard("Dashboard"), Agents("Agents"), Tools("Tools"), Settings("Settings") }

/** The active palette; the app uses the catalog's first family until a theme picker exists. */
val KurobanePalette: ThemeColors = ThemeCatalog.all.first().light

/**
 * The signed-in home, in Herald's shape: the open chat (or picked page) fills the screen and a sessions
 * sidebar — a drawer on phones — carries the chat list and the other destinations. Surfaces are colored
 * from the Kurobane theme catalog rather than stock Material colors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroClawHome(http: HttpClient, conn: GatewayConnection, onForget: () -> Unit) {
    val drawer = rememberDrawerState(ModalDrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var destination by remember { mutableStateOf(Destination.Chat) }
    var openSession by remember { mutableStateOf<GatewaySession?>(null) }

    fun closeDrawer() {
        scope.launch { drawer.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContainerColor = KurobanePalette.surface,
        drawerContent = {
            SessionsSidebar(
                http = http, conn = conn,
                openSessionKey = openSession?.sessionKey,
                onNewChat = {
                    openSession = GatewaySession(
                        "kb-" + UUID.randomUUID().toString().take(12), "", "", "", 0, conn.agentAlias, null,
                    )
                    destination = Destination.Chat
                    closeDrawer()
                },
                onOpenSession = { s ->
                    openSession = s
                    destination = Destination.Chat
                    closeDrawer()
                },
                onPickDestination = { d ->
                    destination = d
                    if (d == Destination.Chat) openSession = openSession // keep the chat; only other pages replace it
                    closeDrawer()
                },
            )
        },
    ) {
        Surface(Modifier.fillMaxSize(), color = KurobanePalette.bg) {
            when (destination) {
                Destination.Chat -> {
                    val s = openSession
                    if (s == null) EmptyChatHome()
                    else ChatScreen(http, conn, s, onBack = { openSession = null })
                }
                Destination.Dashboard -> DashboardPage(onOpenDrawer = { scope.launch { drawer.open() } }) {
                    dashboardBody(http, conn)
                }
                Destination.Agents -> AgentsPage(onOpenDrawer = { scope.launch { drawer.open() } }) { agentsBody(http, conn) }
                Destination.Tools -> ToolsPage(onOpenDrawer = { scope.launch { drawer.open() } }) { toolsBody(http, conn) }
                Destination.Settings -> SettingsPage(onOpenDrawer = { scope.launch { drawer.open() } }) { settingsBody(conn, onForget) }
            }
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

/** The sidebar: app mark, gateway line, new chat, the session list, then the other destinations. */
@Composable
private fun SessionsSidebar(
    http: HttpClient,
    conn: GatewayConnection,
    openSessionKey: String?,
    onNewChat: () -> Unit,
    onOpenSession: (GatewaySession) -> Unit,
    onPickDestination: (Destination) -> Unit,
) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<GatewaySession>?>(null) }
    var renaming by remember { mutableStateOf<GatewaySession?>(null) }
    var deleting by remember { mutableStateOf<GatewaySession?>(null) }

    fun refresh() {
        scope.launch {
            val r = api.sessions()
            sessions = if (r.ok && r.body.getOrNull() != null) SessionsParser.parse(r.body.getOrNull()!!) else null
        }
    }
    LaunchedEffect(conn) { refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SidebarHeader(conn)
        Spacer(Modifier.height(12.dp))
        SidebarAction(Icons.Filled.Add, "New chat", KurobanePalette.accent, onNewChat)
        Spacer(Modifier.height(12.dp))
        Text("Chats", style = MaterialTheme.typography.labelMedium, color = KurobanePalette.textMuted)
        Spacer(Modifier.height(4.dp))
        val list = sessions
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        } else if (list.isEmpty()) {
            Text(
                "No chats yet",
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
            )
        } else {
            list.forEach { s ->
                SessionRow(
                    s,
                    open = s.sessionKey == openSessionKey,
                    onOpen = { onOpenSession(s) },
                    onRename = { renaming = s },
                    onDelete = { deleting = s },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Gateway", style = MaterialTheme.typography.labelMedium, color = KurobanePalette.textMuted)
        Spacer(Modifier.height(4.dp))
        SidebarLink(Icons.Filled.Insights, Destination.Dashboard.title) { onPickDestination(Destination.Dashboard) }
        SidebarLink(Icons.Filled.Menu, Destination.Agents.title) { onPickDestination(Destination.Agents) }
        SidebarLink(Icons.Filled.SmartToy, Destination.Tools.title) { onPickDestination(Destination.Tools) }
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
            scope.launch { api.deleteSession(s.sessionKey); refresh() }
            deleting = null
        })
    }
}

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
                maxLines = 1, overflow = TextOverflow.Ellipsis,
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
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatLastActivity(s.lastActivity),
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
            )
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

/** A themed page scaffold: a top bar with the sidebar button, content below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageScaffold(title: String, onOpenDrawer: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.material3.Scaffold(
        containerColor = KurobanePalette.bg,
        topBar = {
            TopAppBar(
                title = { Text(title, color = KurobanePalette.text) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open sidebar", tint = KurobanePalette.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KurobanePalette.bg),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}

@Composable
private fun DashboardPage(onOpenDrawer: () -> Unit, content: @Composable () -> Unit) =
    PageScaffold(Destination.Dashboard.title, onOpenDrawer, content)

@Composable
private fun AgentsPage(onOpenDrawer: () -> Unit, content: @Composable () -> Unit) =
    PageScaffold(Destination.Agents.title, onOpenDrawer, content)

@Composable
private fun ToolsPage(onOpenDrawer: () -> Unit, content: @Composable () -> Unit) =
    PageScaffold(Destination.Tools.title, onOpenDrawer, content)

@Composable
private fun SettingsPage(onOpenDrawer: () -> Unit, content: @Composable () -> Unit) =
    PageScaffold(Destination.Settings.title, onOpenDrawer, content)

@Composable
private fun dashboardBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var status by remember { mutableStateOf<GatewayStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(conn) {
        val r = api.status()
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) status = StatusParser.parse(obj)
        else error = "Could not load the gateway status (HTTP ${r.httpStatus})."
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { Text(it, color = KurobanePalette.danger) }
        val s = status
        if (s == null && error == null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        s?.let { st ->
            InfoCard("Gateway") {
                InfoRow("Version", st.version)
                InfoRow("Uptime", StatusParser.formatUptime(st.uptimeSeconds))
                InfoRow("Paired", if (st.paired) "yes" else "no")
                InfoRow("Memory backend", st.memoryBackend)
                InfoRow("Port", st.gatewayPort.toString())
            }
            InfoCard("Model") {
                InfoRow("Provider", st.modelProvider ?: "—")
                InfoRow("Model", st.model.ifBlank { "—" })
                st.temperature?.let { InfoRow("Temperature", it.toString()) }
            }
            if (st.channels.isNotEmpty()) InfoCard("Channels") {
                st.channels.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text) }
            }
        }
    }
}

@Composable
private fun agentsBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var agents by remember { mutableStateOf<List<dev.kurobanekotlin.core.config.AgentInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(conn) {
        val r = api.config()
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) agents = AgentsParser.parse(obj)
        else error = "Could not load the agent list (HTTP ${r.httpStatus})."
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { Text(it, color = KurobanePalette.danger) }
        if (agents.isEmpty() && error == null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        agents.forEach { a ->
            InfoCard(a.alias) {
                Text(
                    (if (a.enabled) "enabled" else "disabled") +
                        (if (a.modelProvider.isNotBlank()) " · ${a.modelProvider}" else "") +
                        (if (a.channels.isNotEmpty()) " · ${a.channels.joinToString(", ")}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = KurobanePalette.textMuted,
                )
            }
        }
    }
}

@Composable
private fun toolsBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var tools by remember { mutableStateOf<List<GatewayTool>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(conn) {
        val r = api.tools(conn.agentAlias)
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) tools = ToolsParser.parse(obj)
        else error = "Could not load the tool list (HTTP ${r.httpStatus})."
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { Text(it, color = KurobanePalette.danger) }
        if (tools.isEmpty() && error == null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        tools.forEach { t ->
            InfoCard(t.name) {
                Text(t.description, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
            }
        }
    }
}

@Composable
private fun settingsBody(conn: GatewayConnection, onForget: () -> Unit) {
    SettingsScreen(conn, onForget)
}

@Composable
private fun InfoCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = KurobanePalette.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = KurobanePalette.text)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.textMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
    }
}
