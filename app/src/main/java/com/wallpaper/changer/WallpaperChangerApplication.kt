package com.wallpaper.changer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.wallpaper.changer.data.AppDatabase

class WallpaperChangerApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Wallpaper Automation"
            val descriptionText = "Manages background rules and updates the device wallpaper."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel("wallpaper_automation_channel", name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
