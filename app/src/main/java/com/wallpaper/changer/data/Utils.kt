package com.wallpaper.changer.data

import android.content.Context
import android.net.Uri
import java.io.File

object UriCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    
    fun exists(context: Context, path: String): Boolean {
        return cache.getOrPut(path) {
            existsUriDirect(context, path)
        }
    }
    
    private fun existsUriDirect(context: Context, path: String): Boolean {
        if (path.startsWith("content://")) {
            return try {
                val uri = Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        } else {
            val file = File(path)
            return file.exists() && file.isFile
        }
    }
    
    fun invalidate(path: String) {
        cache.remove(path)
    }
    
    fun clear() {
        cache.clear()
    }
}

fun existsUri(context: Context, path: String): Boolean {
    return UriCache.exists(context, path)
}
