package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ConnectScreen(
    onSave: (name: String, url: String, alias: String, code: String) -> Unit,
    status: String,
    busy: Boolean = false,
) {
    var name by remember { mutableStateOf("My ZeroClaw") }
    var url by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("default") }
    var code by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = KurobanePalette.bg) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Kurobane", style = MaterialTheme.typography.headlineMedium, color = KurobanePalette.text)
        Text(
            "Connect to your ZeroClaw gateway, for example http://100.64.0.2:42617 over Tailscale, " +
                "or https://zeroclaw.tail1234.ts.net. Plain http:// is accepted only for Tailscale, private-network " +
                "and local addresses; anything else needs https://.",
            style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted,
        )
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            url, { url = it }, label = { Text("Gateway address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            alias, { alias = it }, label = { Text("Agent alias") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Must match an [agents.<alias>] entry; if not, the first enabled agent is used.") },
        )
        OutlinedTextField(code, { code = it }, label = { Text("Pairing code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(name, url, alias, code) }, enabled = url.isNotBlank() && code.isNotBlank() && !busy) {
            Text(if (busy) "Pairing…" else "Pair and connect")
        }
        if (status.isNotBlank()) Text(status, color = KurobanePalette.accent)
        Spacer(Modifier.height(8.dp))
    }
    }
}
