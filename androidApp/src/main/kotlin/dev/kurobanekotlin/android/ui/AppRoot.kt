package dev.kurobanekotlin.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.kurobanekotlin.core.auth.TokenStore
import dev.kurobanekotlin.core.config.AgentsParser
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.PairResult
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.gateway.GatewayUrl
import dev.kurobanekotlin.core.storage.KeyValueStore
import dev.kurobanekotlin.core.storage.SettingsKeys
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

@Serializable
data class SavedServer(val name: String, val baseUrl: String, val agentAlias: String)

/** Root of the app: connect screen until a gateway is paired, then the drawer-based home. */
@Composable
fun AppRoot(dark: Boolean) {
    val http: HttpClient = koinInject()
    val store: KeyValueStore = koinInject()
    val tokens: TokenStore = koinInject()
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var conn by remember { mutableStateOf<GatewayConnection?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val saved = store.get(SettingsKeys.Servers)
            ?.let { runCatching { Json.decodeFromString<SavedServer>(it) }.getOrNull() }
        val token = saved?.let { runCatching { tokens.get(it.baseUrl) }.getOrNull() }
        // A saved server without its token (cleared storage) must be paired again.
        if (saved != null && token != null) conn = GatewayConnection(saved.name, saved.baseUrl, saved.agentAlias, token)
        loaded = true
    }

    MaterialTheme {
        val c = conn
        when {
            !loaded -> Unit
            c == null -> ConnectScreen(
                busy = busy,
                status = status,
                onSave = { name, url, alias, code ->
                    scope.launch {
                        busy = true
                        status = "Pairing…"
                        try {
                            val normalized = GatewayUrl.normalize(url).getOrElse { e ->
                                status = e.message ?: "Invalid address"; return@launch
                            }
                            val probe = GatewayConnection(name.ifBlank { "My ZeroClaw" }, normalized, alias.trim().ifBlank { "default" })
                            when (val r = ZeroClawApi(http, probe).pair(code, "Kurobane on Android")) {
                                is PairResult.Error -> status = r.message
                                is PairResult.Ok -> {
                                    // The gateway has no default agent, so make sure the alias exists.
                                    val authed = probe.copy(token = r.token)
                                    val agents = ZeroClawApi(http, authed).config().body.getOrNull()
                                        ?.let { AgentsParser.parse(it) }.orEmpty()
                                    val resolved = AgentsParser.resolveAlias(agents, probe.agentAlias) ?: probe.agentAlias
                                    val full = authed.copy(agentAlias = resolved)
                                    tokens.set(normalized, r.token)
                                    store.set(SettingsKeys.Servers, Json.encodeToString(SavedServer(full.name, normalized, resolved)))
                                    conn = full
                                    status = ""
                                }
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            else -> ZeroClawHome(http, c, onForget = {
                scope.launch {
                    tokens.remove(c.baseUrl)
                    store.remove(SettingsKeys.Servers)
                    conn = null
                    status = ""
                }
            })
        }
    }
}
