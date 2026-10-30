package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.auth.TokenStore
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.gateway.GatewayUrl
import dev.kurobanekotlin.core.network.HttpClientFactory
import dev.kurobanekotlin.core.sessions.SessionsParser
import dev.kurobanekotlin.core.storage.KeyValueStore
import dev.kurobanekotlin.core.storage.SettingsKeys
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

/** Root of the app: connect screen until a server is configured, then the session list + chat. */
@Composable
fun AppRoot(dark: Boolean) {
    val http: HttpClient = koinInject()
    val store: KeyValueStore = koinInject()
    val tokens: TokenStore = koinInject()
    val scope = rememberCoroutineScope()

    var conn by remember { mutableStateOf<GatewayConnection?>(null) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val raw = store.get(SettingsKeys.Servers)
        if (raw != null) {
            val saved = Json.decodeFromString<SavedServer>(raw)
            conn = GatewayConnection(saved.name, saved.baseUrl, saved.agentAlias, tokens.get(saved.baseUrl))
        }
    }

    MaterialTheme {
        if (conn == null) {
            ConnectScreen(
                onSave = { name, url, alias, code ->
                    scope.launch {
                        val normalized = GatewayUrl.normalize(url).getOrElse { e -> status = e.message ?: "Invalid"; return@launch }
                        val probe = GatewayConnection(name, normalized, alias)
                        val api = ZeroClawApi(http, probe)
                        when (val r = api.pair(code, "Kurobane on Android")) {
                            is dev.kurobanekotlin.core.connection.PairResult.Ok -> {
                                tokens.set(normalized, r.token)
                                val full = probe.copy(token = r.token)
                                store.set(SettingsKeys.Servers, Json.encodeToString(SavedServer(name, normalized, alias)))
                                conn = full
                                status = "Paired."
                            }
                            is dev.kurobanekotlin.core.connection.PairResult.Error -> status = r.message
                        }
                    }
                },
                status = status,
            )
        } else {
            ChatListScreen(http, conn!!)
        }
    }
}

@kotlinx.serialization.Serializable
data class SavedServer(val name: String, val baseUrl: String, val agentAlias: String)
