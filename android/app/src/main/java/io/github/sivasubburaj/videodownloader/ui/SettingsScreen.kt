package io.github.sivasubburaj.videodownloader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sivasubburaj.videodownloader.BuildConfig
import io.github.sivasubburaj.videodownloader.Engine
import io.github.sivasubburaj.videodownloader.Logins
import io.github.sivasubburaj.videodownloader.Settings
import io.github.sivasubburaj.videodownloader.THEMES
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSignIn: (Logins.Site) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ThemeCard()
            LoginsCard(onSignIn)
            EngineCard()
            AboutCard()
        }
    }
}

@Composable
private fun Muted(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ThemeCard() {
    val theme by Settings.theme.collectAsStateWithLifecycle()
    SectionCard(Modifier.fillMaxWidth()) {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Muted("\"System\" follows your phone's dark mode setting.")
        Column(Modifier.selectableGroup().padding(top = 4.dp)) {
            THEMES.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().height(44.dp)
                        .selectable(selected = theme == option, onClick = { Settings.setTheme(option) }, role = Role.RadioButton),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = theme == option, onClick = null)
                    Text(option, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun LoginsCard(onSignIn: (Logins.Site) -> Unit) {
    val context = LocalContext.current
    val signedIn = remember { mutableStateMapOf<String, Boolean>().apply { Logins.sites.forEach { put(it.name, it.signedIn()) } } }
    var imported by remember { mutableStateOf(Logins.importedFile(context).exists()) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Logins.import(context, uri)
            imported = Logins.importedFile(context).exists()
        }
    }
    SectionCard(Modifier.fillMaxWidth()) {
        Text("Logins", style = MaterialTheme.typography.titleSmall)
        Muted(
            "Some Instagram, Facebook and X posts can only be downloaded when you're signed in. " +
                "Sign in here once. The app only uses the login to fetch videos.",
        )
        Spacer(Modifier.height(8.dp))
        Logins.sites.forEach { site ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(site.name)
                    Muted(if (signedIn[site.name] == true) "Signed in" else "Not signed in")
                }
                if (signedIn[site.name] == true) {
                    OutlinedButton(onClick = {
                        Logins.signOut(site)
                        signedIn[site.name] = site.signedIn()
                    }) { Text("Sign out") }
                } else {
                    FilledTonalButton(onClick = { onSignIn(site) }) { Text("Sign in") }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("cookies.txt file")
                Muted(if (imported) "Imported. It's used for every download." else "For other sites: export one from a browser on your computer.")
            }
            if (imported) {
                OutlinedButton(onClick = {
                    Logins.importedFile(context).delete()
                    imported = false
                }) { Text("Remove") }
            } else {
                FilledTonalButton(onClick = { pickFile.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import") }
            }
        }
    }
}

@Composable
private fun EngineCard() {
    val status by Engine.status.collectAsStateWithLifecycle()
    val version by Engine.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    SectionCard(Modifier.fillMaxWidth()) {
        Text("Download engine", style = MaterialTheme.typography.titleSmall)
        Text(
            when (status) {
                Engine.Status.STARTING -> "Starting…"
                Engine.Status.UPDATING -> "Updating yt-dlp…"
                Engine.Status.FAILED -> "Couldn't start. Close the app completely and open it again."
                Engine.Status.READY -> "yt-dlp $version"
            },
        )
        Muted("Sites change often, so yt-dlp updates itself every few days. If downloads suddenly stop working, check for updates.")
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { scope.launch { result = Engine.update() } },
            enabled = status == Engine.Status.READY,
        ) { Text("Check for updates") }
        result?.let { Muted(it) }
    }
}

@Composable
private fun AboutCard() {
    SectionCard(Modifier.fillMaxWidth()) {
        Text("About", style = MaterialTheme.typography.titleSmall)
        Text("Video Downloader ${BuildConfig.VERSION_NAME} for Android")
        Muted(
            "Only download videos you own, have permission to use, or that are licensed for reuse. " +
                "YouTube, Instagram, Facebook and X are trademarks of their owners. This app isn't " +
                "affiliated with or endorsed by them.",
        )
        Muted("Uses yt-dlp (Unlicense), FFmpeg (GPLv3), Python and QuickJS via youtubedl-android (GPLv3).")
    }
}
