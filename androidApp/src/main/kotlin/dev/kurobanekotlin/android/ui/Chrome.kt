package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.core.connection.GatewayErrors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun apiMessage(status: Int, body: JsonObject?): String {
    val detail = (body?.get("message") as? JsonPrimitive)?.contentOrNull
        ?: (body?.get("error") as? JsonPrimitive)?.contentOrNull
    if (!detail.isNullOrBlank() && detail.contains("provider", ignoreCase = true)) {
        return GatewayErrors.provider(detail)
    }
    return GatewayErrors.plain(status, detail)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageScaffold(
    title: String,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = KurobanePalette.bg,
        topBar = {
            TopAppBar(
                title = { Text(title, color = KurobanePalette.text) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open sidebar", tint = KurobanePalette.text)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = KurobanePalette.text)
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
internal fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ProblemCard(message: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = KurobanePalette.surfaceRaised),
    ) {
        Text(message, Modifier.padding(16.dp), color = KurobanePalette.danger, style = MaterialTheme.typography.bodyMedium)
    }
}
