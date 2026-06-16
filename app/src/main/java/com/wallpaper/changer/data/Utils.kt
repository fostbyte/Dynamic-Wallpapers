package com.wallpaper.changer.data

import android.content.Context
import android.net.Uri
import java.io.File

fun existsUri(context: Context, path: String): Boolean {
    if (path.startsWith("content://")) {
        return try {
            val uri = Uri.parse(path)
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            val exists = cursor?.use { it.moveToFirst() } ?: false
            if (exists) {
                true
            } else {
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            }
        } catch (e: Exception) {
            try {
                val uri = Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            } catch (e2: Exception) {
                false
            }
        }
    } else {
        val file = File(path)
        return file.exists() && file.isFile
    }
}

fun formatTimeBySystem(time24h: String, context: Context): String {
    try {
        val parts = time24h.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return time24h
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return time24h
        
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
        }
        
        return if (android.text.format.DateFormat.is24HourFormat(context)) {
            String.format(java.util.Locale.getDefault(), "%02d:%02d", hour, minute)
        } else {
            val df = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            df.format(cal.time)
        }
    } catch (e: Exception) {
        return time24h
    }
}

