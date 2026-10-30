package io.github.sivasubburaj.videodownloader

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

enum class State { WAITING, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadItem(
    val id: String,
    val url: String,
    val audio: Boolean,
    val quality: String,
    val title: String,
    val state: State = State.WAITING,
    val progress: Float? = null,  // 0..1, or null while unknown
    val status: String = "Waiting…",
    val uri: Uri? = null,
    val mime: String? = null,
) {
    val finished get() = state == State.DONE || state == State.FAILED || state == State.CANCELLED
}

/** The download list the screen shows, and the queue [DownloadService] works through one at a time. */
object Downloads {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items
    private val queue = LinkedBlockingQueue<String>()

    fun add(context: Context, url: String, audio: Boolean, quality: String, title: String?) {
        val item = DownloadItem(UUID.randomUUID().toString(), url, audio, quality, title ?: url)
        _items.update { listOf(item) + it }
        queue.put(item.id)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel(id: String) {
        if (get(id)?.finished != false) return
        update(id) { it.copy(state = State.CANCELLED, status = "Cancelled", progress = null) }
        Engine.cancel(id)
    }

    fun remove(id: String) {
        cancel(id)
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() = _items.update { list -> list.filterNot { it.finished } }

    fun get(id: String) = _items.value.find { it.id == id }

    internal fun next(): DownloadItem? {
        while (true) {
            val item = get(queue.poll() ?: return null)
            if (item?.state == State.WAITING) return item
        }
    }

    internal fun update(id: String, change: (DownloadItem) -> DownloadItem) =
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }

    /** Like [update], but leaves the item alone once it's no longer running (e.g. cancelled). */
    internal fun progress(id: String, change: (DownloadItem) -> DownloadItem) =
        update(id) { if (it.state == State.RUNNING) change(it) else it }
}

/** Runs queued downloads in the background, with a progress notification, then stops itself. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: kotlinx.coroutines.Job? = null
    @Volatile private var lastStartId = 0
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_ID, progressNotification("Starting…", null, null), type)
        synchronized(this) {
            if (worker?.isActive != true) worker = scope.launch { work() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun work() {
        while (true) {
            val startId = lastStartId  // read first, so a download added while draining isn't missed
            while (true) download(Downloads.next() ?: break)
            if (stopSelfResult(startId)) return  // false: someone started us again meanwhile
        }
    }

    private fun download(item: DownloadItem) {
        val id = item.id
        Downloads.update(id) { if (it.state == State.WAITING) it.copy(state = State.RUNNING, status = "Starting…") else it }
        if (Downloads.get(id)?.state != State.RUNNING) return
        val work = File(cacheDir, "jobs/$id").apply { deleteRecursively(); mkdirs() }
        var title = item.title
        var filepath: String? = null
        var part = 0
        var lastDone = 0.0
        notifyProgress(title, null, "Starting…")
        try {
            Engine.exec(Engine.downloadRequest(item, work), id) { line ->
                when {
                    line.startsWith("[dl] ") -> {
                        val fields = line.substring(5).split("|").map { it.toDoubleOrNull() }
                        if (fields.size != 5) return@exec
                        val (done, total, estimate, speed, eta) = fields
                        val size = total ?: estimate
                        if (done != null && done < lastDone) part++  // a new stream (e.g. audio after video)
                        lastDone = done ?: 0.0
                        if (size != null && size > 0) {
                            val fraction = ((done ?: 0.0) / size).coerceIn(0.0, 1.0).toFloat()
                            val percent = "${(fraction * 100).toInt()}%" + if (part > 0) " (part ${part + 1})" else ""
                            val status = listOfNotNull(
                                "Downloading $percent", formatBytes(size),
                                speed?.let { "${formatBytes(it)}/s" }, eta?.let { "${it.toInt()}s left" },
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                            Downloads.progress(id) { it.copy(progress = fraction, status = status) }
                            notifyProgress(title, fraction, status)
                        }
                    }
                    line.startsWith("[title] ") -> {
                        title = line.substring(8)
                        Downloads.progress(id) { it.copy(title = title) }
                    }
                    line.startsWith("[file] ") -> filepath = line.substring(7)
                    listOf("[Merger]", "[VideoRemuxer]", "[FixupM3u8]", "[FixupM4a]").any { line.startsWith(it) } ->
                        Downloads.progress(id) { it.copy(progress = null, status = "Finishing…") }
                    line.startsWith("[ExtractAudio]") ->
                        Downloads.progress(id) { it.copy(progress = null, status = "Converting to MP3…") }
                }
            }
            if (Downloads.get(id)?.state != State.RUNNING) return  // cancelled before yt-dlp started
            val file = filepath?.let(::File)?.takeIf { it.isFile }
                ?: throw FriendlyException("The download finished, but no file came out of it.")
            Downloads.progress(id) { it.copy(progress = null, status = "Saving…") }
            val mime = Storage.mimeOf(file)
            val uri = Storage.save(this, file, mime)
            Downloads.update(id) {
                it.copy(state = State.DONE, progress = 1f, status = "Saved to ${Storage.FOLDER_LABEL}", uri = uri, mime = mime)
            }
            notifyFinished(id, title, "Saved to ${Storage.FOLDER_LABEL}. Tap to play.", uri, mime)
        } catch (e: YoutubeDL.CanceledException) {
            // Downloads.cancel has already marked it
        } catch (e: Exception) {
            if (Downloads.get(id)?.state != State.RUNNING) return
            val message = (e as? FriendlyException)?.message ?: friendlyError(e.message.orEmpty())
            Downloads.update(id) { it.copy(state = State.FAILED, progress = null, status = message) }
            notifyFinished(id, title, "Download failed: $message", null, null)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(title: String, fraction: Float?, text: String?) =
        NotificationCompat.Builder(this, App.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()

    private fun notifyProgress(title: String, fraction: Float?, text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotified < 700 && fraction != null) return  // Android drops updates that come too fast
        lastNotified = now
        if (canNotify()) {
            getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification(title, fraction, text))
        }
    }

    private fun notifyFinished(id: String, title: String, text: String, uri: Uri?, mime: String?) {
        if (!canNotify()) return
        val tap = if (uri != null) {
            PendingIntent.getActivity(
                this, id.hashCode(),
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else openApp()
        val notification = NotificationCompat.Builder(this, App.CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        getSystemService(NotificationManager::class.java).notify(id.hashCode(), notification)
    }

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val PROGRESS_ID = 1
    }
}
