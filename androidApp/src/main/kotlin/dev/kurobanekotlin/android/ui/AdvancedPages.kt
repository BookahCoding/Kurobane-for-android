package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.catalog.CostSummaryInfo
import dev.kurobanekotlin.core.catalog.CronJobInfo
import dev.kurobanekotlin.core.catalog.DeviceInfo
import dev.kurobanekotlin.core.catalog.MemoryEntryInfo
import dev.kurobanekotlin.core.catalog.PersonalityFileInfo
import dev.kurobanekotlin.core.catalog.PluginInfo
import dev.kurobanekotlin.core.catalog.ReadModels
import dev.kurobanekotlin.core.catalog.SkillBundleInfo
import dev.kurobanekotlin.core.catalog.SkillInfo
import dev.kurobanekotlin.core.config.AgentInfo
import dev.kurobanekotlin.core.config.AgentsParser
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.gateway.GatewayStatus
import dev.kurobanekotlin.core.gateway.StatusParser
import dev.kurobanekotlin.core.tools.GatewayTool
import dev.kurobanekotlin.core.tools.ToolsParser
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun DashboardBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var status by remember { mutableStateOf<GatewayStatus?>(null) }
    var health by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val r = api.status()
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) status = StatusParser.parse(obj) else error = apiMessage(r.httpStatus, obj)
        val h = api.health()
        val hb = h.body.getOrNull()
        health = if (h.ok && hb != null) ReadModels.healthLine(hb) else apiMessage(h.httpStatus, hb)
        loading = false
    }
    PageColumn(loading, error) {
        val s = status ?: return@PageColumn
        health?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text) }
        InfoCard("Gateway") {
            InfoRow("Version", s.version.ifBlank { "—" })
            InfoRow("Uptime", StatusParser.formatUptime(s.uptimeSeconds))
            InfoRow("Paired", if (s.paired) "yes" else "no")
            InfoRow("Memory backend", s.memoryBackend.ifBlank { "—" })
            InfoRow("Port", s.gatewayPort.toString())
        }
        InfoCard("Process model") {
            InfoRow("Provider", s.modelProvider ?: "—")
            InfoRow("Model", s.model.ifBlank { "—" })
            Text(
                "From GET /api/status. This is the process default and can differ from the agent.",
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
            )
        }
    }
}

@Composable
internal fun AgentsBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var agents by remember { mutableStateOf<List<AgentInfo>>(emptyList()) }
    var process by remember { mutableStateOf("—") }
    var model by remember { mutableStateOf("—") }
    var fallback by remember { mutableStateOf("—") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val status = api.status()
        val statusBody = status.body.getOrNull()
        if (status.ok && statusBody != null) {
            val parsed = StatusParser.parse(statusBody)
            val provider = parsed.modelProvider ?: "—"
            process = "$provider · ${parsed.model.ifBlank { "—" }}"
        } else {
            error = apiMessage(status.httpStatus, statusBody)
        }
        val cfg = api.config()
        val cfgBody = cfg.body.getOrNull()
        val list = if (cfg.ok && cfgBody != null) AgentsParser.parse(cfgBody) else {
            error = error ?: apiMessage(cfg.httpStatus, cfgBody)
            emptyList()
        }
        agents = list
        val agent = list.firstOrNull { it.alias == conn.agentAlias }
        val propModel = readProp(api, "agents.${conn.agentAlias}.model")
        val propFallback = readProp(api, "agents.${conn.agentAlias}.fallback_models")
            ?: readProp(api, "agents.${conn.agentAlias}.fallback_model")
            ?: readProp(api, "agents.${conn.agentAlias}.model_fallback")
        model = agent?.model?.takeIf { it.isNotBlank() } ?: propModel ?: "not set"
        fallback = agent?.fallback?.takeIf { it.isNotBlank() } ?: propFallback ?: "not set"
        loading = false
    }
    PageColumn(loading, error) {
        InfoCard("Process model") {
            Text(process, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
            Text(
                "GET /api/status. The process default can differ from this agent.",
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
            )
        }
        InfoCard("This agent · ${conn.agentAlias}") {
            InfoRow("Model", model)
            InfoRow("Fallback", fallback)
            Text(
                "From the agent config and GET /api/config/prop.",
                style = MaterialTheme.typography.bodySmall,
                color = KurobanePalette.textMuted,
            )
        }
        agents.forEach { a ->
            InfoCard(a.alias) {
                Text(
                    (if (a.enabled) "enabled" else "disabled") +
                        (if (a.modelProvider.isNotBlank()) " · ${a.modelProvider}" else "") +
                        (if (a.model.isNotBlank()) " · ${a.model}" else "") +
                        (if (a.fallback.isNotBlank()) " · fallback ${a.fallback}" else "") +
                        (if (a.channels.isNotEmpty()) " · ${a.channels.joinToString(", ")}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = KurobanePalette.textMuted,
                )
            }
        }
    }
}

private suspend fun readProp(api: ZeroClawApi, path: String): String? {
    val r = api.configProp(path)
    val obj = r.body.getOrNull() ?: return null
    if (!r.ok) return null
    return ReadModels.propText(obj)
}

@Composable
internal fun ToolsBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var tools by remember { mutableStateOf<List<GatewayTool>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val r = api.tools(conn.agentAlias)
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) tools = ToolsParser.parse(obj) else error = apiMessage(r.httpStatus, obj)
        loading = false
    }
    PageColumn(loading, error) {
        if (!loading && error == null && tools.isEmpty()) EmptyLine("No tools for this agent.")
        tools.forEach { t ->
            InfoCard(t.name) {
                Text(t.description.ifBlank { "No description." }, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
            }
        }
    }
}

@Composable
internal fun SkillsBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var bundles by remember { mutableStateOf<List<SkillBundleInfo>>(emptyList()) }
    var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val b = api.skillBundles()
        val bb = b.body.getOrNull()
        if (b.ok && bb != null) bundles = ReadModels.bundles(bb) else error = apiMessage(b.httpStatus, bb)
        val s = api.agentSkills(conn.agentAlias)
        val sb = s.body.getOrNull()
        if (s.ok && sb != null) skills = ReadModels.skills(sb) else error = error ?: apiMessage(s.httpStatus, sb)
        loading = false
    }
    PageColumn(loading, error) {
        Text("Skills are read only on this phone.", style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
        if (!loading && error == null && bundles.isEmpty() && skills.isEmpty()) EmptyLine("No skills on this agent.")
        if (bundles.isNotEmpty()) InfoCard("Bundles") {
            bundles.forEach { Text(it.alias, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text) }
        }
        skills.forEach { sk ->
            InfoCard(sk.name) {
                if (sk.description.isNotBlank()) Text(sk.description, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
                if (sk.origin.isNotBlank()) Text(sk.origin, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
            }
        }
    }
}

@Composable
internal fun JobsBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<CronJobInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var drafting by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("0 9 * * *") }
    var prompt by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var shell by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var runs by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conn, reload) {
        loading = true
        error = null
        val r = api.cron()
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) jobs = ReadModels.cron(obj) else error = apiMessage(r.httpStatus, obj)
        loading = false
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirm") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        try {
                            action()
                            reload += 1
                        } catch (e: Exception) {
                            error = e.message ?: "The gateway refused the change."
                        }
                    }
                }) { Text("Write") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    runs?.let { text ->
        AlertDialog(
            onDismissRequest = { runs = null },
            title = { Text("Recent runs") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { runs = null }) { Text("Close") } },
        )
    }
    PageColumn(loading, error) {
        Text(
            "A new job is sent only after you confirm. A shell job is not pre-approved.",
            style = MaterialTheme.typography.bodySmall,
            color = KurobanePalette.textMuted,
        )
        if (!drafting) {
            Button(onClick = { drafting = true }) { Text("New scheduled job") }
        } else {
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(schedule, { schedule = it }, label = { Text("Cron expression") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { shell = !shell }) { Text(if (shell) "Switch to an agent prompt" else "Switch to a shell command") }
            if (shell) {
                OutlinedTextField(command, { command = it }, label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = {
                val expr = schedule.trim()
                val body = if (shell) command.trim() else prompt.trim()
                if (expr.isEmpty() || body.isEmpty()) {
                    error = "A schedule and a prompt or command are required."
                    return@Button
                }
                val kind = if (shell) "shell command" else "agent prompt"
                confirm = "Create a $kind job for ${conn.agentAlias} at $expr?" to {
                    val result = api.addCron(conn.agentAlias, expr, name.trim(), if (shell) null else body, if (shell) body else null)
                    if (!result.ok) error = apiMessage(result.httpStatus, result.body.getOrNull())
                    else drafting = false
                }
            }) { Text("Review job") }
        }
        if (!loading && error == null && jobs.isEmpty()) EmptyLine("No jobs on this gateway.")
        jobs.forEach { job ->
            InfoCard(job.name) {
                if (job.schedule.isNotBlank()) InfoRow("Schedule", job.schedule)
                if (job.kind.isNotBlank()) InfoRow("Kind", job.kind)
                InfoRow("Enabled", if (job.enabled) "yes" else "no")
                if (job.nextRun.isNotBlank()) InfoRow("Next", job.nextRun)
                if (job.lastStatus.isNotBlank()) InfoRow("Last", job.lastStatus)
                RowButtons {
                    TextButton(onClick = {
                        scope.launch {
                            val result = api.cronRuns(job.id)
                            val obj = result.body.getOrNull()
                            runs = if (!result.ok || obj == null) apiMessage(result.httpStatus, obj)
                            else formatRuns(obj)
                        }
                    }) { Text("Runs") }
                    TextButton(onClick = {
                        val next = !job.enabled
                        confirm = "Set this job to ${if (next) "enabled" else "paused"}?" to {
                            val result = api.setCronEnabled(job.id, next)
                            if (!result.ok) error = apiMessage(result.httpStatus, result.body.getOrNull())
                        }
                    }) { Text(if (job.enabled) "Pause" else "Resume") }
                    TextButton(onClick = {
                        confirm = "Delete this scheduled job from the gateway?" to {
                            val result = api.deleteCron(job.id)
                            if (!result.ok) error = apiMessage(result.httpStatus, result.body.getOrNull())
                        }
                    }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
internal fun MemoryBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var entries by remember { mutableStateOf<List<MemoryEntryInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val r = api.memory(conn.agentAlias)
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) entries = ReadModels.memory(obj) else error = apiMessage(r.httpStatus, obj)
        loading = false
    }
    PageColumn(loading, error) {
        Text("Memory is read only on this phone.", style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
        if (!loading && error == null && entries.isEmpty()) EmptyLine("No memory entries for this agent.")
        entries.forEach { entry ->
            InfoCard(entry.key) {
                if (entry.category.isNotBlank()) Text(entry.category, style = MaterialTheme.typography.labelSmall, color = KurobanePalette.textMuted)
                if (entry.content.isNotBlank()) Text(entry.content, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
            }
        }
    }
}

@Composable
internal fun UsageBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var cost by remember { mutableStateOf<CostSummaryInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val r = api.cost(conn.agentAlias)
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) cost = ReadModels.cost(obj) else error = apiMessage(r.httpStatus, obj)
        loading = false
    }
    PageColumn(loading, error) {
        val c = cost ?: return@PageColumn
        InfoCard("Usage") {
            Text(c.explain(), style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
            InfoRow("Requests", c.requestCount.toString())
        }
        c.byModel.forEach { m ->
            InfoCard(m.model.ifBlank { "model" }) {
                Text(
                    "${m.tokens} tokens" + if (m.unpricedTokens > 0) " (${m.unpricedTokens} unpriced)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KurobanePalette.text,
                )
            }
        }
    }
}

@Composable
internal fun PluginsBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var plugins by remember { mutableStateOf<List<PluginInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val r = api.plugins()
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) plugins = ReadModels.plugins(obj) else error = apiMessage(r.httpStatus, obj)
        loading = false
    }
    PageColumn(loading, error) {
        if (!loading && error == null && plugins.isEmpty()) EmptyLine("No plugins on this gateway.")
        plugins.forEach { p ->
            InfoCard(p.name) {
                if (p.version.isNotBlank()) InfoRow("Version", p.version)
                if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
            }
        }
    }
}

@Composable
internal fun PersonalityBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<PersonalityFileInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf("") }
    var editorMtime by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(conn) {
        loading = true
        val r = api.personality(conn.agentAlias)
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) files = ReadModels.personality(obj) else error = apiMessage(r.httpStatus, obj)
        loading = false
    }
    PageColumn(loading, error) {
        Text(
            "Opening a file loads it. Saving writes it back only after you confirm, and only for the gateway allowlist.",
            style = MaterialTheme.typography.bodySmall,
            color = KurobanePalette.textMuted,
        )
        if (!loading && error == null && files.isEmpty()) EmptyLine("No personality files were listed.")
        files.forEach { file ->
            val allowed = file.filename in PersonalityAllowlist
            InfoCard(file.filename) {
                InfoRow("On disk", if (file.exists) "present" else "missing")
                if (!allowed) {
                    Text("Not on the allowlist, so Kurobane will not open it.", style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
                } else {
                    TextButton(onClick = {
                        scope.launch {
                            val result = api.personalityFile(conn.agentAlias, file.filename)
                            val obj = result.body.getOrNull()
                            if (!result.ok || obj == null) error = apiMessage(result.httpStatus, obj)
                            else {
                                editing = file.filename
                                editor = (obj["content"] as? JsonPrimitive)?.content ?: ""
                                editorMtime = (obj["mtime_ms"] as? JsonPrimitive)?.content?.toLongOrNull()
                            }
                        }
                    }) { Text("Edit") }
                }
            }
        }
    }
    editing?.let { filename ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(filename) },
            text = {
                OutlinedTextField(editor, { editor = it }, modifier = Modifier.fillMaxWidth(), minLines = 6)
            },
            confirmButton = {
                TextButton(onClick = {
                    val body = editor
                    val mtime = editorMtime
                    editing = null
                    scope.launch {
                        val result = api.putPersonality(conn.agentAlias, filename, body, mtime)
                        if (!result.ok) error = apiMessage(result.httpStatus, result.body.getOrNull())
                    }
                }) { Text("Replace on gateway") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun DevicesBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(conn) {
        loading = true
        val r = api.devices()
        val obj = r.body.getOrNull()
        if (r.ok && obj != null) devices = ReadModels.devices(obj) else error = apiMessage(r.httpStatus, obj)
        loading = false
    }
    PageColumn(loading, error) {
        if (!loading && error == null && devices.isEmpty()) EmptyLine("No paired devices.")
        devices.forEach { d ->
            InfoCard(d.name) {
                InfoRow("Type", d.type.ifBlank { "—" })
            }
        }
    }
}

private val PersonalityAllowlist = setOf(
    "SOUL.md", "IDENTITY.md", "USER.md", "AGENTS.md", "TOOLS.md", "HEARTBEAT.md", "BOOTSTRAP.md", "MEMORY.md",
)

private val GatewayJson = Json { ignoreUnknownKeys = true }

@Composable
internal fun ConfigBody(http: HttpClient, conn: GatewayConnection) {
    val api = remember(conn) { ZeroClawApi(http, conn) }
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("One property at a time. A masked secret is refused.") }
    var confirm by remember { mutableStateOf<String?>(null) }
    confirm?.let { text ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirm") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    val dotted = path.trim()
                    scope.launch {
                        val parsed = runCatching { GatewayJson.parseToJsonElement(valueText) }.getOrElse {
                            kotlinx.serialization.json.JsonPrimitive(valueText)
                        }
                        val shown = parsed.toString()
                        if (shown.contains("****") || shown.contains("***")) {
                            status = "Refusing to write a masked secret back."
                            return@launch
                        }
                        val result = api.setConfigProp(dotted, parsed)
                        status = if (result.ok) "Saved $dotted." else apiMessage(result.httpStatus, result.body.getOrNull())
                    }
                }) { Text("Write") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    PageColumn(loading = false, error = null) {
        Text(status, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
        OutlinedTextField(path, { path = it }, label = { Text("Dotted path") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(valueText, { valueText = it }, label = { Text("JSON value") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val dotted = path.trim()
            if (dotted.isEmpty() || valueText.isBlank()) {
                status = "Enter a path and a value."
                return@Button
            }
            val modelNote = if (dotted.contains("model", ignoreCase = true)) " This path names a model or provider." else ""
            confirm = "Write $dotted on the gateway?$modelNote"
        }) { Text("Review change") }
    }
}

private fun formatRuns(obj: kotlinx.serialization.json.JsonObject): String {
    val runs = obj["runs"] as? JsonArray ?: return "No runs recorded for this job."
    if (runs.isEmpty()) return "No runs recorded for this job."
    return runs.take(12).joinToString("\n") { el ->
        val row = el.jsonObject
        val started = (row["started_at"] as? JsonPrimitive)?.content ?: ""
        val state = (row["status"] as? JsonPrimitive)?.content ?: ""
        val output = (row["output"] as? JsonPrimitive)?.content?.take(180).orEmpty()
        listOf(started, state, output).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

@Composable
private fun RowButtons(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun PageColumn(loading: Boolean, error: String?, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        error?.let { ProblemCard(it) }
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        content()
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.textMuted)
}
