package dev.kurobanekotlin.android.ui

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
import dev.kurobanekotlin.designsystem.ThemeCatalog
import dev.kurobanekotlin.core.storage.KeyValueStore
import dev.kurobanekotlin.core.storage.SettingsKeys
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

@Serializable
data class SavedServer(val name: String, val baseUrl: String, val agentAlias: String)

internal const val ALIAS_SUBSTITUTED =
    "The saved agent name was not enabled on this gateway, so kogarashi is selected on this phone."

/** Root of the app: connect screen until a gateway is paired, then the drawer-based home. */
@Composable
fun AppRoot(systemDark: Boolean) {
    val http: HttpClient = koinInject()
    val store: KeyValueStore = koinInject()
    val tokens: TokenStore = koinInject()
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var conn by remember { mutableStateOf<GatewayConnection?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var themeId by remember { mutableStateOf("washi-ink") }
    var appearance by remember { mutableStateOf(ThemeCatalog.SYSTEM) }
    var advanced by remember { mutableStateOf(false) }
    var aliasNote by remember { mutableStateOf<String?>(null) }

    suspend fun adopt(name: String, baseUrl: String, token: String, requested: String): GatewayConnection {
        val probe = GatewayConnection(name, baseUrl, requested, token)
        val config = ZeroClawApi(http, probe).config()
        val agents = if (config.ok) config.body.getOrNull()?.let { AgentsParser.parse(it) } else null
        if (agents == null) return probe
        val resolution = AgentsParser.resolve(agents, requested)
        if (resolution.substitutedKogarashi && resolution.alias == "kogarashi") {
            aliasNote = ALIAS_SUBSTITUTED
            val full = probe.copy(agentAlias = "kogarashi")
            store.set(SettingsKeys.Servers, Json.encodeToString(SavedServer(full.name, baseUrl, "kogarashi")))
            return full
        }
        return probe
    }

    LaunchedEffect(Unit) {
        themeId = store.get(SettingsKeys.ThemeId) ?: "washi-ink"
        appearance = store.get(SettingsKeys.Appearance) ?: ThemeCatalog.SYSTEM
        advanced = store.get(SettingsKeys.Experience) == "advanced"
        val saved = store.get(SettingsKeys.Servers)
            ?.let { runCatching { Json.decodeFromString<SavedServer>(it) }.getOrNull() }
        val token = saved?.let { runCatching { tokens.get(it.baseUrl) }.getOrNull() }
        if (saved != null && token != null) {
            conn = adopt(saved.name, saved.baseUrl, token, saved.agentAlias)
        }
        loaded = true
    }

    KurobaneTheme(themeId, appearance, systemDark) {
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
                            val requested = alias.trim().ifBlank { "kogarashi" }
                            val probe = GatewayConnection(name.ifBlank { "My ZeroClaw" }, normalized, requested)
                            when (val r = ZeroClawApi(http, probe).pair(code, "Kurobane on Android")) {
                                is PairResult.Error -> status = r.message
                                is PairResult.Ok -> {
                                    tokens.set(normalized, r.token)
                                    val full = adopt(probe.name, normalized, r.token, requested)
                                    if (full.agentAlias == requested) {
                                        store.set(
                                            SettingsKeys.Servers,
                                            Json.encodeToString(SavedServer(full.name, normalized, requested)),
                                        )
                                    }
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
            else -> ZeroClawHome(
                http = http,
                conn = c,
                advanced = advanced,
                appearance = appearance,
                themeId = themeId,
                aliasNote = aliasNote,
                onAliasNoteDismiss = { aliasNote = null },
                onAppearance = { value ->
                    appearance = value
                    scope.launch { store.set(SettingsKeys.Appearance, value) }
                },
                onTheme = { value ->
                    themeId = value
                    scope.launch { store.set(SettingsKeys.ThemeId, value) }
                },
                onAdvanced = { value ->
                    advanced = value
                    scope.launch { store.set(SettingsKeys.Experience, if (value) "advanced" else "simple") }
                },
                onForget = {
                    scope.launch {
                        tokens.remove(c.baseUrl)
                        store.remove(SettingsKeys.Servers)
                        conn = null
                        aliasNote = null
                        status = ""
                    }
                },
            )
        }
    }
}
