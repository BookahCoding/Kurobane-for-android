package dev.kurobanekotlin.android.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.kurobanekotlin.android.BuildConfig
import dev.kurobanekotlin.android.update.ApkInstaller
import dev.kurobanekotlin.designsystem.ThemeCatalog
import dev.kurobanekotlin.core.connection.GatewayConnection
import dev.kurobanekotlin.core.update.ReleaseVersion
import dev.kurobanekotlin.core.update.UpdateAvailable
import dev.kurobanekotlin.core.update.UpdateCheckFailed
import dev.kurobanekotlin.core.update.UpdateChecker
import dev.kurobanekotlin.core.update.UpdateRelease
import dev.kurobanekotlin.core.update.UpdateUpToDate
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

/** Where the in-app update is: each state maps to one block of text and buttons in [UpdateCard]. */
private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val latest: ReleaseVersion) : UpdateUi
    data class Available(val release: UpdateRelease) : UpdateUi
    data class Downloading(val done: Long, val total: Long) : UpdateUi
    data class NeedsPermission(val release: UpdateRelease, val apk: File) : UpdateUi
    data class InstallStarted(val release: UpdateRelease, val apk: File) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

@Composable
fun SettingsScreen(
    conn: GatewayConnection,
    onForget: () -> Unit,
    appearance: String,
    themeId: String,
    advanced: Boolean,
    onAppearance: (String) -> Unit,
    onTheme: (String) -> Unit,
    onAdvanced: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = KurobanePalette.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Gateway", style = MaterialTheme.typography.titleMedium, color = KurobanePalette.text)
                Text("${conn.name}\n${conn.baseUrl}\nAgent: ${conn.agentAlias}", style = MaterialTheme.typography.bodyMedium, color = KurobanePalette.text)
                if (!confirmForget) {
                    OutlinedButton(onClick = { confirmForget = true }) { Text("Forget this gateway") }
                } else {
                    Text(
                        "This deletes the stored token from this phone. You will need a new pairing code to connect again.",
                        style = MaterialTheme.typography.bodySmall, color = KurobanePalette.danger,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onForget) { Text("Forget and unpair") }
                        OutlinedButton(onClick = { confirmForget = false }) { Text("Cancel") }
                    }
                }
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = KurobanePalette.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleMedium, color = KurobanePalette.text)
                listOf(ThemeCatalog.SYSTEM to "System", ThemeCatalog.LIGHT to "Light", ThemeCatalog.DARK to "Dark").forEach { (id, label) ->
                    OutlinedButton(onClick = { onAppearance(id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (appearance == id) "$label · on" else label)
                    }
                }
                Text("Theme", style = MaterialTheme.typography.titleSmall, color = KurobanePalette.text)
                ThemeCatalog.all.forEach { family ->
                    OutlinedButton(onClick = { onTheme(family.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (family.id == themeId) "${family.displayName} · on" else family.displayName)
                    }
                }
                Text(
                    "Text uses this phone's font so Greek and large text stay readable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KurobanePalette.textMuted,
                )
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = KurobanePalette.surface),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Experience", style = MaterialTheme.typography.titleMedium, color = KurobanePalette.text)
                Text(
                    if (advanced) {
                        "Advanced shows Dashboard, Agents, Tools, Skills, Jobs, Memory, Usage, Plugins, Personality, and Devices."
                    } else {
                        "Simple shows chat, sessions, and Needs you."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = KurobanePalette.textMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAdvanced(false) }) { Text(if (!advanced) "Simple · on" else "Simple") }
                    OutlinedButton(onClick = { onAdvanced(true) }) { Text(if (advanced) "Advanced · on" else "Advanced") }
                }
            }
        }

        UpdateCard()

        Text("Kurobane for Android ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
            color = KurobanePalette.textMuted)
    }
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val http: HttpClient = koinInject()
    val installer: ApkInstaller = koinInject()
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }

    val repo = BuildConfig.RELEASES_REPO
    val current = ReleaseVersion.parseCurrent(BuildConfig.VERSION_NAME)

    fun openInstaller(release: UpdateRelease, apk: File) {
        if (!installer.canRequestInstalls()) {
            ui = UpdateUi.NeedsPermission(release, apk)
            return
        }
        try {
            context.startActivity(installer.installIntent(apk))
            ui = UpdateUi.InstallStarted(release, apk)
        } catch (e: ActivityNotFoundException) {
            ui = UpdateUi.Failed("No app on this device can install packages.")
        }
    }

    fun check() {
        ui = UpdateUi.Checking
        scope.launch {
            ui = when (val r = UpdateChecker(http, repo, current!!).check()) {
                is UpdateAvailable -> UpdateUi.Available(r.release)
                is UpdateUpToDate -> UpdateUi.UpToDate(r.latest)
                is UpdateCheckFailed -> UpdateUi.Failed(r.message)
            }
        }
    }

    fun download(release: UpdateRelease) {
        ui = UpdateUi.Downloading(0, release.apkSize)
        scope.launch {
            installerResult(installer.download(release) { done, total -> ui = UpdateUi.Downloading(done, total) }, release,
                onReady = { apk -> openInstaller(release, apk) }, onFail = { ui = UpdateUi.Failed(it) })
        }
    }

    Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = KurobanePalette.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Text("Installed: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)

            if (repo.isEmpty() || current == null) {
                Text(
                    "Update checks are disabled in this build. Install a signed release from GitHub to get in-app updates.",
                    style = MaterialTheme.typography.bodySmall, color = KurobanePalette.textMuted,
                )
                return@Column
            }

            when (val s = ui) {
                UpdateUi.Idle -> Button(onClick = ::check) { Text("Check for updates") }
                UpdateUi.Checking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Checking GitHub…", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateUi.UpToDate -> {
                    Text("You are up to date (latest release ${s.latest.tag}).", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = ::check) { Text("Check again") }
                }
                is UpdateUi.Available -> {
                    Text("Version ${s.release.tag} is available" +
                        (if (s.release.apkSize > 0) " (%.1f MB)".format(s.release.apkSize / 1_048_576.0) else "") + ".",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { download(s.release) }) { Text("Download and install") }
                        OutlinedButton(onClick = ::check) { Text("Check again") }
                    }
                    Text("Android will ask you to confirm the installation.", style = MaterialTheme.typography.bodySmall,
                        color = KurobanePalette.textMuted)
                }
                is UpdateUi.Downloading -> {
                    if (s.total > 0) LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Downloading… ${s.done / 1024} KB", style = MaterialTheme.typography.bodySmall)
                }
                is UpdateUi.NeedsPermission -> {
                    Text(
                        "Android needs your permission before Kurobane can install updates. Open the setting, turn on " +
                            "\"Allow from this source\", press back, then tap Install.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { context.startActivity(installer.permissionIntent()) }) { Text("Open setting") }
                        OutlinedButton(onClick = { openInstaller(s.release, s.apk) }) { Text("Install") }
                    }
                }
                is UpdateUi.InstallStarted -> {
                    Text("Android's install screen was opened for ${s.release.tag}. If nothing appeared, tap Install.",
                        style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { openInstaller(s.release, s.apk) }) { Text("Install") }
                }
                is UpdateUi.Failed -> {
                    Text(s.message, color = KurobanePalette.danger, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = ::check) { Text("Try again") }
                }
            }
        }
    }
}

private inline fun installerResult(
    result: Result<File>, release: UpdateRelease, onReady: (File) -> Unit, onFail: (String) -> Unit,
) {
    result.fold(
        onSuccess = onReady,
        onFailure = { onFail(it.message ?: "The update for ${release.tag} could not be downloaded.") },
    )
}
