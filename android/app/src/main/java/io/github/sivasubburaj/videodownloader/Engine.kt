package io.github.sivasubburaj.videodownloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val AUTO_UPDATE_MS = 3 * 24 * 60 * 60 * 1000L  // every 3 days, like the desktop app
private val SITE_NAMES = mapOf(
    "Youtube" to "YouTube", "YoutubeTab" to "YouTube", "FacebookReel" to "Facebook",
    "InstagramStory" to "Instagram", "TikTok" to "TikTok", "Twitter" to "X",
)
private val NETWORK_ERRORS = listOf(
    "getaddrinfo", "timed out", "connection refused", "connection reset", "network is unreachable",
    "failed to resolve", "no route to host", "unable to resolve host",
)

/** An error whose message is ready to show to the user. */
class FriendlyException(message: String) : Exception(message)

/** What yt-dlp found at a link, for the preview. */
class VideoInfo(val title: String, val details: String, val thumbnail: String?)

/**
 * yt-dlp, with the Python, FFmpeg and QuickJS that youtubedl-android bundles. Lookups and downloads
 * run side by side; an engine update waits for them to finish and holds new ones back while it runs.
 */
object Engine {
    enum class Status { STARTING, READY, UPDATING, FAILED }

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status
    private val _version = MutableStateFlow("")
    val version: StateFlow<String> = _version

    private val gate = ReentrantReadWriteLock()
    private val started = CountDownLatch(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            try {
                gate.write {
                    YoutubeDL.getInstance().init(app)  // unpacks Python on first run: a few seconds
                    FFmpeg.getInstance().init(app)
                }
                _status.value = Status.READY
            } catch (e: Exception) {
                _status.value = Status.FAILED
            } finally {
                started.countDown()
            }
            if (_status.value == Status.READY) {
                readVersion()
                if (System.currentTimeMillis() - Settings.lastUpdateCheck > AUTO_UPDATE_MS) update()
            }
        }
    }

    private fun ready() {
        if (_status.value == Status.STARTING) started.await(3, TimeUnit.MINUTES)
        if (_status.value == Status.FAILED) {
            throw FriendlyException("The download engine couldn't start. Close the app completely and open it again.")
        }
    }

    private fun readVersion() {
        _version.value = runCatching { exec(YoutubeDLRequest(emptyList()).addOption("--version")).out.trim() }
            .getOrDefault("")
    }

    /** Checks GitHub for a newer yt-dlp and installs it. Returns a message for the user. */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        try {
            ready()
            _status.value = Status.UPDATING
            val result = try {
                gate.write { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
            } finally {
                _status.value = Status.READY
            }
            Settings.lastUpdateCheck = System.currentTimeMillis()
            readVersion()
            if (result == YoutubeDL.UpdateStatus.DONE) "Updated to yt-dlp ${_version.value}"
            else "yt-dlp ${_version.value} is up to date"
        } catch (e: Exception) {
            "Couldn't check for updates. " + (e as? FriendlyException ?: FriendlyException(friendlyError(e.message.orEmpty()))).message
        }
    }

    /** Title, site, channel, length and size of the video at [url]. Throws [FriendlyException]. */
    fun lookup(url: String): VideoInfo {
        // The preview needs no playable streams, so skip YouTube's player code: solving its
        // JavaScript challenge in QuickJS takes ~20 s on a phone; without it a lookup takes ~8 s.
        val response = exec(
            request(url).addOption("-J").addOption("--no-playlist").addOption("--no-warnings")
                .addOption("--extractor-args", "youtube:player_skip=js"),
        )
        val info = runCatching { JSONObject(response.out) }.getOrNull()  // yt-dlp prints "null" when it found nothing
            ?: throw FriendlyException(friendlyError(response.err.ifBlank { response.out }))
        var title = info.text("title").ifBlank { info.text("id") }.ifBlank { "Untitled" }
        if (info.text("_type") == "playlist") title += " (${info.optJSONArray("entries")?.length() ?: 0} videos)"
        val site = info.text("extractor_key")
        val size = info.number("filesize") ?: info.number("filesize_approx")
        val details = listOf(
            SITE_NAMES[site] ?: site,
            info.text("uploader").ifBlank { info.text("channel") },
            formatDuration(info.number("duration")),
            size?.let { "~${formatBytes(it)}" }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString("  ·  ")
        return VideoInfo(title, details, info.text("thumbnail").ifBlank { null })
    }

    /** The yt-dlp command for one download, working inside [work] (the app's cache). */
    fun downloadRequest(item: DownloadItem, work: File): YoutubeDLRequest {
        val request = request(item.url)
            .addOption("--no-playlist").addOption("--newline").addOption("--no-simulate")
            .addOption("--no-quiet").addOption("--progress").addOption("--windows-filenames")
            .addOption("-P", "home:${File(work, "out")}").addOption("-P", "temp:${File(work, "tmp")}")
            .addOption("-o", "%(title).90B [%(id)s].%(ext)s")
            .addOption(
                "--progress-template",
                "download:[dl] %(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
                    "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s",
            )
            .addOption("--print", "before_dl:[title] %(title)s")
            .addOption("--print", "after_move:[file] %(filepath)s")
        if (item.audio) {
            request.addOption("-f", "ba/b").addOption("-x")
                .addOption("--audio-format", "mp3").addOption("--audio-quality", "0")
        } else {
            val sort = QUALITIES[item.quality] ?: "res"
            request.addOption("-S", "$sort,vcodec:h264,acodec:aac")
                .addOption("--merge-output-format", "mp4").addOption("--remux-video", "mp4")
        }
        return request
    }

    /**
     * Runs yt-dlp with the user's sign-ins. [processId] lets [cancel] stop it; [onLine] gets each
     * line it prints. Throws [FriendlyException], or [YoutubeDL.CanceledException] if cancelled.
     */
    fun exec(request: YoutubeDLRequest, processId: String? = null, onLine: ((String) -> Unit)? = null): YoutubeDLResponse {
        ready()
        val cookies = Logins.cookieFile(app)  // a fresh copy per run: yt-dlp rewrites it on exit
        cookies?.let { request.addOption("--cookies", it.absolutePath) }
        val callback = onLine?.let { handler -> { _: Float, _: Long, line: String -> handler(line) } }
        try {
            return gate.read { YoutubeDL.getInstance().execute(request, processId, false, callback) }
        } catch (e: YoutubeDLException) {
            throw FriendlyException(friendlyError(e.message.orEmpty()))
        } finally {
            cookies?.delete()
        }
    }

    fun cancel(processId: String) = YoutubeDL.getInstance().destroyProcessById(processId)

    private fun request(url: String) = YoutubeDLRequest(url).addOption("--encoding", "utf-8")
}

private fun JSONObject.text(name: String) = if (isNull(name)) "" else optString(name)

private fun JSONObject.number(name: String) = optDouble(name).takeUnless { it.isNaN() }

/** Turns yt-dlp's error output into something a non-technical person can act on. */
fun friendlyError(text: String): String {
    val errors = text.lines().filter { it.startsWith("ERROR:") }
    val low = errors.joinToString("\n").ifEmpty { text }.lowercase()
    return when {
        "private" in low && ("video" in low || "account" in low) ->
            "This video is private. If your account can see it, sign in under Settings → Logins and try again."
        listOf("login", "log in", "sign in", "cookies").any { it in low } ->
            "This video needs a login. Sign in to the site under Settings → Logins, then try again."
        "unsupported url" in low -> "That link isn't supported. Copy the link of the video itself and try again."
        "http error 404" in low -> "Nothing was found at this link. Check that it's the link of the video itself."
        listOf("not available", "removed", "unavailable").any { it in low } ->
            "This video isn't available (it may be deleted, private, or blocked in your country)."
        NETWORK_ERRORS.any { it in low } -> "Couldn't connect. Check your internet connection and try again."
        else -> (errors.lastOrNull() ?: text.trim().lines().lastOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown error")
            .removePrefix("ERROR: ").take(300)
    }
}
