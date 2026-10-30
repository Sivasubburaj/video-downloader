package io.github.sivasubburaj.videodownloader

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sivasubburaj.videodownloader.ui.AppTheme
import io.github.sivasubburaj.videodownloader.ui.LoginScreen
import io.github.sivasubburaj.videodownloader.ui.MainScreen
import io.github.sivasubburaj.videodownloader.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by Settings.theme.collectAsState()
            val dark = when (theme) {
                "Light" -> false
                "Dark" -> true
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {  // status and navigation bar icons that suit the theme
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppTheme(dark) { Screens(model) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Share → Video Downloader" from another app: open with the link ready and look it up. */
    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val link = findLink(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        model.screen = Screen.Main
        if (link != null) model.useLink(link) else model.message = "That share didn't include a link."
    }
}

@Composable
private fun Screens(model: MainViewModel) {
    when (val screen = model.screen) {
        Screen.Main -> MainScreen(model, onSettings = { model.screen = Screen.Settings })
        Screen.Settings -> {
            BackHandler { model.screen = Screen.Main }
            SettingsScreen(onBack = { model.screen = Screen.Main }, onSignIn = { model.screen = Screen.Login(it.name) })
        }
        is Screen.Login -> {
            val done = {
                CookieManager.getInstance().flush()
                model.screen = Screen.Settings
            }
            BackHandler(onBack = done)
            LoginScreen(Logins.sites.first { it.name == screen.site }, onDone = done)
        }
    }
}

sealed interface Screen {
    data object Main : Screen
    data object Settings : Screen
    data class Login(val site: String) : Screen
}

sealed interface Preview {
    data object Empty : Preview
    data object Loading : Preview
    data class Ready(val url: String, val info: VideoInfo, val thumbnail: ImageBitmap?) : Preview
    data class Problem(val title: String, val message: String) : Preview
}

class MainViewModel : ViewModel() {
    var screen by mutableStateOf<Screen>(Screen.Main)
    var url by mutableStateOf("")
        private set
    var preview by mutableStateOf<Preview>(Preview.Empty)
        private set
    var audio by mutableStateOf(Settings.audio)
    var quality by mutableStateOf(Settings.quality)
    var message by mutableStateOf<String?>(null)  // shown once as a snackbar
    var askedForNotifications = false
    private var lookup: Job? = null

    /** Typing or editing the link: look it up once it stops changing. */
    fun changeUrl(text: String) {
        url = text
        lookup?.cancel()
        if (text.isBlank()) {
            preview = Preview.Empty
            return
        }
        lookup = viewModelScope.launch {
            delay(700)
            lookUp(text.trim())
        }
    }

    /** A pasted or shared link: look it up straight away. */
    fun useLink(link: String) {
        url = link
        lookUpNow()
    }

    fun lookUpNow() {
        lookup?.cancel()
        val link = url.trim()
        if (link.isEmpty()) return
        lookup = viewModelScope.launch { lookUp(link) }
    }

    private suspend fun lookUp(link: String) {
        if (!isLink(link)) {
            preview = Preview.Problem("That doesn't look like a link", "Video links start with https://")
            return
        }
        if ((preview as? Preview.Ready)?.url == link) return
        preview = Preview.Loading
        preview = withContext(Dispatchers.IO) {
            try {
                val info = Engine.lookup(link)
                Preview.Ready(link, info, info.thumbnail?.let(::loadThumbnail))
            } catch (e: FriendlyException) {
                Preview.Problem("Couldn't load this link", e.message.orEmpty())
            } catch (e: Exception) {
                Preview.Problem("Couldn't load this link", friendlyError(e.message.orEmpty()))
            }
        }
    }

    fun hasLink() = isLink(url.trim())

    fun download(context: Context) {
        val link = url.trim()
        if (!isLink(link)) {
            message = "Paste a video link first (it starts with https://)."
            return
        }
        Settings.audio = audio
        Settings.quality = quality
        val title = (preview as? Preview.Ready)?.takeIf { it.url == link }?.info?.title
        Downloads.add(context.applicationContext, link, audio, quality, title)
        lookup?.cancel()
        url = ""
        preview = Preview.Empty
    }
}

/** The thumbnail, scaled down to a sensible size for the preview. */
private fun loadThumbnail(url: String): ImageBitmap? = runCatching {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
    val bytes = connection.inputStream.use { it.readBytes() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()
