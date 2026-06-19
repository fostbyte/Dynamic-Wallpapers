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
        
        private var doubleTapAction = "NextPhoto"
        private var twoFingerAction = "Freeze"
        private var threeFingerAction = "NextPhoto"

        // Call this in onCreate and whenever settings change
        private fun loadGestureSettings() {
            CoroutineScope(Dispatchers.IO).launch {
                val db = (application as WallpaperChangerApplication).database
                doubleTapAction = db.appSettingDao().getSetting("gesture_DoubleTap")?.value ?: "NextPhoto"
                twoFingerAction = db.appSettingDao().getSetting("gesture_TwoFingerDoubleTap")?.value ?: "Freeze"
                threeFingerAction = db.appSettingDao().getSetting("gesture_ThreeFingerDoubleTap")?.value ?: "NextPhoto"
            }
        }
        
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

        private var lastDimming = 0
        private var lastBlur = 0
        private var lastGreyscale = 0

        private val gestureTimeoutRunnable = Runnable {
            processRecentTaps()
        }

        private val wallpaperReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_UPDATE_WALLPAPER) {
                    val path = intent.getStringExtra(EXTRA_WALLPAPER_PATH)
                    val mode = intent.getStringExtra(EXTRA_SCALING_MODE) ?: "Fill"
                    val dimming = intent.getIntExtra("dimming_percent", 0)
                    val blur = intent.getIntExtra("blur_percent", 0)
                    val greyscale = intent.getIntExtra("greyscale_percent", 0)
                    Log.d(TAG, "Received wallpaper update: $path with mode: $mode, dimming: $dimming, blur: $blur, greyscale: $greyscale")
                    if (path != null) {
                        updateWallpaper(path, mode, dimming = dimming, blur = blur, greyscale = greyscale)
                    }
                    loadGestureSettings()
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
                Log.d(TAG, "Wallpaper visible, broadcasting action homescreen")
                val intent = Intent("com.wallpaper.changer.ACTION_HOMESCREEN").apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
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
                        
                        if (recentTaps.size >= 2) {
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
                    if (commandTaps.size >= 2) {
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
                        val dimming = if (database.appSettingDao().getSetting("global_dimming_enabled")?.value == "true") {
                            database.appSettingDao().getSetting("global_dimming_percent")?.value?.toIntOrNull() ?: 0
                        } else {
                            database.appSettingDao().getSetting("active_rule_dimming")?.value?.toIntOrNull() ?: 0
                        }
                        val blur = if (database.appSettingDao().getSetting("global_blur_enabled")?.value == "true") {
                            database.appSettingDao().getSetting("global_blur_percent")?.value?.toIntOrNull() ?: 0
                        } else {
                            database.appSettingDao().getSetting("active_rule_blur")?.value?.toIntOrNull() ?: 0
                        }
                        val greyscale = if (database.appSettingDao().getSetting("global_greyscale_enabled")?.value == "true") {
                            database.appSettingDao().getSetting("global_greyscale_percent")?.value?.toIntOrNull() ?: 0
                        } else {
                            database.appSettingDao().getSetting("active_rule_greyscale")?.value?.toIntOrNull() ?: 0
                        }
                        
                        withContext(Dispatchers.Main) {
                            updateWallpaper(finalPath, mode, dimming = dimming, blur = blur, greyscale = greyscale)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading wallpaper from DB on creation: ${e.message}")
                }
            }
        }

        private fun processRecentTaps() {
            val fingers = recentTaps.maxOf { it.fingers }
            recentTaps.clear()

            val gestureType = when (fingers) {
                1 -> "DoubleTap"
                2 -> "TwoFingerDoubleTap"
                3 -> "ThreeFingerDoubleTap"
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

        private fun updateWallpaper(
            path: String,
            mode: String,
            forceReload: Boolean = false,
            dimming: Int = 0,
            blur: Int = 0,
            greyscale: Int = 0
        ) {
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
                    val filtersChanged = lastDimming != dimming || lastBlur != blur || lastGreyscale != greyscale
                    if (!forceReload && lastCurrentPath == path && hasCurrentBitmap && sizeMatches && !filtersChanged) {
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

                    // Read bytes into memory once to prevent slow duplicate I/O and Binder calls
                    val bytes = try {
                        if (path.startsWith("content://")) {
                            val uri = Uri.parse(path)
                            contentResolver.openInputStream(uri)?.use { stream ->
                                stream.readBytes()
                            }
                        } else {
                            File(path).readBytes()
                        }
                    } catch (e: Exception) {
                        null
                    }

                    if (bytes == null || bytes.isEmpty()) {
                        Log.e(TAG, "Failed to read bytes for path: $path")
                        reportError(path)
                        return@launch
                    }

                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    
                    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                    options.inJustDecodeBounds = false
                    
                    var decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (decoded != null) {
                        if (greyscale > 0) {
                            val temp = applyGreyscale(decoded, greyscale)
                            decoded.recycle()
                            decoded = temp
                        }
                        if (blur > 0) {
                            val temp = applyBlur(decoded, blur)
                            decoded.recycle()
                            decoded = temp
                        }
                    }
                    val bitmap = decoded

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
                            
                            lastDimming = dimming
                            lastBlur = blur
                            lastGreyscale = greyscale

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

            if (lastDimming > 0) {
                val dimPaint = Paint().apply {
                    color = Color.BLACK
                    alpha = ((lastDimming / 100f) * 255).toInt().coerceIn(0, 255)
                }
                canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), dimPaint)
            }
        }

        private fun applyGreyscale(src: Bitmap, percent: Int): Bitmap {
            val dest = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(dest)
            val paint = Paint().apply {
                val matrix = android.graphics.ColorMatrix().apply {
                    setSaturation(1f - (percent / 100f))
                }
                colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            }
            canvas.drawBitmap(src, 0f, 0f, paint)
            return dest
        }

        private fun applyBlur(src: Bitmap, percent: Int): Bitmap {
            val radius = (percent * 15 / 100).coerceIn(1, 15)
            val scale = 0.2f
            val w = (src.width * scale).toInt().coerceAtLeast(10)
            val h = (src.height * scale).toInt().coerceAtLeast(10)
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val blurredSmall = boxBlur(small, radius)
            small.recycle()
            val result = Bitmap.createScaledBitmap(blurredSmall, src.width, src.height, true)
            blurredSmall.recycle()
            return result
        }

        private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
            val w = src.width
            val h = src.height
            val pix = IntArray(w * h)
            src.getPixels(pix, 0, w, 0, 0, w, h)
            
            val wm = w - 1
            val hm = h - 1
            val wh = w * h
            val div = radius + radius + 1
            val r = IntArray(wh)
            val g = IntArray(wh)
            val b = IntArray(wh)
            var rsum: Int
            var gsum: Int
            var bsum: Int
            var x: Int
            var y: Int
            var i: Int
            var p: Int
            var yp: Int
            var yi: Int
            var yw: Int
            val vmin = IntArray(Math.max(w, h))
            val dv = IntArray(256 * div)
            for (i in 0 until 256 * div) {
                dv[i] = i / div
            }
            yw = 0
            yi = 0
            y = 0
            while (y < h) {
                rsum = 0
                gsum = 0
                bsum = 0
                for (i in -radius..radius) {
                    p = pix[yi + Math.min(wm, Math.max(i, 0))]
                    rsum += p shr 16 and 0xff
                    gsum += p shr 8 and 0xff
                    bsum += p and 0xff
                }
                x = 0
                while (x < w) {
                    r[yi] = dv[rsum]
                    g[yi] = dv[gsum]
                    b[yi] = dv[bsum]
                    if (y == 0) {
                        vmin[x] = Math.min(x + radius + 1, wm)
                    }
                    val p1 = pix[yw + vmin[x]]
                    val p2 = pix[yw + Math.max(x - radius, 0)]
                    rsum += (p1 shr 16 and 0xff) - (p2 shr 16 and 0xff)
                    gsum += (p1 shr 8 and 0xff) - (p2 shr 8 and 0xff)
                    bsum += (p1 and 0xff) - (p2 and 0xff)
                    x++
                    yi++
                }
                yw += w
                y++
            }
            x = 0
            while (x < w) {
                rsum = 0
                gsum = 0
                bsum = 0
                yp = -radius * w
                for (i in -radius..radius) {
                    yi = Math.max(0, yp) + x
                    rsum += r[yi]
                    gsum += g[yi]
                    bsum += b[yi]
                    yp += w
                }
                yi = x
                y = 0
                while (y < h) {
                    pix[yi] = -0x1000000 or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                    if (x == 0) {
                        vmin[y] = Math.min(y + radius + 1, hm) * w
                    }
                    val p1 = x + vmin[y]
                    val p2 = x + Math.max(y - radius, 0) * w
                    rsum += r[p1] - r[p2]
                    gsum += g[p1] - g[p2]
                    bsum += b[p1] - b[p2]
                    yi += w
                    y++
                }
                x++
            }
            val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            dest.setPixels(pix, 0, w, 0, 0, w, h)
            return dest
        }
    }
}
