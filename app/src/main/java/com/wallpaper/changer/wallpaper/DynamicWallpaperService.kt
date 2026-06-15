package com.wallpaper.changer.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.WallpaperManager
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import java.io.File
import android.net.Uri
import com.wallpaper.changer.WallpaperChangerApplication
import com.wallpaper.changer.data.existsUri
import com.wallpaper.changer.data.AppSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TapInfo(val time: Long, val fingers: Int)

class DynamicWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "DynamicWPService"
        const val ACTION_UPDATE_WALLPAPER = "com.wallpaper.changer.ACTION_UPDATE_WALLPAPER"
        const val EXTRA_WALLPAPER_PATH = "wallpaper_path"
        const val EXTRA_SCALING_MODE = "scaling_mode"
        
        const val ACTION_GESTURE = "com.wallpaper.changer.ACTION_GESTURE"
        const val EXTRA_GESTURE_TYPE = "gesture_type"
        
        const val ACTION_WALLPAPER_ERROR = "com.wallpaper.changer.ACTION_WALLPAPER_ERROR"
        const val EXTRA_ERROR_PATH = "error_path"
    }

    override fun onCreateEngine(): Engine {
        return WallpaperEngine()
    }

    inner class WallpaperEngine : Engine() {
        private var currentBitmap: Bitmap? = null
        private var currentPath: String? = null
        private var scalingMode: String = "Fill"
        private var transitionType: String = "Crossfade"
        private var decodedWidth: Int = -1
        private var decodedHeight: Int = -1
        
        // Gesture variables
        private var tapStartTime = 0L
        private var maxPointerCount = 1
        private var startX = 0f
        private var startY = 0f
        private var isGestureCancelled = false
        private var lastTapTime = 0L
        private var lastTouchTime = 0L
        private val commandTaps = ArrayList<Long>()
        private val commandTapsRunnable = Runnable {
            processCommandTaps()
        }
        private val handler = Handler(Looper.getMainLooper())
        
        private var previousBitmap: Bitmap? = null
        private var transitionAlpha = 1f
        private var animator: android.animation.ValueAnimator? = null

        private val recentTaps = ArrayList<TapInfo>()

        private var currentDestRect: Rect? = null
        private var previousDestRect: Rect? = null
        private val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        private val gestureTimeoutRunnable = Runnable {
            processRecentTaps()
        }

        private val wallpaperReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_UPDATE_WALLPAPER) {
                    val path = intent.getStringExtra(EXTRA_WALLPAPER_PATH)
                    val mode = intent.getStringExtra(EXTRA_SCALING_MODE) ?: "Fill"
                    Log.d(TAG, "Received wallpaper update: $path with mode: $mode")
                    if (path != null) {
                        updateWallpaper(path, mode)
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            
            val filter = IntentFilter(ACTION_UPDATE_WALLPAPER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wallpaperReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(wallpaperReceiver, filter)
            }
            
            // Ask for current wallpaper on start
            val requestIntent = Intent("com.wallpaper.changer.ACTION_REQUEST_CURRENT")
            sendBroadcast(requestIntent)

            // Load current wallpaper directly from database as backup (especially for preview mode)
            loadCurrentWallpaperFromDb()
        }

        override fun onDestroy() {
            super.onDestroy()
            try {
                unregisterReceiver(wallpaperReceiver)
            } catch (e: Exception) {
                // Ignore
            }
            handler.removeCallbacks(gestureTimeoutRunnable)
            handler.removeCallbacks(commandTapsRunnable)
            animator?.cancel()
            currentBitmap?.recycle()
            previousBitmap?.recycle()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                drawFrame()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val sizeChanged = decodedWidth != width || decodedHeight != height
            if (sizeChanged && currentPath != null) {
                updateWallpaper(currentPath!!, scalingMode, forceReload = true)
            } else {
                drawFrame()
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event == null) return

            lastTouchTime = System.currentTimeMillis()

            val density = resources.displayMetrics.density
            val threshold = 80f * density
            val thresholdSq = threshold * threshold

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapStartTime = System.currentTimeMillis()
                    maxPointerCount = event.pointerCount
                    startX = event.x
                    startY = event.y
                    isGestureCancelled = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointerCount = Math.max(maxPointerCount, event.pointerCount)
                }
                MotionEvent.ACTION_MOVE -> {
                    maxPointerCount = Math.max(maxPointerCount, event.pointerCount)
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (dx * dx + dy * dy > thresholdSq) {
                        isGestureCancelled = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    maxPointerCount = Math.max(maxPointerCount, event.pointerCount)
                    val duration = System.currentTimeMillis() - tapStartTime
                    if (!isGestureCancelled && duration < 300L) {
                        recentTaps.add(TapInfo(System.currentTimeMillis(), maxPointerCount))
                        handler.removeCallbacks(gestureTimeoutRunnable)
                        
                        val isMultiFinger = recentTaps.any { it.fingers > 1 }
                        if (recentTaps.size >= 3 || (isMultiFinger && recentTaps.size >= 2)) {
                            processRecentTaps()
                        } else {
                            handler.postDelayed(gestureTimeoutRunnable, 200) // 200ms window
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    isGestureCancelled = true
                }
            }
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                val now = System.currentTimeMillis()
                if (now - lastTouchTime > 1000L) {
                    // Touch events are not active, handle tap via COMMAND_TAP queue
                    commandTaps.add(now)
                    handler.removeCallbacks(commandTapsRunnable)
                    if (commandTaps.size >= 3) {
                        processCommandTaps()
                    } else {
                        handler.postDelayed(commandTapsRunnable, 200L) // 200ms window
                    }
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        private fun loadCurrentWallpaperFromDb() {
            val database = (application as WallpaperChangerApplication).database
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val activePathSetting = database.appSettingDao().getSetting("active_wallpaper_path")
                    val scalingModeSetting = database.appSettingDao().getSetting("active_wallpaper_scaling")
                    var path = activePathSetting?.value
                    val mode = scalingModeSetting?.value ?: "Fill"

                    if (path == null) {
                        // Fallback: get first photo from active albums
                        val activeIdsSetting = database.appSettingDao().getSetting("active_album_ids")
                        var activeIds = activeIdsSetting?.value?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
                        if (activeIds.isEmpty()) {
                            database.appSettingDao().getSetting("active_album_id")?.value?.toLongOrNull()?.let {
                                activeIds = listOf(it)
                            }
                        }
                        
                        var fallbackPath: String? = null
                        for (albumId in activeIds) {
                            val photos = database.photoDao().getPhotosForAlbum(albumId)
                            val found = photos.firstOrNull { it.isOnline || existsUri(this@DynamicWallpaperService, it.path) }?.path
                            if (found != null) {
                                fallbackPath = found
                                break
                            }
                        }
                        path = fallbackPath
                    }

                    if (path != null) {
                        val finalPath = path
                        withContext(Dispatchers.Main) {
                            updateWallpaper(finalPath, mode)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading wallpaper from DB on creation: ${e.message}")
                }
            }
        }

        private fun processRecentTaps() {
            val taps = recentTaps.size
            if (taps == 0) {
                recentTaps.clear()
                return
            }
            val fingers = recentTaps.maxOf { it.fingers }
            recentTaps.clear()

            Log.d(TAG, "Processed taps: count=$taps, fingers=$fingers")
            val gestureType = when {
                fingers == 1 && taps == 2 -> "DoubleTap"
                fingers == 2 && taps == 2 -> "TwoFingerDoubleTap"
                fingers == 3 && taps == 2 -> "ThreeFingerDoubleTap"
                else -> null
            }

            if (gestureType != null) {
                Log.d(TAG, "Broadcasting gesture: $gestureType")
                val intent = Intent(ACTION_GESTURE).apply {
                    putExtra(EXTRA_GESTURE_TYPE, gestureType)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        private fun processCommandTaps() {
            val taps = commandTaps.size
            commandTaps.clear()
            val gestureType = when (taps) {
                2 -> "DoubleTap"
                else -> null
            }
            if (gestureType != null) {
                Log.d(TAG, "Broadcasting command gesture: $gestureType")
                val intent = Intent(ACTION_GESTURE).apply {
                    putExtra(EXTRA_GESTURE_TYPE, gestureType)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
        }

        private fun updateWallpaper(path: String, mode: String, forceReload: Boolean = false) {
            val database = (application as WallpaperChangerApplication).database
            val lastDecodedWidth = decodedWidth
            val lastDecodedHeight = decodedHeight
            val lastCurrentPath = currentPath
            val hasCurrentBitmap = currentBitmap != null

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val surfaceFrame = surfaceHolder.surfaceFrame
                    val displayMetrics = resources.displayMetrics
                    val reqWidth = if (surfaceFrame.width() > 0) surfaceFrame.width() else displayMetrics.widthPixels
                    val reqHeight = if (surfaceFrame.height() > 0) surfaceFrame.height() else displayMetrics.heightPixels

                    val sizeMatches = reqWidth == lastDecodedWidth && reqHeight == lastDecodedHeight
                    if (!forceReload && lastCurrentPath == path && hasCurrentBitmap && sizeMatches) {
                        withContext(Dispatchers.Main) {
                            val scalingChanged = scalingMode != mode
                            scalingMode = mode
                            if (scalingChanged) {
                                currentBitmap?.let {
                                    currentDestRect = getDestRect(it, reqWidth, reqHeight, mode)
                                }
                            }
                            drawFrame()
                        }
                        return@launch
                    }

                    if (!existsUri(this@DynamicWallpaperService, path)) {
                        Log.e(TAG, "Wallpaper file does not exist: $path")
                        reportError(path)
                        return@launch
                    }

                    // Decode bitmap safely to prevent OOM
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    
                    if (path.startsWith("content://")) {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, options)
                        }
                    } else {
                        BitmapFactory.decodeFile(path, options)
                    }
                    
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                    options.inJustDecodeBounds = false
                    
                    val bitmap = if (path.startsWith("content://")) {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, options)
                        }
                    } else {
                        BitmapFactory.decodeFile(path, options)
                    }

                    val speedSetting = try {
                        database.appSettingDao().getSetting("transition_speed")
                    } catch (e: Exception) {
                        null
                    }
                    val durationMs = speedSetting?.value?.toLongOrNull() ?: 800L

                    val typeSetting = try {
                        database.appSettingDao().getSetting("transition_type")
                    } catch (e: Exception) {
                        null
                    }
                    val currentTransitionType = typeSetting?.value ?: "Crossfade"

                    withContext(Dispatchers.Main) {
                        scalingMode = mode
                        transitionType = currentTransitionType
                        if (bitmap != null) {
                            val oldBitmap = currentBitmap
                            animator?.cancel()
                            
                            if (durationMs > 0 && oldBitmap != null) {
                                previousBitmap?.recycle()
                                previousBitmap = oldBitmap
                                previousDestRect = currentDestRect
                                transitionAlpha = 0f
                                
                                currentBitmap = bitmap
                                currentDestRect = getDestRect(bitmap, reqWidth, reqHeight, mode)
                                currentPath = path
                                decodedWidth = reqWidth
                                decodedHeight = reqHeight
                                
                                animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration = durationMs
                                    addUpdateListener { valueAnimator ->
                                        transitionAlpha = valueAnimator.animatedValue as Float
                                        drawFrame()
                                    }
                                    addListener(object : android.animation.AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: android.animation.Animator) {
                                            previousBitmap?.recycle()
                                            previousBitmap = null
                                            previousDestRect = null
                                            animator = null
                                        }
                                    })
                                    start()
                                }
                            } else {
                                oldBitmap?.recycle()
                                previousBitmap = null
                                previousDestRect = null
                                currentBitmap = bitmap
                                currentDestRect = getDestRect(bitmap, reqWidth, reqHeight, mode)
                                currentPath = path
                                decodedWidth = reqWidth
                                decodedHeight = reqHeight
                                transitionAlpha = 1f
                                drawFrame()
                            }
                        } else {
                            Log.e(TAG, "Failed to decode bitmap for path: $path")
                            reportError(path)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading wallpaper bitmap: ${e.message}")
                    reportError(path)
                }
            }
        }

        private fun reportError(path: String) {
            val intent = Intent(ACTION_WALLPAPER_ERROR).apply {
                putExtra(EXTRA_ERROR_PATH, path)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1

            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2

                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    draw(canvas)
                }
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        }

        private fun getDestRect(bitmap: Bitmap, canvasWidth: Int, canvasHeight: Int, mode: String): Rect {
            return when (mode) {
                "Stretch" -> {
                    Rect(0, 0, canvasWidth, canvasHeight)
                }
                "Fit" -> {
                    val scale = Math.min(
                        canvasWidth.toFloat() / bitmap.width,
                        canvasHeight.toFloat() / bitmap.height
                    )
                    val newWidth = (bitmap.width * scale).toInt()
                    val newHeight = (bitmap.height * scale).toInt()
                    val left = (canvasWidth - newWidth) / 2
                    val top = (canvasHeight - newHeight) / 2
                    Rect(left, top, left + newWidth, top + newHeight)
                }
                else -> { // "Fill" (Center Crop)
                    val scale = Math.max(
                        canvasWidth.toFloat() / bitmap.width,
                        canvasHeight.toFloat() / bitmap.height
                    )
                    val newWidth = (bitmap.width * scale).toInt()
                    val newHeight = (bitmap.height * scale).toInt()
                    val left = (canvasWidth - newWidth) / 2
                    val top = (canvasHeight - newHeight) / 2
                    Rect(left, top, left + newWidth, top + newHeight)
                }
            }
        }

        private fun draw(canvas: Canvas) {
            val bitmap = currentBitmap
            val prevBitmap = previousBitmap

            if (bitmap == null && prevBitmap == null) {
                canvas.drawColor(Color.parseColor("#81C784")) // Green branding fallback
                return
            }

            val canvasWidth = canvas.width
            val canvasHeight = canvas.height

            if (scalingMode == "Fit") {
                canvas.drawColor(Color.BLACK)
            }

            paint.alpha = 255 // Reset alpha default

            val curDest = currentDestRect ?: bitmap?.let { getDestRect(it, canvasWidth, canvasHeight, scalingMode) }
            val prevDest = previousDestRect ?: prevBitmap?.let { getDestRect(it, canvasWidth, canvasHeight, scalingMode) }

            when (transitionType) {
                "FadeToBlack" -> {
                    canvas.drawColor(Color.BLACK)
                    if (transitionAlpha < 0.5f && prevBitmap != null && prevDest != null) {
                        paint.alpha = ((1f - 2f * transitionAlpha) * 255).toInt().coerceIn(0, 255)
                        canvas.drawBitmap(prevBitmap, null, prevDest, paint)
                    } else if (transitionAlpha >= 0.5f && bitmap != null && curDest != null) {
                        paint.alpha = ((2f * (transitionAlpha - 0.5f)) * 255).toInt().coerceIn(0, 255)
                        canvas.drawBitmap(bitmap, null, curDest, paint)
                    }
                }
                "SlideLeft" -> {
                    if (prevBitmap != null && prevDest != null && transitionAlpha < 1f) {
                        canvas.save()
                        canvas.translate(-canvasWidth * transitionAlpha, 0f)
                        canvas.drawBitmap(prevBitmap, null, prevDest, paint)
                        canvas.restore()
                    }
                    if (bitmap != null && curDest != null) {
                        canvas.save()
                        if (prevBitmap != null && transitionAlpha < 1f) {
                            canvas.translate(canvasWidth * (1f - transitionAlpha), 0f)
                        }
                        canvas.drawBitmap(bitmap, null, curDest, paint)
                        canvas.restore()
                    }
                }
                "SlideRight" -> {
                    if (prevBitmap != null && prevDest != null && transitionAlpha < 1f) {
                        canvas.save()
                        canvas.translate(canvasWidth * transitionAlpha, 0f)
                        canvas.drawBitmap(prevBitmap, null, prevDest, paint)
                        canvas.restore()
                    }
                    if (bitmap != null && curDest != null) {
                        canvas.save()
                        if (prevBitmap != null && transitionAlpha < 1f) {
                            canvas.translate(-canvasWidth * (1f - transitionAlpha), 0f)
                        }
                        canvas.drawBitmap(bitmap, null, curDest, paint)
                        canvas.restore()
                    }
                }
                "SlideUp" -> {
                    if (prevBitmap != null && prevDest != null && transitionAlpha < 1f) {
                        canvas.save()
                        canvas.translate(0f, -canvasHeight * transitionAlpha)
                        canvas.drawBitmap(prevBitmap, null, prevDest, paint)
                        canvas.restore()
                    }
                    if (bitmap != null && curDest != null) {
                        canvas.save()
                        if (prevBitmap != null && transitionAlpha < 1f) {
                            canvas.translate(0f, canvasHeight * (1f - transitionAlpha))
                        }
                        canvas.drawBitmap(bitmap, null, curDest, paint)
                        canvas.restore()
                    }
                }
                "SlideDown" -> {
                    if (prevBitmap != null && prevDest != null && transitionAlpha < 1f) {
                        canvas.save()
                        canvas.translate(0f, canvasHeight * transitionAlpha)
                        canvas.drawBitmap(prevBitmap, null, prevDest, paint)
                        canvas.restore()
                    }
                    if (bitmap != null && curDest != null) {
                        canvas.save()
                        if (prevBitmap != null && transitionAlpha < 1f) {
                            canvas.translate(0f, -canvasHeight * (1f - transitionAlpha))
                        }
                        canvas.drawBitmap(bitmap, null, curDest, paint)
                        canvas.restore()
                    }
                }
                else -> { // "Crossfade" (Default)
                    if (prevBitmap != null && prevDest != null && transitionAlpha < 1f) {
                        paint.alpha = ((1f - transitionAlpha) * 255).toInt().coerceIn(0, 255)
                        canvas.drawBitmap(prevBitmap, null, prevDest, paint)
                    }
                    if (bitmap != null && curDest != null) {
                        if (prevBitmap != null && transitionAlpha < 1f) {
                            paint.alpha = (transitionAlpha * 255).toInt().coerceIn(0, 255)
                        } else {
                            paint.alpha = 255
                        }
                        canvas.drawBitmap(bitmap, null, curDest, paint)
                    }
                }
            }
        }
    }
}
