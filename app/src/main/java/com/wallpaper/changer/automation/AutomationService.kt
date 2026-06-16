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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isWifiConnected = false
    
    // Automation state
    private val handler = Handler(Looper.getMainLooper())
    private var lastCheckedMinute = -1
    private var activeRuleId: Long? = null
    private var isScreenOn = true
    private var hasChangedWallpaperWhileScreenOff = false
    
    // Set of triggered time rules for the current minute to avoid double triggering
    private val triggeredTimeRules = HashSet<Long>()
    
    private var lastGestureTime = 0L
    private var lastGestureType = ""
    private var lastUnlockTime = 0L
    
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
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Device unlocked, handling unlock event")
                    handleUnlockEvent()
                }
                "com.wallpaper.changer.ACTION_HOMESCREEN" -> {
                    Log.d(TAG, "Navigated to home screen, handling home screen event")
                    handleHomeScreenEvent()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen turned ON")
                    isScreenOn = true
                    hasChangedWallpaperWhileScreenOff = false
                    scope.launch {
                        evaluateScheduledRules()
                        evaluateIntervalRules()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen turned OFF")
                    isScreenOn = false
                    hasChangedWallpaperWhileScreenOff = false
                }
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            evaluateScheduledRules()
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
        
        // Register receiver for gestures, errors, lock, and home screen actions
        val filter = IntentFilter().apply {
            addAction(DynamicWallpaperService.ACTION_GESTURE)
            addAction(DynamicWallpaperService.ACTION_WALLPAPER_ERROR)
            addAction("com.wallpaper.changer.ACTION_REQUEST_CURRENT")
            addAction(Intent.ACTION_USER_PRESENT)
            addAction("com.wallpaper.changer.ACTION_HOMESCREEN")
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
        
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }
        
        startForeground(NOTIFICATION_ID, createNotification("Running wallpaper automation..."))
        
        // Start time and interval rule evaluation loops
        handler.post(tickRunnable)
        handler.post(intervalRunnable)
        
        // Start location and WiFi updates
        setupLocationTracking()
        setupWifiTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying AutomationService...")
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(intervalRunnable)
        stopLocationTracking()
        stopWifiTracking()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        job.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AutomationService started onStartCommand")
        setupLocationTracking()
        val forceRotate = intent?.getBooleanExtra("force_rotate", false) == true
        val applyWallpaper = intent?.getBooleanExtra("apply_wallpaper", false) == true
        val prevPhoto = intent?.getBooleanExtra("prev_photo", false) == true
        // Force evaluation on command start
        scope.launch {
            evaluateScheduledRules()
            
            val activeAlbumIds = getActiveAlbumIds()
            val activePath = database.appSettingDao().getSetting("active_wallpaper_path")?.value
            
            if (applyWallpaper && activePath != null) {
                scope.launch(Dispatchers.IO) {
                    val photo = database.photoDao().getAllPhotos().find { it.path == activePath }
                    if (photo != null) {
                        database.photoDao().updateWasSeen(photo.id, true)
                    }
                }
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

            val updateIntent = Intent(DynamicWallpaperService.ACTION_UPDATE_WALLPAPER).apply {
                putExtra(DynamicWallpaperService.EXTRA_WALLPAPER_PATH, path)
                putExtra(DynamicWallpaperService.EXTRA_SCALING_MODE, mode)
                putExtra("dimming_percent", dimming)
                putExtra("blur_percent", blur)
                putExtra("greyscale_percent", greyscale)
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

    suspend fun rotateWallpaper(albumIds: List<Long>, random: Boolean = false, isForward: Boolean = true, updateStatic: Boolean = true) {
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
            
            val validPhotos = allPhotos.filter { it.isOnline || existsUri(applicationContext, it.path) }
            if (validPhotos.isEmpty()) {
                Log.d(TAG, "All photos in albums pool are invalid/missing!")
                return@withContext
            }

            val globalRandomSetting = database.appSettingDao().getSetting("global_random_order")
            val isGlobalRandom = globalRandomSetting?.value == "true"
            
            val albums = albumIds.mapNotNull { database.albumDao().getById(it) }
            val isAlbumRandom = albums.any { it.randomOrder }
            val shouldRandomize = random || isGlobalRandom || isAlbumRandom

            val currentPath = database.appSettingDao().getSetting("active_wallpaper_path")?.value
            val historySetting = database.appSettingDao().getSetting("wallpaper_history")
            val history = historySetting?.value?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

            var chosenPhoto: Photo? = null

            // Seen check and reset logic
            var unseenPhotos = validPhotos.filter { !it.wasSeen }
            if (unseenPhotos.isEmpty()) {
                Log.d(TAG, "All valid photos have been seen. Resetting seen marks.")
                val idsToReset = validPhotos.map { it.id }
                database.photoDao().resetSeen(idsToReset)
                unseenPhotos = validPhotos.map { it.copy(wasSeen = false) }
            }

            if (isForward) {
                val currentIndexInHistory = if (currentPath != null) history.indexOf(currentPath) else -1
                if (currentIndexInHistory != -1 && currentIndexInHistory < history.size - 1) {
                    // Traverse forward through history first
                    for (i in (currentIndexInHistory + 1) until history.size) {
                        val path = history[i]
                        val match = validPhotos.find { it.path == path }
                        if (match != null) {
                            chosenPhoto = match
                            break
                        }
                    }
                }
                
                if (chosenPhoto == null) {
                    // Select new photo
                    if (shouldRandomize) {
                        chosenPhoto = unseenPhotos.randomOrNull()
                    } else {
                        val currentIndex = validPhotos.indexOfFirst { it.path == currentPath }
                        var targetPhoto: Photo? = null
                        if (currentIndex != -1) {
                            for (i in 1..validPhotos.size) {
                                val checkIdx = (currentIndex + i) % validPhotos.size
                                val candidate = validPhotos[checkIdx]
                                if (unseenPhotos.any { it.id == candidate.id }) {
                                    targetPhoto = candidate
                                    break
                                }
                            }
                        }
                        chosenPhoto = targetPhoto ?: unseenPhotos.firstOrNull()
                    }
                    
                    if (chosenPhoto != null) {
                        val newHistory = history.toMutableList()
                        newHistory.add(chosenPhoto.path)
                        if (newHistory.size > 5) {
                            newHistory.removeAt(0)
                        }
                        database.appSettingDao().insertSetting(AppSetting("wallpaper_history", newHistory.joinToString(",")))
                    }
                }
            } else {
                // Going backward
                val currentIndexInHistory = if (currentPath != null) history.indexOf(currentPath) else -1
                if (currentIndexInHistory > 0) {
                    for (i in (currentIndexInHistory - 1) downTo 0) {
                        val path = history[i]
                        val match = validPhotos.find { it.path == path }
                        if (match != null) {
                            chosenPhoto = match
                            break
                        }
                    }
                }
                
                if (chosenPhoto == null) {
                    // Select previous sequentially from unseen pool
                    val currentIndex = validPhotos.indexOfFirst { it.path == currentPath }
                    var targetPhoto: Photo? = null
                    if (currentIndex != -1) {
                        for (i in 1..validPhotos.size) {
                            val checkIdx = (currentIndex - i + validPhotos.size) % validPhotos.size
                            val candidate = validPhotos[checkIdx]
                            if (unseenPhotos.any { it.id == candidate.id }) {
                                targetPhoto = candidate
                                break
                            }
                        }
                    }
                    chosenPhoto = targetPhoto ?: unseenPhotos.lastOrNull()
                    
                    if (chosenPhoto != null) {
                        val newHistory = mutableListOf<String>()
                        newHistory.add(chosenPhoto.path)
                        if (currentPath != null) {
                            newHistory.add(currentPath)
                        }
                        database.appSettingDao().insertSetting(AppSetting("wallpaper_history", newHistory.joinToString(",")))
                    }
                }
            }

            if (chosenPhoto != null) {
                // Mark chosen photo as seen in database
                database.photoDao().updateWasSeen(chosenPhoto.id, true)

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

    suspend fun rotateWallpaper(albumId: Long, random: Boolean = false, updateStatic: Boolean = true) {
        rotateWallpaper(listOf(albumId), random, true, updateStatic)
    }

    suspend fun rotateWallpaperBackwards(albumId: Long, updateStatic: Boolean = true) {
        rotateWallpaper(listOf(albumId), false, false, updateStatic)
    }

    suspend fun rotateFavorites(random: Boolean = false, isForward: Boolean = true, updateStatic: Boolean = true) {
        withContext(Dispatchers.IO) {
            val allPhotos = database.photoDao().getFavoritePhotos()
            if (allPhotos.isEmpty()) {
                Log.d(TAG, "No favorite photos, cannot rotate")
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "No favorites added yet! Add some to run this rule.", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }

            val validPhotos = allPhotos.filter { it.isOnline || existsUri(applicationContext, it.path) }
            if (validPhotos.isEmpty()) {
                Log.d(TAG, "All favorite photos are invalid/missing!")
                return@withContext
            }

            val globalRandomSetting = database.appSettingDao().getSetting("global_random_order")
            val isGlobalRandom = globalRandomSetting?.value == "true"
            val shouldRandomize = random || isGlobalRandom

            val currentPath = database.appSettingDao().getSetting("active_wallpaper_path")?.value
            val historySetting = database.appSettingDao().getSetting("wallpaper_history")
            val history = historySetting?.value?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

            var chosenPhoto: Photo? = null

            // Seen check and reset logic for favorites
            var unseenPhotos = validPhotos.filter { !it.wasSeen }
            if (unseenPhotos.isEmpty()) {
                Log.d(TAG, "All valid favorite photos have been seen. Resetting seen marks.")
                val idsToReset = validPhotos.map { it.id }
                database.photoDao().resetSeen(idsToReset)
                unseenPhotos = validPhotos.map { it.copy(wasSeen = false) }
            }

            if (isForward) {
                val currentIndexInHistory = if (currentPath != null) history.indexOf(currentPath) else -1
                if (currentIndexInHistory != -1 && currentIndexInHistory < history.size - 1) {
                    for (i in (currentIndexInHistory + 1) until history.size) {
                        val path = history[i]
                        val match = validPhotos.find { it.path == path }
                        if (match != null) {
                            chosenPhoto = match
                            break
                        }
                    }
                }
                
                if (chosenPhoto == null) {
                    if (shouldRandomize) {
                        chosenPhoto = unseenPhotos.randomOrNull()
                    } else {
                        val currentIndex = validPhotos.indexOfFirst { it.path == currentPath }
                        var targetPhoto: Photo? = null
                        if (currentIndex != -1) {
                            for (i in 1..validPhotos.size) {
                                val checkIdx = (currentIndex + i) % validPhotos.size
                                val candidate = validPhotos[checkIdx]
                                if (unseenPhotos.any { it.id == candidate.id }) {
                                    targetPhoto = candidate
                                    break
                                }
                            }
                        }
                        chosenPhoto = targetPhoto ?: unseenPhotos.firstOrNull()
                    }
                    
                    if (chosenPhoto != null) {
                        val newHistory = history.toMutableList()
                        newHistory.add(chosenPhoto.path)
                        if (newHistory.size > 5) {
                            newHistory.removeAt(0)
                        }
                        database.appSettingDao().insertSetting(AppSetting("wallpaper_history", newHistory.joinToString(",")))
                    }
                }
            } else {
                // Backward
                val currentIndexInHistory = if (currentPath != null) history.indexOf(currentPath) else -1
                if (currentIndexInHistory > 0) {
                    for (i in (currentIndexInHistory - 1) downTo 0) {
                        val path = history[i]
                        val match = validPhotos.find { it.path == path }
                        if (match != null) {
                            chosenPhoto = match
                            break
                        }
                    }
                }
                
                if (chosenPhoto == null) {
                    val currentIndex = validPhotos.indexOfFirst { it.path == currentPath }
                    var targetPhoto: Photo? = null
                    if (currentIndex != -1) {
                        for (i in 1..validPhotos.size) {
                            val checkIdx = (currentIndex - i + validPhotos.size) % validPhotos.size
                            val candidate = validPhotos[checkIdx]
                            if (unseenPhotos.any { it.id == candidate.id }) {
                                targetPhoto = candidate
                                break
                            }
                        }
                    }
                    chosenPhoto = targetPhoto ?: unseenPhotos.lastOrNull()
                    
                    if (chosenPhoto != null) {
                        val newHistory = mutableListOf<String>()
                        newHistory.add(chosenPhoto.path)
                        if (currentPath != null) {
                            newHistory.add(currentPath)
                        }
                        database.appSettingDao().insertSetting(AppSetting("wallpaper_history", newHistory.joinToString(",")))
                    }
                }
            }

            if (chosenPhoto != null) {
                // Mark chosen photo as seen in database
                database.photoDao().updateWasSeen(chosenPhoto.id, true)

                database.appSettingDao().insertSetting(AppSetting("active_wallpaper_path", chosenPhoto.path))
                val photoAlbum = database.albumDao().getById(chosenPhoto.albumId)
                val scaleMode = if (chosenPhoto.scalingOverride != "None") {
                    chosenPhoto.scalingOverride
                } else {
                    photoAlbum?.scalingMode ?: "Fill"
                }
                database.appSettingDao().insertSetting(AppSetting("active_wallpaper_scaling", scaleMode))
                
                Log.d(TAG, "New favorite wallpaper chosen: ${chosenPhoto.path} with mode $scaleMode")
                broadcastCurrentWallpaper()
                if (updateStatic) {
                    applyStaticWallpaper(chosenPhoto.path)
                }
            }
        }
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

    private fun evaluateScheduledRules() {
        val calendar = Calendar.getInstance()
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentDayInt = calendar.get(Calendar.DAY_OF_WEEK)
        
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

        scope.launch {
            val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
            val isOverrideLocked = overrideLockSetting?.value == "true"
            if (isOverrideLocked) return@launch

            val rules = database.automationRuleDao().getAllRules()
            val scheduledRules = rules.filter { 
                it.isEnabled && (
                    it.type == "Time" || 
                    it.type == "Location" || 
                    it.type == "Compound" || 
                    it.time != null || 
                    it.latitude != null || 
                    it.wifiState != null
                ) 
            }

            val location = lastLocation
            var winningRule: AutomationRule? = null

            for (rule in scheduledRules) {
                val isDayOfWeekMet = if (!rule.daysOfWeek.isNullOrBlank()) {
                    val days = rule.daysOfWeek.split(",").map { it.trim() }
                    days.isEmpty() || days.contains(currentDayStr)
                } else {
                    true
                }

                val isTimeMet = if (!rule.time.isNullOrBlank()) {
                    val ruleTimeParts = rule.time.split(":")
                    val ruleHour = ruleTimeParts.getOrNull(0)?.toIntOrNull() ?: 0
                    val ruleMin = ruleTimeParts.getOrNull(1)?.toIntOrNull() ?: 0
                    val ruleMinutes = ruleHour * 60 + ruleMin
                    val currentMinutes = currentHour * 60 + currentMinute
                    
                    when (rule.timeCondition) {
                        "Before" -> currentMinutes < ruleMinutes
                        "After" -> currentMinutes > ruleMinutes
                        else -> currentMinutes == ruleMinutes // "At"
                    }
                } else {
                    true
                }

                val isLocationMet = if (rule.latitude != null && rule.longitude != null) {
                    if (location != null) {
                        val dest = Location("dest").apply {
                            latitude = rule.latitude
                            longitude = rule.longitude
                        }
                        val distance = location.distanceTo(dest)
                        val isInside = distance <= (rule.radius ?: 100f)
                        
                        when (rule.locationCondition) {
                            "Leaving" -> !isInside
                            else -> isInside // "Entering"
                        }
                    } else {
                        false
                    }
                } else {
                    true
                }

                val isWifiMet = if (!rule.wifiState.isNullOrBlank()) {
                    val currentSsid = getWifiSsid()
                    val matchesSsid = rule.wifiSsid.isNullOrBlank() || 
                                      rule.wifiSsid.equals(currentSsid, ignoreCase = true) ||
                                      rule.wifiSsid == "*"
                    
                    val matchesState = if (rule.wifiState.equals("Connecting", ignoreCase = true)) {
                        isWifiConnected
                    } else {
                        !isWifiConnected
                    }
                    matchesState && matchesSsid
                } else {
                    true
                }

                val isConditionMet = when (rule.connectionLogic) {
                    "AND" -> {
                        var met = true
                        if (rule.time != null) met = met && (isDayOfWeekMet && isTimeMet)
                        if (rule.latitude != null) met = met && isLocationMet
                        if (rule.wifiState != null) met = met && isWifiMet
                        met
                    }
                    "OR" -> {
                        var met = false
                        var count = 0
                        if (rule.time != null) { met = met || (isDayOfWeekMet && isTimeMet); count++ }
                        if (rule.latitude != null) { met = met || isLocationMet; count++ }
                        if (rule.wifiState != null) { met = met || isWifiMet; count++ }
                        if (count == 0) true else met
                    }
                    "XOR" -> {
                        var met = false
                        var count = 0
                        if (rule.time != null) { met = met xor (isDayOfWeekMet && isTimeMet); count++ }
                        if (rule.latitude != null) { met = met xor isLocationMet; count++ }
                        if (rule.wifiState != null) { met = met xor isWifiMet; count++ }
                        if (count == 0) true else met
                    }
                    "AND_NOT" -> (isDayOfWeekMet && isTimeMet) && !isLocationMet
                    "OR_NOT" -> (isDayOfWeekMet && isTimeMet) || !isLocationMet
                    "NOT" -> {
                        if (rule.time != null) {
                            !(isDayOfWeekMet && isTimeMet)
                        } else {
                            !isLocationMet
                        }
                    }
                    else -> {
                        if (rule.time != null && rule.latitude != null && rule.wifiState != null) {
                            (isDayOfWeekMet && isTimeMet) && isLocationMet && isWifiMet
                        } else if (rule.time != null && rule.latitude != null) {
                            (isDayOfWeekMet && isTimeMet) && isLocationMet
                        } else if (rule.time != null && rule.wifiState != null) {
                            (isDayOfWeekMet && isTimeMet) && isWifiMet
                        } else if (rule.latitude != null && rule.wifiState != null) {
                            isLocationMet && isWifiMet
                        } else if (rule.time != null) {
                            isDayOfWeekMet && isTimeMet
                        } else if (rule.latitude != null) {
                            isLocationMet
                        } else if (rule.wifiState != null) {
                            isWifiMet
                        } else {
                            true
                        }
                    }
                }

                if (isConditionMet) {
                    if (winningRule == null || rule.priority > winningRule.priority) {
                        winningRule = rule
                    }
                }
            }

            if (winningRule != null) {
                if (winningRule.id != activeRuleId) {
                    Log.d(TAG, "Scheduled rule triggered: ${winningRule.name} with priority ${winningRule.priority}")
                    activeRuleId = winningRule.id
                    executeRule(winningRule)
                }
            } else {
                if (activeRuleId != null) {
                    Log.d(TAG, "No scheduled rule met. Clearing activeRuleId.")
                    activeRuleId = null
                    database.appSettingDao().insertSetting(AppSetting("active_rule_dimming", "0"))
                    database.appSettingDao().insertSetting(AppSetting("active_rule_blur", "0"))
                    database.appSettingDao().insertSetting(AppSetting("active_rule_greyscale", "0"))
                    broadcastCurrentWallpaper()
                }
            }
        }
    }

    private fun evaluateIntervalRules() {
        scope.launch {
            val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
            val isOverrideLocked = overrideLockSetting?.value == "true"
            if (isOverrideLocked) return@launch

            val isBatterySaverEnabled = true
            
            if (isBatterySaverEnabled && !isScreenOn && hasChangedWallpaperWhileScreenOff) {
                // Screen off and wallpaper already rotated once. Skip evaluations to save battery.
                return@launch
            }

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
                        if (isBatterySaverEnabled && !isScreenOn) {
                            hasChangedWallpaperWhileScreenOff = true
                        }
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
                    if (isBatterySaverEnabled && !isScreenOn) {
                        hasChangedWallpaperWhileScreenOff = true
                    }
                }
            }
        }
    }

    private suspend fun executeRule(rule: AutomationRule) {
        // Clear temporary manual override when rule triggers
        database.appSettingDao().deleteSetting("manual_override_album")
        
        updateNotification("Active Rule: ${rule.name}")
        
        // Save active rule filters to database
        database.appSettingDao().insertSetting(AppSetting("active_rule_dimming", (rule.dimmingPercent ?: 0).toString()))
        database.appSettingDao().insertSetting(AppSetting("active_rule_blur", (rule.blurPercent ?: 0).toString()))
        database.appSettingDao().insertSetting(AppSetting("active_rule_greyscale", (rule.greyscalePercent ?: 0).toString()))
        
        if (rule.actionType == "NextWallpaper") {
            val activeAlbumIds = getActiveAlbumIds()
            if (activeAlbumIds.isNotEmpty()) {
                rotateWallpaper(activeAlbumIds, random = rule.randomOrder, isForward = true)
            }
        } else if (rule.actionType == "PrevWallpaper") {
            val activeAlbumIds = getActiveAlbumIds()
            if (activeAlbumIds.isNotEmpty()) {
                rotateWallpaper(activeAlbumIds, random = rule.randomOrder, isForward = false)
            }
        } else if (rule.actionType == "RandomOrder") {
            val activeAlbumIds = getActiveAlbumIds()
            if (activeAlbumIds.isNotEmpty()) {
                rotateWallpaper(activeAlbumIds, random = true)
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
        } else if (rule.actionType == "RotateFavorites") {
            rotateFavorites(random = rule.randomOrder)
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
                "Freeze" -> {
                    toggleOverrideFreeze()
                }
                "None" -> {}
            }

            // Always run matching custom gesture rules defined in the rule engine
            val rules = database.automationRuleDao().getAllRules()
            val gestureRules = rules.filter { 
                it.isEnabled && it.type == "Gesture" && it.gestureType == gesture 
            }
            gestureRules.forEach { rule ->
                Log.d(TAG, "Triggering custom gesture rule: ${rule.name}")
                executeRule(rule)
            }
        }
    }

    private fun handleUnlockEvent() {
        lastUnlockTime = System.currentTimeMillis()
        scope.launch {
            // 1. Global Setting Check
            val unlockSetting = database.appSettingDao().getSetting("change_on_unlock")
            if (unlockSetting?.value == "true") {
                val activeAlbumIds = getActiveAlbumIds()
                if (activeAlbumIds.isNotEmpty()) {
                    Log.d(TAG, "Global unlock trigger: rotating wallpaper")
                    rotateWallpaper(activeAlbumIds)
                }
            }

            // 2. Custom Automation Rules Check
            val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
            if (overrideLockSetting?.value != "true") {
                val rules = database.automationRuleDao().getAllRules()
                val unlockRules = rules.filter { it.isEnabled && it.type == "Unlock" }
                var winningRule: AutomationRule? = null
                for (rule in unlockRules) {
                    if (winningRule == null || rule.priority > winningRule.priority) {
                        winningRule = rule
                    }
                }
                winningRule?.let {
                    Log.d(TAG, "Unlock automation rule triggered: ${it.name}")
                    executeRule(it)
                }
            }
        }
    }

    private fun handleHomeScreenEvent() {
        scope.launch {
            // 1. Global Setting Check
            val homeSetting = database.appSettingDao().getSetting("change_on_homescreen")
            val unlockSetting = database.appSettingDao().getSetting("change_on_unlock")
            if (homeSetting?.value == "true") {
                // Deduplicate rotation if changeOnUnlock is also active and we recently unlocked
                if (unlockSetting?.value == "true" && System.currentTimeMillis() - lastUnlockTime < 2000L) {
                    Log.d(TAG, "Skipping home screen trigger because unlock trigger just ran.")
                    return@launch
                }
                val activeAlbumIds = getActiveAlbumIds()
                if (activeAlbumIds.isNotEmpty()) {
                    Log.d(TAG, "Global home screen trigger: rotating wallpaper")
                    rotateWallpaper(activeAlbumIds)
                }
            }

            // 2. Custom Automation Rules Check
            val overrideLockSetting = database.appSettingDao().getSetting("continue_override")
            if (overrideLockSetting?.value != "true") {
                val rules = database.automationRuleDao().getAllRules()
                val homeRules = rules.filter { it.isEnabled && it.type == "HomeScreen" }
                var winningRule: AutomationRule? = null
                for (rule in homeRules) {
                    if (winningRule == null || rule.priority > winningRule.priority) {
                        winningRule = rule
                    }
                }
                winningRule?.let {
                    // Also deduplicate custom rule rotation if an unlock rule just ran
                    val hasUnlockRule = rules.any { it.isEnabled && it.type == "Unlock" }
                    if (hasUnlockRule && System.currentTimeMillis() - lastUnlockTime < 2000L) {
                        Log.d(TAG, "Skipping home screen rule trigger because unlock rule just ran.")
                        return@launch
                    }
                    Log.d(TAG, "HomeScreen automation rule triggered: ${it.name}")
                    executeRule(it)
                }
            }
        }
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
            evaluateScheduledRules()
        }
    }

    // --- Geofencing & Location API ---

    @SuppressLint("MissingPermission")
    private fun setupLocationTracking() {
        scope.launch {
            stopLocationTracking()
            val rules = database.automationRuleDao().getAllRules()
            val locationRules = rules.filter { it.isEnabled && (it.type == "Location" || it.latitude != null) }
            if (locationRules.isEmpty()) {
                Log.d(TAG, "No active location rules, skipping location updates")
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
                        evaluateScheduledRules()
                    }
                }
            }

            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        Log.d(TAG, "Last known location retrieved: ${loc.latitude}, ${loc.longitude}")
                        lastLocation = loc
                        evaluateScheduledRules()
                    }
                }
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback!!,
                    Looper.getMainLooper()
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission missing: ${e.message}")
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

    private fun setupWifiTracking() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        
        // Initial state
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        isWifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
            
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "WiFi Connected")
                if (!isWifiConnected) {
                    isWifiConnected = true
                    handleWifiEvent("Connecting")
                    evaluateScheduledRules()
                }
            }
            
            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "WiFi Disconnected")
                if (isWifiConnected) {
                    isWifiConnected = false
                    handleWifiEvent("Disconnecting")
                    evaluateScheduledRules()
                }
            }
        }
        
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }
    
    private fun stopWifiTracking() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback: ${e.message}")
            }
            networkCallback = null
        }
    }
    
    private fun getWifiSsid(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val activeNetwork = cm.activeNetwork ?: return null
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return null
        val wifiInfo = capabilities.transportInfo as? android.net.wifi.WifiInfo ?: return null
        val ssid = wifiInfo.ssid
        if (ssid == "<unknown ssid>" || ssid == "0x") return null
        return ssid?.trim('"', ' ')
    }
    
    private fun handleWifiEvent(state: String) {
        scope.launch {
            val rules = database.automationRuleDao().getAllRules()
            val wifiRules = rules.filter { it.isEnabled && it.type == "WiFi" && it.wifiState.equals(state, ignoreCase = true) }
            
            if (wifiRules.isEmpty()) return@launch
            
            val currentSsid = getWifiSsid()
            Log.d(TAG, "Evaluating WiFi rules for state=$state, currentSsid=$currentSsid")
            
            var winningRule: AutomationRule? = null
            for (rule in wifiRules) {
                val matchesSsid = rule.wifiSsid.isNullOrBlank() || 
                                  rule.wifiSsid.equals(currentSsid, ignoreCase = true) ||
                                  rule.wifiSsid == "*"
                
                if (matchesSsid) {
                    if (winningRule == null || rule.priority > winningRule.priority) {
                        winningRule = rule
                    }
                }
            }
            
            winningRule?.let {
                Log.d(TAG, "WiFi automation rule triggered: ${it.name}")
                executeRule(it)
            }
        }
    }
}
