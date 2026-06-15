package com.wallpaper.changer.automation

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.widget.Toast
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.wallpaper.changer.MainActivity
import com.wallpaper.changer.WallpaperChangerApplication
import com.wallpaper.changer.data.Album
import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.AppSetting
import com.wallpaper.changer.data.AutomationRule
import com.wallpaper.changer.data.Photo
import com.wallpaper.changer.wallpaper.DynamicWallpaperService
import kotlinx.coroutines.*
import java.io.File
import android.net.Uri
import com.wallpaper.changer.data.existsUri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AutomationService : Service() {

    companion object {
        private const val TAG = "AutomationService"
        private const val NOTIFICATION_ID = 8888
        private const val TICK_INTERVAL_MS = 30000L // 30 seconds
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var database: AppDatabase
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private var locationCallback: LocationCallback? = null
    private var lastLocation: Location? = null
    
    // Automation state
    private val handler = Handler(Looper.getMainLooper())
    private var lastCheckedMinute = -1
    private var activeRuleId: Long? = null
    
    // Set of triggered time rules for the current minute to avoid double triggering
    private val triggeredTimeRules = HashSet<Long>()
    
    private var lastGestureTime = 0L
    private var lastGestureType = ""
    
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DynamicWallpaperService.ACTION_GESTURE -> {
                    val gesture = intent.getStringExtra(DynamicWallpaperService.EXTRA_GESTURE_TYPE)
                    Log.d(TAG, "Received gesture broadcast: $gesture")
                    handleGesture(gesture)
                }
                DynamicWallpaperService.ACTION_WALLPAPER_ERROR -> {
                    val path = intent.getStringExtra(DynamicWallpaperService.EXTRA_ERROR_PATH)
                    if (path != null) {
                        handleWallpaperError(path)
                    }
                }
                "com.wallpaper.changer.ACTION_REQUEST_CURRENT" -> {
                    scope.launch {
                        broadcastCurrentWallpaper()
                    }
                }
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            evaluateTimeRules()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val lastTriggeredTimeMap = HashMap<Long, Long>()

    private val intervalRunnable = object : Runnable {
        override fun run() {
            evaluateIntervalRules()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating AutomationService...")
        database = (application as WallpaperChangerApplication).database
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Register receiver for gestures and errors
        val filter = IntentFilter().apply {
            addAction(DynamicWallpaperService.ACTION_GESTURE)
            addAction(DynamicWallpaperService.ACTION_WALLPAPER_ERROR)
            addAction("com.wallpaper.changer.ACTION_REQUEST_CURRENT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
        
        startForeground(NOTIFICATION_ID, createNotification("Running wallpaper automation..."))
        
        // Start time and interval rule evaluation loops
        handler.post(tickRunnable)
        handler.post(intervalRunnable)
        
        // Start location updates
        setupLocationTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying AutomationService...")
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(intervalRunnable)
        stopLocationTracking()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        job.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AutomationService started onStartCommand")
        val forceRotate = intent?.getBooleanExtra("force_rotate", false) == true
        val applyWallpaper = intent?.getBooleanExtra("apply_wallpaper", false) == true
        val prevPhoto = intent?.getBooleanExtra("prev_photo", false) == true
        // Force evaluation on command start
        scope.launch {
            evaluateLocationRules()
            
            val activeAlbumIds = getActiveAlbumIds()
            val activePath = database.appSettingDao().getSetting("active_wallpaper_path")?.value
            
            if (applyWallpaper && activePath != null) {
                broadcastCurrentWallpaper()
                applyStaticWallpaper(activePath)
            } else if (activeAlbumIds.isNotEmpty() && (activePath == null || forceRotate || prevPhoto)) {
                val isUserTriggered = forceRotate || prevPhoto
                rotateWallpaper(activeAlbumIds, isForward = !prevPhoto, updateStatic = isUserTriggered)
            } else {
                broadcastCurrentWallpaper()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(contentText: String): Notification {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            PendingIntent.getActivity(this, 0, notificationIntent, flags)
        }

        return NotificationCompat.Builder(this, "wallpaper_automation_channel")
            .setContentTitle("Dynamic Wallpaper Manager")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_gallery) // Fallback standard gallery icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    // --- Wallpaper selection & rotation logic ---

    private suspend fun broadcastCurrentWallpaper() {
        val activePathSetting = database.appSettingDao().getSetting("active_wallpaper_path")
        val scalingModeSetting = database.appSettingDao().getSetting("active_wallpaper_scaling")
        
        val path = activePathSetting?.value
        val mode = scalingModeSetting?.value ?: "Fill"
        
        if (path != null) {
            val updateIntent = Intent(DynamicWallpaperService.ACTION_UPDATE_WALLPAPER).apply {
                putExtra(DynamicWallpaperService.EXTRA_WALLPAPER_PATH, path)
                putExtra(DynamicWallpaperService.EXTRA_SCALING_MODE, mode)
                setPackage(packageName)
            }
            sendBroadcast(updateIntent)
        }
    }

    private fun handleWallpaperError(path: String) {
        Log.d(TAG, "Wallpaper engine reported error loading path: $path")
        scope.launch(Dispatchers.IO) {
            // Find photo and delete or mark it, or skip to next photo
            val allPhotos = database.photoDao().getAllPhotos()
            val failedPhoto = allPhotos.find { it.path == path }
            if (failedPhoto != null) {
                Log.d(TAG, "Silent skip: Moving to next photo after failure of photo ${failedPhoto.id}")
                rotateWallpaper(failedPhoto.albumId)
            }
        }
    }

    suspend fun getActiveAlbumIds(): List<Long> {
        val activeSetSetting = database.appSettingDao().getSetting("active_album_ids")
        val list = activeSetSetting?.value?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
        if (list.isNotEmpty()) return list
        val legacyActive = database.appSettingDao().getSetting("active_album_id")?.value?.toLongOrNull()
        return if (legacyActive != null) listOf(legacyActive) else emptyList()
    }

    suspend fun rotateWallpaper(albumIds: List<Long>, random: Boolean = false, isForward: Boolean = true, updateStatic: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (albumIds.isEmpty()) {
                Log.d(TAG, "No album IDs to rotate")
                return@withContext
            }
            
            val allPhotos = albumIds.flatMap { id -> database.photoDao().getPhotosForAlbum(id) }
            if (allPhotos.isEmpty()) {
                Log.d(TAG, "All target albums are empty, cannot rotate")
                return@withContext
            }

            val globalRandomSetting = database.appSettingDao().getSetting("global_random_order")
            val isGlobalRandom = globalRandomSetting?.value == "true"
            
            val albums = albumIds.mapNotNull { database.albumDao().getById(it) }
            val isAlbumRandom = albums.any { it.randomOrder }
            val shouldRandomize = random || isGlobalRandom || isAlbumRandom

            val currentPath = database.appSettingDao().getSetting("active_wallpaper_path")?.value
            val currentIndex = allPhotos.indexOfFirst { it.path == currentPath }

            var attempts = 0
            var chosenPhoto: Photo? = null
            var targetIndex = currentIndex

            while (attempts < allPhotos.size) {
                if (shouldRandomize) {
                    targetIndex = (0 until allPhotos.size).random()
                } else {
                    if (isForward) {
                        targetIndex = if (targetIndex == -1) 0 else (targetIndex + 1) % allPhotos.size
                    } else {
                        targetIndex = if (targetIndex == -1) allPhotos.size - 1 else (targetIndex - 1 + allPhotos.size) % allPhotos.size
                    }
                }
                
                val tempPhoto = allPhotos[targetIndex]
                if (tempPhoto.isOnline || existsUri(applicationContext, tempPhoto.path)) {
                    chosenPhoto = tempPhoto
                    break
                } else {
                    Log.d(TAG, "Silently skipping dead path: ${tempPhoto.path}")
                }
                attempts++
            }

            if (chosenPhoto != null) {
                database.appSettingDao().insertSetting(AppSetting("active_wallpaper_path", chosenPhoto.path))
                
                val photoAlbum = albums.find { it.id == chosenPhoto.albumId }
                val scaleMode = if (chosenPhoto.scalingOverride != "None") {
                    chosenPhoto.scalingOverride
                } else {
                    photoAlbum?.scalingMode ?: "Fill"
                }
                database.appSettingDao().insertSetting(AppSetting("active_wallpaper_scaling", scaleMode))
                
                Log.d(TAG, "New wallpaper chosen: ${chosenPhoto.path} with mode $scaleMode")
                
                broadcastCurrentWallpaper()
                if (updateStatic) {
                    applyStaticWallpaper(chosenPhoto.path)
                }
            } else {
                Log.d(TAG, "All photos in albums pool are invalid/missing!")
            }
        }
    }

    suspend fun rotateWallpaper(albumId: Long, random: Boolean = false, updateStatic: Boolean = false) {
        rotateWallpaper(listOf(albumId), random, true, updateStatic)
    }

    suspend fun rotateWallpaperBackwards(albumId: Long, updateStatic: Boolean = false) {
        rotateWallpaper(listOf(albumId), false, false, updateStatic)
    }

    private fun applyStaticWallpaper(path: String) {
        val wallpaperManager = WallpaperManager.getInstance(applicationContext)
        val isLiveActive = try {
            wallpaperManager.wallpaperInfo?.packageName == packageName
        } catch (e: Exception) {
            false
        }
        if (isLiveActive) {
            Log.d(TAG, "Live wallpaper is active. Skipping static wallpaper application to avoid deactivating the live wallpaper service.")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (existsUri(applicationContext, path)) {
                    val bitmap = if (path.startsWith("content://")) {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } else {
                        BitmapFactory.decodeFile(path)
                    } ?: return@launch
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                        } catch (e: Exception) {
                            wallpaperManager.setBitmap(bitmap)
                        }
                    } else {
                        wallpaperManager.setBitmap(bitmap)
                    }
                    Log.d(TAG, "Successfully updated static wallpaper for both screens")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying static wallpaper: ${e.message}")
            }
        }
    }

    // --- Automation Rule Engine Evaluators ---

    private fun evaluateTimeRules() {
        val calendar = Calendar.getInstance()
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentDayInt = calendar.get(Calendar.DAY_OF_WEEK) // Sunday = 1, Saturday = 7
        
        val dayOfWeekMap = mapOf(
            Calendar.SUNDAY to "Sun",
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat"
        )
        val currentDayStr = dayOfWeekMap[currentDayInt] ?: ""
        
        // Reset triggered cache if minute changes
        if (currentMinute != lastCheckedMinute) {
            triggeredTimeRules.clear()
            lastCheckedMinute = currentMinute
        }

        scope.launch {
            // If manual override is permanent, don't execute rules
            val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
            val isOverrideLocked = overrideLockSetting?.value == "true"
            if (isOverrideLocked) {
                return@launch
            }

            val rules = database.automationRuleDao().getAllRules()
            val timeRules = rules.filter { it.isEnabled && (it.type == "Time" || it.time != null) }
            
            val currentTimeStr = String.format(Locale.US, "%02d:%02d", currentHour, currentMinute)

            var triggeredRule: AutomationRule? = null

            for (rule in timeRules) {
                // If it already triggered in this minute, skip
                if (triggeredTimeRules.contains(rule.id)) continue
                
                val ruleTime = rule.time ?: continue
                
                // Match time
                if (ruleTime == currentTimeStr) {
                    // Match day of week if configured
                    val days = rule.daysOfWeek?.split(",") ?: emptyList()
                    if (days.isEmpty() || days.contains(currentDayStr)) {
                        // Evaluate Logic Gates if geofence is also set
                        val isLocationActive = if (rule.latitude != null && rule.longitude != null) {
                            val loc = lastLocation
                            if (loc != null) {
                                val dest = Location("dest").apply {
                                    latitude = rule.latitude
                                    longitude = rule.longitude
                                }
                                loc.distanceTo(dest) <= (rule.radius ?: 100f)
                            } else {
                                false
                            }
                        } else {
                            false
                        }

                        val shouldTrigger = when (rule.connectionLogic) {
                            "AND" -> isLocationActive
                            "XOR" -> !isLocationActive
                            else -> true // "NONE", "OR"
                        }

                        if (shouldTrigger) {
                            // Priority Check
                            if (triggeredRule == null || rule.priority > triggeredRule.priority) {
                                triggeredRule = rule
                            }
                        }
                    }
                }
            }

            if (triggeredRule != null) {
                Log.d(TAG, "Time rule triggered: ${triggeredRule.name} with priority ${triggeredRule.priority}")
                triggeredTimeRules.add(triggeredRule.id)
                executeRule(triggeredRule)
            }
        }
    }

    private fun evaluateIntervalRules() {
        scope.launch {
            val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
            val isOverrideLocked = overrideLockSetting?.value == "true"
            if (isOverrideLocked) return@launch

            val now = System.currentTimeMillis()

            // 1. Global settings periodic rotate logic
            val globalRotateEnabledSetting = database.appSettingDao().getSetting("global_rotate_enabled")
            if (globalRotateEnabledSetting?.value == "true") {
                val valueSetting = database.appSettingDao().getSetting("global_rotate_time_value")
                val unitSetting = database.appSettingDao().getSetting("global_rotate_time_unit")
                val value = valueSetting?.value?.toIntOrNull() ?: 10
                val unit = unitSetting?.value ?: "Minutes"
                
                val intervalMs = when (unit) {
                    "Seconds" -> value * 1000L
                    "Minutes" -> value * 60000L
                    "Hours" -> value * 3600000L
                    "Days" -> value * 86400000L
                    else -> value * 60000L
                }
                
                val lastRun = lastTriggeredTimeMap[999999L]
                if (lastRun == null) {
                    lastTriggeredTimeMap[999999L] = now
                } else if (now - lastRun >= intervalMs) {
                    lastTriggeredTimeMap[999999L] = now
                    val activeAlbumIds = getActiveAlbumIds()
                    if (activeAlbumIds.isNotEmpty()) {
                        Log.d(TAG, "Global periodic rotate triggered for albums pool $activeAlbumIds")
                        rotateWallpaper(activeAlbumIds)
                    }
                }
            }

            // 2. Custom automation interval rules
            val rules = database.automationRuleDao().getAllRules()
            val intervalRules = rules.filter { it.isEnabled && it.type == "Interval" && it.intervalValue != null && it.intervalUnit != null }

            for (rule in intervalRules) {
                val value = rule.intervalValue ?: continue
                val unit = rule.intervalUnit ?: continue
                val intervalMs = when (unit) {
                    "Seconds" -> value * 1000L
                    "Minutes" -> value * 60000L
                    "Hours" -> value * 3600000L
                    "Days" -> value * 86400000L
                    else -> value * 60000L
                }

                val lastRun = lastTriggeredTimeMap[rule.id]
                if (lastRun == null) {
                    lastTriggeredTimeMap[rule.id] = now
                    continue
                }

                if (now - lastRun >= intervalMs) {
                    lastTriggeredTimeMap[rule.id] = now
                    Log.d(TAG, "Interval rule triggered: ${rule.name} (elapsed ${now - lastRun}ms)")
                    executeRule(rule)
                }
            }
        }
    }

    private suspend fun evaluateLocationRules() {
        val location = lastLocation ?: return
        
        // Check manual override permanent lock
        val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
        val isOverrideLocked = overrideLockSetting?.value == "true"
        if (isOverrideLocked) return

        val rules = database.automationRuleDao().getAllRules()
        val locRules = rules.filter { it.isEnabled && (it.type == "Location" || it.latitude != null) }

        var winningRule: AutomationRule? = null

        for (rule in locRules) {
            val lat = rule.latitude ?: continue
            val lng = rule.longitude ?: continue
            val radius = rule.radius ?: 100f

            val dest = Location("dest").apply {
                latitude = lat
                longitude = lng
            }

            val distance = location.distanceTo(dest)
            val isInside = distance <= radius

            if (isInside) {
                // Evaluate logic gate time constraint if set
                val isTimeActive = if (rule.time != null) {
                    val calendar = Calendar.getInstance()
                    val currentTimeStr = String.format(Locale.US, "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
                    rule.time == currentTimeStr
                } else {
                    false
                }

                val shouldTrigger = when (rule.connectionLogic) {
                    "AND" -> isTimeActive
                    "XOR" -> !isTimeActive
                    else -> true // "NONE", "OR"
                }

                if (shouldTrigger) {
                    if (winningRule == null || rule.priority > winningRule.priority) {
                        winningRule = rule
                    }
                }
            }
        }

        if (winningRule != null && winningRule.id != activeRuleId) {
            Log.d(TAG, "Location rule triggered: ${winningRule.name} (Entered geofence)")
            activeRuleId = winningRule.id
            executeRule(winningRule)
        } else if (winningRule == null && activeRuleId != null) {
            // Exited all geofenced rules. Clear active state
            Log.d(TAG, "Exited all location rule geofences")
            activeRuleId = null
            // Re-evaluate normal schedule
            evaluateTimeRules()
        }
    }

    private suspend fun executeRule(rule: AutomationRule) {
        // Clear temporary manual override when rule triggers
        database.appSettingDao().deleteSetting("manual_override_album")
        
        updateNotification("Active Rule: ${rule.name}")
        
        if (rule.actionType == "NextWallpaper") {
            val activeAlbumIds = getActiveAlbumIds()
            if (activeAlbumIds.isNotEmpty()) {
                rotateWallpaper(activeAlbumIds, random = rule.randomOrder)
            }
        } else if (rule.actionType == "SwitchAlbum" && rule.targetAlbumId != null) {
            val ids = rule.targetAlbumIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            val finalIds = if (ids.isNotEmpty()) ids else listOf(rule.targetAlbumId)
            
            val idsString = finalIds.joinToString(",")
            database.appSettingDao().insertSetting(AppSetting("active_album_ids", idsString))
            database.appSettingDao().insertSetting(AppSetting("active_album_id", finalIds.first().toString()))
            
            val finalAlbums = finalIds.mapNotNull { database.albumDao().getById(it) }
            val albumNames = finalAlbums.joinToString(", ") { it.name }
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "changing album to $albumNames", Toast.LENGTH_SHORT).show()
            }
            
            rotateWallpaper(finalIds, random = rule.randomOrder)
        }
    }

    // --- Gesture & Command Handlers ---

    private fun handleGesture(gesture: String?) {
        if (gesture == null) return
        val now = System.currentTimeMillis()
        if (gesture == lastGestureType && now - lastGestureTime < 500L) {
            Log.d(TAG, "Ignoring duplicate gesture: $gesture")
            return
        }
        lastGestureTime = now
        lastGestureType = gesture

        scope.launch {
            var activeAlbumIds = getActiveAlbumIds()
            if (activeAlbumIds.isEmpty()) {
                val fallbackId = database.albumDao().getAll().firstOrNull()?.id
                if (fallbackId != null) {
                    activeAlbumIds = listOf(fallbackId)
                }
            }
            if (activeAlbumIds.isEmpty()) {
                Log.d(TAG, "No active album and no fallback album found, cannot handle gesture")
                return@launch
            }

            val actionSettingKey = "gesture_$gesture"
            val actionSetting = database.appSettingDao().getSetting(actionSettingKey)
            val action = actionSetting?.value ?: when (gesture) {
                "DoubleTap" -> "NextPhoto"
                "TwoFingerDoubleTap" -> "Freeze"
                "ThreeFingerDoubleTap" -> "NextPhoto"
                else -> "None"
            }

            Log.d(TAG, "Gesture $gesture mapped to action: $action")

            when (action) {
                "NextPhoto" -> {
                    rotateWallpaper(activeAlbumIds)
                }
                "LastPhoto" -> {
                    rotateWallpaper(activeAlbumIds, isForward = false)
                }
                "NextAlbum" -> {
                    cycleAlbum()
                }
                "LastAlbum" -> {
                    cycleAlbumBackwards()
                }
                "Freeze" -> {
                    toggleOverrideFreeze()
                }
                "Custom" -> {
                    val rules = database.automationRuleDao().getAllRules()
                    val gestureRules = rules.filter { 
                        it.isEnabled && it.type == "Gesture" && it.gestureType == gesture 
                    }
                    gestureRules.forEach { rule ->
                        Log.d(TAG, "Triggering custom gesture rule: ${rule.name}")
                        executeRule(rule)
                    }
                }
                "None" -> {}
            }
        }
    }

    private suspend fun cycleAlbum() {
        val activeIds = getActiveAlbumIds()
        if (activeIds.size <= 1) return
        
        val currentAlbumSetting = database.appSettingDao().getSetting("active_album_id")
        val currentAlbumId = currentAlbumSetting?.value?.toLongOrNull()
        
        val currentIndex = if (currentAlbumId != null) activeIds.indexOf(currentAlbumId) else -1
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % activeIds.size
        val nextAlbumId = activeIds[nextIndex]
        
        val nextAlbum = database.albumDao().getById(nextAlbumId) ?: return
        
        Log.d(TAG, "Cycling active album from $currentAlbumId to $nextAlbumId")
        database.appSettingDao().insertSetting(AppSetting("active_album_id", nextAlbumId.toString()))
        
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, "changing album to ${nextAlbum.name}", Toast.LENGTH_SHORT).show()
        }
        
        rotateWallpaper(listOf(nextAlbumId))
    }

    private suspend fun cycleAlbumBackwards() {
        val activeIds = getActiveAlbumIds()
        if (activeIds.size <= 1) return
        
        val currentAlbumSetting = database.appSettingDao().getSetting("active_album_id")
        val currentAlbumId = currentAlbumSetting?.value?.toLongOrNull()
        
        val currentIndex = if (currentAlbumId != null) activeIds.indexOf(currentAlbumId) else -1
        val nextIndex = if (currentIndex == -1) activeIds.size - 1 else (currentIndex - 1 + activeIds.size) % activeIds.size
        val nextAlbumId = activeIds[nextIndex]
        
        val nextAlbum = database.albumDao().getById(nextAlbumId) ?: return
        
        Log.d(TAG, "Cycling active album backwards from $currentAlbumId to $nextAlbumId")
        database.appSettingDao().insertSetting(AppSetting("active_album_id", nextAlbumId.toString()))
        
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, "changing album to ${nextAlbum.name}", Toast.LENGTH_SHORT).show()
        }
        
        rotateWallpaper(listOf(nextAlbumId))
    }

    private suspend fun toggleOverrideFreeze() {
        val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
        val isOverrideLocked = overrideLockSetting?.value == "true"
        
        val newVal = if (isOverrideLocked) "false" else "true"
        database.appSettingDao().insertSetting(AppSetting("continue_override", newVal))
        
        Log.d(TAG, "Toggle Continue Override Lock is now: $newVal")
        if (newVal == "true") {
            updateNotification("Override Mode: Frozen")
        } else {
            updateNotification("Running wallpaper automation...")
            // Instantly evaluate on un-freeze
            evaluateTimeRules()
            evaluateLocationRules()
        }
    }

    // --- Geofencing & Location API ---

    @SuppressLint("MissingPermission")
    private fun setupLocationTracking() {
        scope.launch {
            val rules = database.automationRuleDao().getAllRules()
            val locationRules = rules.filter { it.isEnabled && it.type == "Location" }
            if (locationRules.isEmpty()) {
                Log.d(TAG, "No active location rules, skipping location updates")
                stopLocationTracking()
                return@launch
            }

            // Determine highest required accuracy
            val needsPrecise = locationRules.any { it.locationAccuracy == "Precise" }
            val interval = if (needsPrecise) 30000L else 120000L // 30 seconds vs 2 minutes
            val priority = if (needsPrecise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY

            Log.d(TAG, "Starting location updates: needsPrecise=$needsPrecise, interval=$interval")

            val locationRequest = LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(interval / 2)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    for (location in locationResult.locations) {
                        Log.d(TAG, "Received location update: ${location.latitude}, ${location.longitude}")
                        lastLocation = location
                        scope.launch {
                            evaluateLocationRules()
                        }
                    }
                }
            }

            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback!!,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request location updates: ${e.message}")
            }
        }
    }

    private fun stopLocationTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
            Log.d(TAG, "Location updates stopped")
        }
    }
}
