package io.github.sivasubburaj.videodownloader.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sivasubburaj.videodownloader.BuildConfig
import io.github.sivasubburaj.videodownloader.DownloadItem
import io.github.sivasubburaj.videodownloader.Downloads
import io.github.sivasubburaj.videodownloader.Engine
import io.github.sivasubburaj.videodownloader.MainViewModel
import io.github.sivasubburaj.videodownloader.Preview
import io.github.sivasubburaj.videodownloader.QUALITIES
import io.github.sivasubburaj.videodownloader.State
import io.github.sivasubburaj.videodownloader.Storage
import io.github.sivasubburaj.videodownloader.findLink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(model: MainViewModel, onSettings: () -> Unit) {
    val context = LocalContext.current
    val items by Downloads.items.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(model.message) {
        val text = model.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)  // clear it only afterwards: changing the key cancels this effect
        model.message = null
    }

    // Ask for notifications (progress in the background) and, before Android 10, storage access
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false) {
            model.message = "Allow storage access so videos can be saved to ${Storage.FOLDER_LABEL}."
        } else {
            model.download(context)
        }
    }
    val onDownload = {
        fun missing(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 33 && !model.askedForNotifications && missing(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT < 29 && missing(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (!model.hasLink() || needed.isEmpty()) {
            model.download(context)
        } else {
            model.askedForNotifications = true
            permissions.launch(needed.toTypedArray())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Video Downloader", fontWeight = FontWeight.SemiBold)
                        Text(
                            "YouTube Shorts · Instagram Reels · Facebook videos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LinkCard(model) }
            item { OptionsCard(model) }
            item {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Download", style = MaterialTheme.typography.titleMedium) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (items.any { it.finished }) TextButton(onClick = Downloads::clearFinished) { Text("Clear finished") }
                }
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        "Your downloads appear here. Tap a finished one to play it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(items, key = { it.id }) { DownloadRow(it) }
            }
            item { EngineLine() }
        }
    }
}

@Composable
private fun LinkCard(model: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    SectionCard(Modifier.fillMaxWidth()) {
        Text("Video link", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = model.url,
                onValueChange = model::changeUrl,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Paste or share a link") },
                trailingIcon = {
                    if (model.url.isNotEmpty()) {
                        IconButton(onClick = { model.changeUrl("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    focus.clearFocus()
                    model.lookUpNow()
                }),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isBlank()) {
                    model.message = "The clipboard is empty. Copy a video link first."
                } else {
                    focus.clearFocus()
                    model.useLink(findLink(text) ?: text.trim())
                }
            }) { Text("Paste") }
        }
        Spacer(Modifier.height(16.dp))
        PreviewRow(model.preview)
    }
}

@Composable
private fun PreviewRow(preview: Preview) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                preview is Preview.Ready && preview.thumbnail != null ->
                    Image(preview.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                preview is Preview.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = colors.outline, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        val (title, detail) = when (preview) {
            Preview.Empty -> "Paste a link to see the video here" to "Works with YouTube, Instagram, Facebook and many other sites."
            Preview.Loading -> "Looking up the video…" to ""
            is Preview.Ready -> preview.info.title to preview.info.details
            is Preview.Problem -> preview.title to preview.message
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (preview is Preview.Problem) colors.error else colors.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionsCard(model: MainViewModel) {
    SectionCard(Modifier.fillMaxWidth()) {
        Text("Save as", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !model.audio,
                onClick = { model.audio = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Video (MP4)") }
            SegmentedButton(
                selected = model.audio,
                onClick = { model.audio = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Audio (MP3)") }
        }
        if (!model.audio) {
            Spacer(Modifier.height(12.dp))
            var open by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = model.quality,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Quality") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    QUALITIES.keys.forEach { quality ->
                        DropdownMenuItem(text = { Text(quality) }, onClick = {
                            model.quality = quality
                            open = false
                        })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Saved to ${Storage.FOLDER_LABEL}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val play = item.uri?.let { uri -> { Storage.open(context, uri, item.mime ?: "video/mp4") } }
    SectionCard(Modifier.fillMaxWidth(), onClick = play) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                when (item.state) {
                    State.DONE -> Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = colors.primary)
                    State.FAILED -> Icon(Icons.Filled.Warning, contentDescription = "Failed", tint = colors.error, modifier = Modifier.size(20.dp))
                    State.CANCELLED -> Icon(Icons.Filled.Close, contentDescription = "Cancelled", tint = colors.outline, modifier = Modifier.size(20.dp))
                    State.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    State.WAITING -> Box(Modifier.size(16.dp).border(2.dp, colors.outline, CircleShape))  // queued
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.state == State.RUNNING) {
                    Spacer(Modifier.height(6.dp))
                    val progress = item.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    item.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.state == State.FAILED) colors.error else colors.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val uri = item.uri
                    if (uri != null) {
                        DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; play?.invoke() })
                        DropdownMenuItem(text = { Text("Share") }, onClick = {
                            menu = false
                            Storage.share(context, uri, item.mime ?: "video/mp4")
                        })
                    }
                    if (!item.finished) {
                        DropdownMenuItem(text = { Text("Cancel") }, onClick = { menu = false; Downloads.cancel(item.id) })
                    }
                    DropdownMenuItem(text = { Text("Copy link") }, onClick = {
                        menu = false
                        clipboard.setText(AnnotatedString(item.url))
                    })
                    DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; Downloads.remove(item.id) })
                }
            }
        }
    }
}

@Composable
private fun EngineLine() {
    val status by Engine.status.collectAsStateWithLifecycle()
    val version by Engine.version.collectAsStateWithLifecycle()
    val engine = when (status) {
        Engine.Status.STARTING -> "starting…"
        Engine.Status.UPDATING -> "updating…"
        Engine.Status.FAILED -> "couldn't start"
        Engine.Status.READY -> "yt-dlp $version"
    }
    Text(
        "Engine: $engine  ·  v${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
