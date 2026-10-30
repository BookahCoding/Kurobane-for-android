package dev.kurobanekotlin.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConnectScreen(
    onSave: (name: String, url: String, alias: String, code: String) -> Unit,
    status: String,
) {
    var name by remember { mutableStateOf("My ZeroClaw") }
    var url by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("default") }
    var code by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Kurobane", style = MaterialTheme.typography.headlineMedium)
        Text("Connect to your ZeroClaw gateway — e.g. http://100.64.0.2:42617 over Tailscale, or https://zeroclaw.tail1234.ts.net",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Gateway address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(alias, { alias = it }, label = { Text("Agent alias") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text("Pairing code") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSave(name, url, alias, code) }, enabled = url.isNotBlank() && code.isNotBlank()) {
            Text("Pair and connect")
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
    }
}
