package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.chat.SlashSuggestion
import dev.kurobanekotlin.core.config.ModelChoice
import dev.kurobanekotlin.core.config.ModelChoices
import dev.kurobanekotlin.core.config.ModelPlan
import dev.kurobanekotlin.core.config.isMaskedSecret
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.connection.ZeroClawApi
import dev.kurobanekotlin.core.gateway.StatusParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun SlashSuggestionList(rows: List<SlashSuggestion>, onPick: (SlashSuggestion) -> Unit) {
    Surface(color = KurobanePalette.surfaceRaised, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { index, row ->
                val group = row.group
                if (group != rows.getOrNull(index - 1)?.group) {
                    Text(
                        group,
                        Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = KurobanePalette.textMuted,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(row) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(row.text, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
                    Text(
                        row.description,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = KurobanePalette.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Picks a model for the connected agent. A tap does not write. Confirm lists the exact config paths,
 * and a different provider is called out before those paths are written.
 */
@Composable
internal fun ModelPickerDialog(
    api: ZeroClawApi,
    conn: GatewayConnection,
    work: CoroutineScope,
    initialTyped: String = "",
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf<JsonObject?>(null) }
    var choices by remember { mutableStateOf<List<ModelChoice>>(emptyList()) }
    var currentModel by remember { mutableStateOf("") }
    var currentProvider by remember { mutableStateOf<String?>(null) }
    var processLine by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf(initialTyped) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ModelPlan?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(conn.agentAlias) {
        loading = true
        val cfg = api.config()
        val body = cfg.body.getOrNull()
        config = if (cfg.ok) body else null
        if (!cfg.ok) error = apiMessage(cfg.httpStatus, body)
        choices = body?.let { ModelChoices.fromConfig(it, conn.agentAlias) }.orEmpty()
        val agent = api.status(conn.agentAlias)
        val parsed = agent.body.getOrNull()?.let { StatusParser.parse(it) }
        if (agent.ok && parsed != null) {
            currentModel = parsed.model
            currentProvider = parsed.modelProvider
        }
        val process = api.status()
        val wide = process.body.getOrNull()?.let { StatusParser.parse(it) }
        processLine = wide?.takeIf { it.model.isNotBlank() && it.model != currentModel }
            ?.let { "Gateway process: ${it.model}" + (it.modelProvider?.let { p -> " · $p" } ?: "") + ". This pick does not change it." }
        loading = false
    }

    fun stage(choice: ModelChoice) {
        val cfg = config
        if (cfg == null) {
            error = "Gateway config did not load, so there is nothing to write."
            return
        }
        val plan = ModelChoices.plan(cfg, conn.agentAlias, currentProvider, choice)
        if (plan.refusal != null) error = plan.refusal else pending = plan
    }

    val plan = pending
    if (plan != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) pending = null },
            title = { Text(if (plan.changesProvider) "Change provider and model?" else "Change this agent's model?") },
            text = {
                Text(
                    buildString {
                        append("This writes the agent ")
                        append(conn.agentAlias)
                        append(" on the gateway, for every chat that uses it.")
                        if (plan.changesProvider) {
                            append(" The provider changes too. Kurobane leaves it alone unless you confirm.")
                        }
                        append("\n\n")
                        plan.writes.forEach { write ->
                            append(write.path)
                            append(" = ")
                            append(write.value)
                            append("\n")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        work.launch {
                            val failure = writePlan(api, plan)
                            saving = false
                            if (failure == null) {
                                pending = null
                                onSaved(plan.writes.last().value)
                                onDismiss()
                            } else {
                                pending = null
                                error = failure
                            }
                        }
                    },
                ) { Text(if (saving) "Writing…" else "Write") }
            },
            dismissButton = { TextButton(onClick = { pending = null }, enabled = !saving) { Text("Cancel") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "For agent ${conn.agentAlias}. A pick is not only this chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KurobanePalette.textMuted,
                )
                if (currentModel.isNotBlank()) {
                    Text(
                        currentModel + (currentProvider?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KurobanePalette.text,
                    )
                }
                processLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.danger) }
                if (loading) CircularProgressIndicator()
                choices.forEach { choice ->
                    TextButton(onClick = { stage(choice) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(choice.model, color = KurobanePalette.text)
                            Text(choice.label, style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted)
                        }
                    }
                }
                OutlinedTextField(
                    typed,
                    { typed = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Other model id") },
                    singleLine = true,
                )
                TextButton(onClick = { stage(ModelChoice(typed.trim(), null, "Typed")) }) { Text("Review typed model") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private suspend fun writePlan(api: ZeroClawApi, plan: ModelPlan): String? {
    for (write in plan.writes) {
        if (isMaskedSecret(write.value)) return "Refusing to write a masked secret."
        val result = api.setConfigProp(write.path, JsonPrimitive(write.value))
        if (!result.ok) return apiMessage(result.httpStatus, result.body.getOrNull())
    }
    return null
}
