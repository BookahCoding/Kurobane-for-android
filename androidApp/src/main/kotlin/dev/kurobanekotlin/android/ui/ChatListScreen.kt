package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.chat.ChatMessage
import dev.kurobanekotlin.core.chat.ChatSession
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.sessions.GatewaySession
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch

/**
 * The open chat: transcript as bubbles (user right, agent left), streamed tail, approval card,
 * and the composer. The back button returns to the empty home; chats are picked in the sidebar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(http: HttpClient, conn: GatewayConnection, session: GatewaySession, onBack: () -> Unit) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    val chat = remember(session.sessionId) { ChatSession(http, api, conn, session.sessionId) }
    val scope = rememberCoroutineScope()
    val state by chat.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(session.sessionId) { chat.loadHistory() }
    LaunchedEffect(state.messages.size, state.streaming) {
        val total = state.messages.size + if (state.streaming.isNotBlank()) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Scaffold(
        containerColor = KurobanePalette.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        session.displayName,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = KurobanePalette.text,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                            tint = KurobanePalette.text)
                    }
                },
                actions = {
                    if (state.running) TextButton(onClick = { scope.launch { chat.abort() } }) { Text("Abort") }
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
            state.error?.let {
                Text(
                    it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = KurobanePalette.danger, style = MaterialTheme.typography.bodySmall,
                )
            }

            state.pendingApproval?.let { ar ->
                Card(
                    Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = KurobanePalette.surfaceRaised),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Approval requested", style = MaterialTheme.typography.labelLarge,
                            color = KurobanePalette.textMuted)
                        Text(ar.tool, style = MaterialTheme.typography.titleSmall, color = KurobanePalette.text)
                        Text(ar.summary, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { scope.launch { chat.answerApproval("approve") } }) { Text("Approve") }
                            TextButton(onClick = { scope.launch { chat.answerApproval("always") } }) { Text("Always") }
                            TextButton(onClick = { scope.launch { chat.answerApproval("deny") } }) { Text("Deny") }
                        }
                    }
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.messages.size) { i -> ChatBubble(state.messages[i]) }
                if (state.streaming.isNotBlank()) {
                    item { ChatBubble(ChatMessage("assistant", state.streaming)) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    draft, { draft = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Message") },
                    maxLines = 5,
                )
                androidx.compose.material3.Button(
                    onClick = {
                        val text = draft
                        draft = ""
                        scope.launch { chat.send(text) }
                    },
                    enabled = draft.isNotBlank() && !state.running,
                ) { Text("Send") }
            }
        }
    }
}

/** One chat message: user bubbles on the right in the accent, agent replies on the left on a surface. */
@Composable
private fun ChatBubble(m: ChatMessage) {
    val isUser = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp, if (isUser) 4.dp else 16.dp, 16.dp, if (isUser) 16.dp else 4.dp),
            color = if (isUser) KurobanePalette.accent else KurobanePalette.surface,
            contentColor = if (isUser) KurobanePalette.onAccent else KurobanePalette.text,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(m.content, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
