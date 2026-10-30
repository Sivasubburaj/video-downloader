package io.github.sivasubburaj.videodownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Quality choice → yt-dlp format sort, as in the desktop app. */
val QUALITIES = linkedMapOf(
    "Best available" to "res",
    "1080p" to "res:1080",
    "720p" to "res:720",
    "480p" to "res:480",
    "360p" to "res:360",
)
val THEMES = listOf("System", "Light", "Dark")

/** The user's choices, remembered between runs. */
object Settings {
    private lateinit var prefs: SharedPreferences
    private val _theme = MutableStateFlow("System")
    val theme: StateFlow<String> = _theme

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _theme.value = prefs.getString("theme", null)?.takeIf { it in THEMES } ?: "System"
    }

    fun setTheme(value: String) {
        _theme.value = value
        prefs.edit { putString("theme", value) }
    }

    var audio: Boolean
        get() = prefs.getBoolean("audio", false)
        set(value) = prefs.edit { putBoolean("audio", value) }

    var quality: String
        get() = prefs.getString("quality", null)?.takeIf { it in QUALITIES } ?: "Best available"
        set(value) = prefs.edit { putString("quality", value) }

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0)
        set(value) = prefs.edit { putLong("last_update_check", value) }
}

fun formatBytes(bytes: Double?): String {
    if (bytes == null || bytes <= 0) return ""
    var n: Double = bytes
    for (unit in listOf("B", "KB", "MB", "GB")) {
        if (n < 1024 || unit == "GB") {
            return if (unit == "B") "%.0f B".format(Locale.getDefault(), n) else "%.1f %s".format(Locale.getDefault(), n, unit)
        }
        n /= 1024
    }
    return ""
}

fun formatDuration(seconds: Double?): String {
    if (seconds == null || seconds.isNaN() || seconds <= 0) return ""
    val total = Math.round(seconds).toInt()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/** The first web link in shared or pasted text, e.g. "Watch this! https://youtu.be/…". */
fun findLink(text: String): String? = LINK.find(text)?.value

fun isLink(text: String) = LINK.matches(text)
