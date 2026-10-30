package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.chat.ChatSession
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.sessions.GatewaySession
import dev.kurobanekotlin.core.sessions.SessionsParser
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID

@Composable
fun ChatListScreen(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<GatewaySession>>(emptyList()) }
    var selected by remember { mutableStateOf<GatewaySession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val r = api.sessions()
            val obj = r.body.getOrNull()
            if (obj != null) sessions = SessionsParser.parse(obj)
            else error = "HTTP ${r.httpStatus}: ${r.body.exceptionOrNull()?.message}"
        }
    }
    LaunchedEffect(conn) { refresh() }

    if (selected != null) {
        ChatScreen(
            http = http, conn = conn,
            session = selected!!,
            onBack = { selected = null; refresh() },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Kurobane — ${conn.name}", style = MaterialTheme.typography.titleLarge)
            Button(onClick = {
                val id = "kb-" + UUID.randomUUID().toString().take(12)
                selected = GatewaySession(id, "gw_$id", "", "", 0, conn.agentAlias, null)
            }) { Text("+ New chat") }
        }
        Text(conn.baseUrl + " · agent " + conn.agentAlias, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sessions, key = { it.sessionKey }) { s ->
                Card(Modifier.fillMaxWidth().clickable { selected = s }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("${s.messageCount} messages · ${s.lastActivity}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(http: HttpClient, conn: GatewayConnection, session: GatewaySession, onBack: () -> Unit) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    val chat = remember(session.sessionId) { ChatSession(http, api, conn, session.sessionId) }
    val scope = rememberCoroutineScope()
    val state by chat.state.collectAsState()
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(session.sessionId) { chat.loadHistory() }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("← Sessions") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { scope.launch { chat.abort() } }, enabled = state.running) { Text("Abort") }
            }
        }
        Text(session.displayName, style = MaterialTheme.typography.titleMedium)
        if (state.status.isNotBlank()) Text(state.status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        state.pendingApproval?.let { ar ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Approval requested: ${ar.tool}", style = MaterialTheme.typography.titleSmall)
                    Text(ar.summary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { scope.launch { chat.answerApproval("approve") } }) { Text("Approve") }
                        OutlinedButton(onClick = { scope.launch { chat.answerApproval("deny") } }) { Text("Deny") }
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.messages) { m ->
                val isUser = m.role == "user"
                Card(Modifier.fillMaxWidth()) {
                    Text(if (isUser) "You" else "ZeroClaw", Modifier.padding(12.dp, 12.dp, 12.dp, 0.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isUser) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                    Text(m.content, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.streaming.isNotBlank()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(state.streaming, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f), label = { Text("Message") })
            Button(
                onClick = { scope.launch { chat.send(draft).also { draft = "" } } },
                enabled = draft.isNotBlank() && !state.running,
            ) { Text("Send") }
        }
    }
}
