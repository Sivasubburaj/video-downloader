package io.github.sivasubburaj.videodownloader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_FINISHED, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        Engine.start(this)
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_FINISHED = "finished"
    }
}
