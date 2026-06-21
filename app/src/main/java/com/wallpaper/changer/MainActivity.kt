package com.wallpaper.changer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import android.app.WallpaperManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wallpaper.changer.ui.getOriginalDisplayName
import com.wallpaper.changer.ui.copyUriToInternal

import com.wallpaper.changer.automation.AutomationService
import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.AppSetting
import com.wallpaper.changer.ui.AlbumScreen
import com.wallpaper.changer.ui.easy.EasyModeScreen
import com.wallpaper.changer.ui.expert.ExpertModeScreen
import com.wallpaper.changer.ui.hobbyist.HobbyistModeScreen
import com.wallpaper.changer.wallpaper.DynamicWallpaperService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val sharedUrisState = mutableStateOf<List<Uri>?>(null)

    private fun getSharedUris(intent: Intent?): List<Uri>? {
        if (intent == null) return null
        val action = intent.action
        val type = intent.type
        if (type?.startsWith("image/") == true) {
            if (Intent.ACTION_SEND == action) {
                val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (imageUri != null) {
                    return listOf(imageUri)
                }
            } else if (Intent.ACTION_SEND_MULTIPLE == action) {
                val imageUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (imageUris != null) {
                    return imageUris.filterNotNull()
                }
            }
        }
        return null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val sharedUris = getSharedUris(intent)
        if (sharedUris != null) {
            sharedUrisState.value = sharedUris
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val database = (application as WallpaperChangerApplication).database
        
        val sharedUris = getSharedUris(intent)
        if (sharedUris != null) {
            sharedUrisState.value = sharedUris
        }
        
        // Auto-start Automation Service when MainActivity opens to ensure rules run
        try {
            val serviceIntent = Intent(this, AutomationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContent {
            val themeSetting by database.appSettingDao().getSettingFlow("app_theme").collectAsState(initial = null)
            val currentTheme = themeSetting?.value ?: "System"
            val isDark = when (currentTheme) {
                "Dark" -> true
                "Light" -> false
                else -> isSystemInDarkTheme()
            }
            
            val colors = if (isDark) {
                darkColorScheme(
                    primary = Color(0xFF8B5CF6),      // Vibrant Violet
                    secondary = Color(0xFFD946EF),    // Fuchsia
                    tertiary = Color(0xFF3B82F6),     // Electric Blue
                    background = Color(0xFF09080C),   // Pure space dark background
                    surface = Color(0xFF121016),      // Obsidian dark card surface
                    surfaceVariant = Color(0xFF1A1722), // Lighter card variant
                    onPrimary = Color(0xFFFFFFFF),
                    onSecondary = Color(0xFFFFFFFF),
                    onBackground = Color(0xFFF3F4F6),
                    onSurface = Color(0xFFF3F4F6),
                    onSurfaceVariant = Color(0xFFD1D5DB),
                    outline = Color(0xFF2C2836)
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF6D28D9),      // Violet
                    secondary = Color(0xFFC084FC),    // Light Fuchsia
                    tertiary = Color(0xFF2563EB),     // Blue
                    background = Color(0xFFF9FAFB),   // Clean light background
                    surface = Color(0xFFFFFFFF),      // White card surface
                    surfaceVariant = Color(0xFFF3F4F6), // Light gray variant
                    onPrimary = Color(0xFFFFFFFF),
                    onSecondary = Color(0xFFFFFFFF),
                    onBackground = Color(0xFF111827),
                    onSurface = Color(0xFF111827),
                    onSurfaceVariant = Color(0xFF4B5563),
                    outline = Color(0xFFE5E7EB)
                )
            }

            val view = androidx.compose.ui.platform.LocalView.current
            val context = androidx.compose.ui.platform.LocalContext.current
            val activity = remember(context) {
                var ctx = context
                while (ctx is android.content.ContextWrapper) {
                    if (ctx is android.app.Activity) break
                    ctx = ctx.baseContext
                }
                ctx as? android.app.Activity
            }
            if (activity != null && !view.isInEditMode) {
                LaunchedEffect(isDark, colors) {
                    val window = activity.window
                    window.statusBarColor = colors.surfaceVariant.toArgb()
                    androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
                }
            }

            MaterialTheme(
                colorScheme = colors
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainNavigationContainer(database)
                    }

                    val sharedUris by sharedUrisState
                    if (sharedUris != null) {
                        ShareImportDialog(
                            sharedUris = sharedUris!!,
                            database = database,
                            onDismiss = { sharedUrisState.value = null }
                        )
                    }
                }
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigationContainer(database: AppDatabase) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("wallpaper_changer_prefs", Context.MODE_PRIVATE) }
    var selectedScreen by remember { mutableStateOf(sharedPrefs.getInt("last_screen", 0)) }
    var isRulesExpanded by remember { mutableStateOf(selectedScreen in 1..3) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val screenHistory = remember { mutableStateListOf<Int>() }

    val navigateTo: (Int) -> Unit = { screenIndex ->
        if (selectedScreen != screenIndex) {
            if (screenHistory.isEmpty() || screenHistory.last() != selectedScreen) {
                screenHistory.add(selectedScreen)
            }
            selectedScreen = screenIndex
            sharedPrefs.edit().putInt("last_screen", screenIndex).apply()
        }
    }

    BackHandler(enabled = screenHistory.isNotEmpty() || selectedScreen != 0) {
        if (screenHistory.isNotEmpty()) {
            val prev = screenHistory.removeAt(screenHistory.size - 1)
            selectedScreen = prev
            sharedPrefs.edit().putInt("last_screen", prev).apply()
        } else {
            selectedScreen = 0
            sharedPrefs.edit().putInt("last_screen", 0).apply()
        }
    }

    // Request permissions on launch
    PermissionRequester()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Wallpaper Changer",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)

                // 1. Albums
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Albums") },
                    label = { Text("Albums") },
                    selected = selectedScreen == 0,
                    onClick = {
                        navigateTo(0)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 2. Expandable Automation Rules
                Column {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.List, contentDescription = "Automation Rules") },
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Automation Rules")
                                Icon(
                                    imageVector = if (isRulesExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isRulesExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        selected = selectedScreen in 1..3,
                        onClick = {
                            isRulesExpanded = !isRulesExpanded
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )

                    if (isRulesExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp)
                        ) {
                            // Sub-item: Easy Mode
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Share, contentDescription = "Easy Mode", tint = Color.Transparent) },
                                label = { Text("Easy Mode") },
                                selected = selectedScreen == 1,
                                onClick = {
                                    navigateTo(1)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                            // Sub-item: Hobbyist Mode
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Share, contentDescription = "Hobbyist Mode", tint = Color.Transparent) },
                                label = { Text("Hobbyist Mode") },
                                selected = selectedScreen == 2,
                                onClick = {
                                    navigateTo(2)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                            // Sub-item: Expert Mode
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Share, contentDescription = "Expert Mode", tint = Color.Transparent) },
                                label = { Text("Expert Mode") },
                                selected = selectedScreen == 3,
                                onClick = {
                                    navigateTo(3)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 3. Global Settings
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Global Settings") },
                    label = { Text("Global Settings") },
                    selected = selectedScreen == 4,
                    onClick = {
                        navigateTo(4)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                val screenTitle = when (selectedScreen) {
                    0 -> "Albums"
                    1 -> "Easy Mode Automation"
                    2 -> "Hobbyist Flowchart"
                    3 -> "Expert YAML Editor"
                    4 -> "Global Settings"
                    else -> "Wallpaper Changer"
                }
                TopAppBar(
                    title = { Text(screenTitle, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Drawer")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedScreen) {
                    0 -> AlbumScreen(database)
                    1 -> EasyModeScreen(database)
                    2 -> HobbyistModeScreen(database)
                    3 -> ExpertModeScreen(database)
                    4 -> SettingsScreen(database)
                }
            }
        }
    }
}

@Composable
fun PermissionRequester() {
    val context = LocalContext.current
    
    val permissionsToRequest = remember {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                list.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        list
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val hasLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
            } else {
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true
            }
            if (!hasLocation || !hasStorage) {
                Toast.makeText(context, "Storage and Location access are required for full automation rules.", Toast.LENGTH_LONG).show()
            }
        }
    )

    LaunchedEffect(Unit) {
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                          ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
             ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasLocation || !hasStorage) {
            launcher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun SettingsScreen(database: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var cacheOnlineSources by remember { mutableStateOf(false) }
    var allFilesAccessGranted by remember { mutableStateOf(false) }
    var isOverrideActive by remember { mutableStateOf(false) }
    var transitionSpeed by remember { mutableStateOf("800") }
    var transitionType by remember { mutableStateOf("Crossfade") }
    var globalRandomOrder by remember { mutableStateOf(false) }
    var globalRotateEnabled by remember { mutableStateOf(false) }
    var globalRotateValue by remember { mutableStateOf("10") }
    var globalRotateUnit by remember { mutableStateOf("Minutes") }
    var appTheme by remember { mutableStateOf("System") }
    val gestureActions = remember { mutableStateMapOf<String, String>() }
    var gesturesInUse by remember { mutableStateOf(emptySet<String>()) }
    var changeOnUnlock by remember { mutableStateOf(false) }
    var changeOnHomeScreen by remember { mutableStateOf(false) }
    var batterySaverScreenOff by remember { mutableStateOf(false) }

    var globalDimmingEnabled by remember { mutableStateOf(false) }
    var globalDimmingPercent by remember { mutableStateOf(0) }
    var globalBlurEnabled by remember { mutableStateOf(false) }
    var globalBlurPercent by remember { mutableStateOf(0) }
    var globalGreyscaleEnabled by remember { mutableStateOf(false) }
    var globalGreyscalePercent by remember { mutableStateOf(0) }

    val wallpaperManager = remember { WallpaperManager.getInstance(context) }
    var isWallpaperActive by remember { mutableStateOf(false) }

    fun refreshSettings() {
        scope.launch {
            val cacheSet = database.appSettingDao().getSetting("cache_online_sources")
            cacheOnlineSources = cacheSet?.value == "true"
            
            val overrideSet = database.appSettingDao().getSetting("continue_override")
            isOverrideActive = overrideSet?.value == "true"

            val speedSet = database.appSettingDao().getSetting("transition_speed")
            transitionSpeed = speedSet?.value ?: "800"

            val typeSet = database.appSettingDao().getSetting("transition_type")
            transitionType = typeSet?.value ?: "Crossfade"

            val globalRandomSet = database.appSettingDao().getSetting("global_random_order")
            globalRandomOrder = globalRandomSet?.value == "true"

            val rotateEnabledSet = database.appSettingDao().getSetting("global_rotate_enabled")
            globalRotateEnabled = rotateEnabledSet?.value == "true"
            
            val rotateValueSet = database.appSettingDao().getSetting("global_rotate_time_value")
            globalRotateValue = rotateValueSet?.value ?: "10"
            
            val rotateUnitSet = database.appSettingDao().getSetting("global_rotate_time_unit")
            globalRotateUnit = rotateUnitSet?.value ?: "Minutes"

            val themeSet = database.appSettingDao().getSetting("app_theme")
            appTheme = themeSet?.value ?: "System"

            val unlockSet = database.appSettingDao().getSetting("change_on_unlock")
            changeOnUnlock = unlockSet?.value == "true"

            val homeSet = database.appSettingDao().getSetting("change_on_homescreen")
            changeOnHomeScreen = homeSet?.value == "true"

            val batterySaverSet = database.appSettingDao().getSetting("battery_saver_screen_off")
            batterySaverScreenOff = batterySaverSet?.value == "true"

            val dimmingEnabledSet = database.appSettingDao().getSetting("global_dimming_enabled")
            globalDimmingEnabled = dimmingEnabledSet?.value == "true"
            val dimmingPercentSet = database.appSettingDao().getSetting("global_dimming_percent")
            globalDimmingPercent = dimmingPercentSet?.value?.toIntOrNull() ?: 0

            val blurEnabledSet = database.appSettingDao().getSetting("global_blur_enabled")
            globalBlurEnabled = blurEnabledSet?.value == "true"
            val blurPercentSet = database.appSettingDao().getSetting("global_blur_percent")
            globalBlurPercent = blurPercentSet?.value?.toIntOrNull() ?: 0

            val greyscaleEnabledSet = database.appSettingDao().getSetting("global_greyscale_enabled")
            globalGreyscaleEnabled = greyscaleEnabledSet?.value == "true"
            val greyscalePercentSet = database.appSettingDao().getSetting("global_greyscale_percent")
            globalGreyscalePercent = greyscalePercentSet?.value?.toIntOrNull() ?: 0

            listOf("DoubleTap", "TwoFingerDoubleTap", "ThreeFingerDoubleTap").forEach { gesture ->
                val key = "gesture_$gesture"
                val setting = database.appSettingDao().getSetting(key)
                gestureActions[gesture] = setting?.value ?: when (gesture) {
                    "DoubleTap" -> "NextPhoto"
                    "TwoFingerDoubleTap" -> "Freeze"
                    "ThreeFingerDoubleTap" -> "NextPhoto"
                    else -> "None"
                }
            }

            val activeGestureRules = database.automationRuleDao().getAllRules().filter { it.isEnabled && it.type == "Gesture" }
            gesturesInUse = activeGestureRules.mapNotNull { it.gestureType }.toSet()

            allFilesAccessGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }

            val activeInfo = wallpaperManager.wallpaperInfo
            isWallpaperActive = activeInfo != null && activeInfo.packageName == context.packageName
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        refreshSettings()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Global Settings", style = MaterialTheme.typography.headlineSmall)
        
        HorizontalDivider()

        if (!isWallpaperActive) {
            // 1. Live Wallpaper Shortcut
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Live Wallpaper Status", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Enable the Dynamic Live Wallpaper Service to support home screen double-taps.", 
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            // User-triggered action only. Android requires explicit user intervention via ACTION_CHANGE_LIVE_WALLPAPER
                            // activity intent. We ensure the app never redirects or prompts setting itself automatically on start/boot.
                            val intent = Intent().apply {
                                action = android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                                putExtra(
                                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, DynamicWallpaperService::class.java)
                                )
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Set Live Wallpaper")
                    }
                }
            }
        }

        if (!allFilesAccessGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Storage Permission Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "To access wallpapers in hidden folders (starting with '.') or directories containing '.nomedia' files, please grant All Files Access in system settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intentFallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                context.startActivity(intentFallback)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Grant All Files Access")
                    }
                }
            }
        }

        // Global Random Order
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Global Random Order", style = MaterialTheme.typography.titleMedium)
                Text("Randomize photo changes for all active albums, overriding normal order.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = globalRandomOrder,
                onCheckedChange = { checked ->
                    globalRandomOrder = checked
                    scope.launch {
                        database.appSettingDao().insertSetting(AppSetting("global_random_order", checked.toString()))
                    }
                }
            )
        }


        // Change Photo on Unlock
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Change Photo on Unlock", style = MaterialTheme.typography.titleMedium)
                Text("Rotate wallpaper automatically when the device is unlocked.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = changeOnUnlock,
                onCheckedChange = { checked ->
                    changeOnUnlock = checked
                    scope.launch {
                        database.appSettingDao().insertSetting(AppSetting("change_on_unlock", checked.toString()))
                    }
                }
            )
        }

        // Change Photo on Home Screen
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Change Photo on Home Screen", style = MaterialTheme.typography.titleMedium)
                Text("Rotate wallpaper automatically when navigating back to the home screen.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = changeOnHomeScreen,
                onCheckedChange = { checked ->
                    changeOnHomeScreen = checked
                    scope.launch {
                        database.appSettingDao().insertSetting(AppSetting("change_on_homescreen", checked.toString()))
                    }
                }
            )
        }

        HorizontalDivider()

        Text("Global Effects Overrides", style = MaterialTheme.typography.titleMedium)
        Text("Force visual effects globally across all active wallpaper rules.", style = MaterialTheme.typography.bodySmall)

        // Global Dimming
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Global Dimming", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = globalDimmingEnabled,
                    onCheckedChange = { checked ->
                        globalDimmingEnabled = checked
                        scope.launch {
                            database.appSettingDao().insertSetting(AppSetting("global_dimming_enabled", checked.toString()))
                            context.startService(Intent(context, AutomationService::class.java))
                        }
                    }
                )
            }
            if (globalDimmingEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Slider(
                        value = globalDimmingPercent.toFloat(),
                        onValueChange = { newValue ->
                            globalDimmingPercent = newValue.toInt()
                            scope.launch {
                                database.appSettingDao().insertSetting(AppSetting("global_dimming_percent", newValue.toInt().toString()))
                                context.startService(Intent(context, AutomationService::class.java))
                            }
                        },
                        valueRange = 0f..100f,
                        steps = 99,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${globalDimmingPercent}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(45.dp))
                }
            }
        }

        // Global Blur
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Global Blur", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = globalBlurEnabled,
                    onCheckedChange = { checked ->
                        globalBlurEnabled = checked
                        scope.launch {
                            database.appSettingDao().insertSetting(AppSetting("global_blur_enabled", checked.toString()))
                            context.startService(Intent(context, AutomationService::class.java))
                        }
                    }
                )
            }
            if (globalBlurEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Slider(
                        value = globalBlurPercent.toFloat(),
                        onValueChange = { newValue ->
                            globalBlurPercent = newValue.toInt()
                            scope.launch {
                                database.appSettingDao().insertSetting(AppSetting("global_blur_percent", newValue.toInt().toString()))
                                context.startService(Intent(context, AutomationService::class.java))
                            }
                        },
                        valueRange = 0f..100f,
                        steps = 99,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${globalBlurPercent}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(45.dp))
                }
            }
        }

        // Global Greyscale
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Global Greyscale", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = globalGreyscaleEnabled,
                    onCheckedChange = { checked ->
                        globalGreyscaleEnabled = checked
                        scope.launch {
                            database.appSettingDao().insertSetting(AppSetting("global_greyscale_enabled", checked.toString()))
                            context.startService(Intent(context, AutomationService::class.java))
                        }
                    }
                )
            }
            if (globalGreyscaleEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Slider(
                        value = globalGreyscalePercent.toFloat(),
                        onValueChange = { newValue ->
                            globalGreyscalePercent = newValue.toInt()
                            scope.launch {
                                database.appSettingDao().insertSetting(AppSetting("global_greyscale_percent", newValue.toInt().toString()))
                                context.startService(Intent(context, AutomationService::class.java))
                            }
                        },
                        valueRange = 0f..100f,
                        steps = 99,
                        modifier = Modifier.weight(1f)
                    )
                    Text("${globalGreyscalePercent}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(45.dp))
                }
            }
        }

        HorizontalDivider()

        // 4. Override active status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Manual Selection Lock", style = MaterialTheme.typography.titleMedium)
                Text("If active, manual choices ignore automation rules permanent freeze.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = isOverrideActive,
                onCheckedChange = { checked ->
                    isOverrideActive = checked
                    scope.launch {
                        database.appSettingDao().insertSetting(AppSetting("continue_override", checked.toString()))
                        // Start service command to adapt FGS notification state
                        context.startService(Intent(context, AutomationService::class.java))
                    }
                }
            )
        }

        HorizontalDivider()

        // 4.1 Periodic Wallpaper Rotation Setting
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Rotate Image Periodically", style = MaterialTheme.typography.titleMedium)
                    Text("Automatically rotate the wallpaper of the active album after a set interval.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = globalRotateEnabled,
                    onCheckedChange = { checked ->
                        globalRotateEnabled = checked
                        scope.launch {
                            database.appSettingDao().insertSetting(AppSetting("global_rotate_enabled", checked.toString()))
                            context.startService(Intent(context, AutomationService::class.java))
                        }
                    }
                )
            }
            
            if (globalRotateEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = globalRotateValue,
                        onValueChange = { newValue ->
                            val filtered = newValue.filter { it.isDigit() }
                            globalRotateValue = filtered
                            scope.launch {
                                database.appSettingDao().insertSetting(AppSetting("global_rotate_time_value", filtered))
                                context.startService(Intent(context, AutomationService::class.java))
                            }
                        },
                        label = { Text("Interval Value") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    
                    var unitExpanded by remember { mutableStateOf(false) }
                    val unitOptions = listOf("Seconds", "Minutes", "Hours", "Days")
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = globalRotateUnit,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Interval Unit") },
                            trailingIcon = {
                                IconButton(onClick = { unitExpanded = true }) {
                                    Icon(
                                        imageVector = if (unitExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Select unit"
                                    )
                                }
                            },
                            modifier = Modifier.clickable { unitExpanded = true }
                        )
                        DropdownMenu(
                            expanded = unitExpanded,
                            onDismissRequest = { unitExpanded = false }
                        ) {
                            unitOptions.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit) },
                                    onClick = {
                                        globalRotateUnit = unit
                                        unitExpanded = false
                                        scope.launch {
                                            database.appSettingDao().insertSetting(AppSetting("global_rotate_time_unit", unit))
                                            context.startService(Intent(context, AutomationService::class.java))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Transition Speed setting
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Transition Speed", style = MaterialTheme.typography.titleMedium)
                Text("Control how fast wallpapers fade in/out.", style = MaterialTheme.typography.bodySmall)
            }
            var expanded by remember { mutableStateOf(false) }
            val speedOptions = listOf(
                "0" to "Instant (No Animation)",
                "400" to "Fast (400ms)",
                "800" to "Medium (800ms)",
                "1500" to "Slow (1.5s)",
                "3000" to "Very Slow (3.0s)"
            )
            val currentSpeedLabel = speedOptions.find { it.first == transitionSpeed }?.second ?: "Medium (800ms)"
            Box {
                Text(
                    text = currentSpeedLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    speedOptions.forEach { (speedValue, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                scope.launch {
                                    database.appSettingDao().insertSetting(AppSetting("transition_speed", speedValue))
                                    transitionSpeed = speedValue
                                    expanded = false
                                }
                            }
                        )
                    }
                }
            }
        }

        // Transition Effect setting
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Transition Effect", style = MaterialTheme.typography.titleMedium)
                Text("Select the background transition animation.", style = MaterialTheme.typography.bodySmall)
            }
            var expanded by remember { mutableStateOf(false) }
            val typeOptions = listOf(
                "Crossfade" to "Crossfade",
                "FadeToBlack" to "Fade to Black",
                "SlideLeft" to "Slide Left",
                "SlideRight" to "Slide Right",
                "SlideUp" to "Slide Up",
                "SlideDown" to "Slide Down"
            )
            val currentTypeLabel = typeOptions.find { it.first == transitionType }?.second ?: "Crossfade"
            Box {
                Text(
                    text = currentTypeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { expanded = true }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    typeOptions.forEach { (typeValue, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                scope.launch {
                                    database.appSettingDao().insertSetting(AppSetting("transition_type", typeValue))
                                    transitionType = typeValue
                                    expanded = false
                                }
                            }
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        // 6. Gesture controls mappings
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Gesture Controls", style = MaterialTheme.typography.titleMedium)
            Text("Configure what happens when you tap the home screen.", style = MaterialTheme.typography.bodySmall)
            
            val gestureList = listOf(
                "DoubleTap" to "Double Tap",
                "TwoFingerDoubleTap" to "Double Tap 2 Fingers",
                "ThreeFingerDoubleTap" to "Double Tap 3 Fingers"
            )

            val actionOptions = listOf(
                "NextPhoto" to "Next Photo",
                "LastPhoto" to "Last Photo",
                "Freeze" to "Play/Pause Photo Rotations",
                "None" to "None (Disabled)"
            )

            gestureList.forEach { (gestureKey, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    
                    var expanded by remember { mutableStateOf(false) }
                    val currentAction = gestureActions[gestureKey] ?: when (gestureKey) {
                        "DoubleTap" -> "NextPhoto"
                        "TwoFingerDoubleTap" -> "Freeze"
                        "ThreeFingerDoubleTap" -> "NextPhoto"
                        else -> "None"
                    }
                    val displayActionName = if (gesturesInUse.contains(gestureKey)) {
                        "Custom Controls"
                    } else {
                        actionOptions.find { it.first == currentAction }?.second ?: "Next Photo"
                    }
                    
                    Box {
                        Text(
                            text = displayActionName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { expanded = true }
                                .padding(vertical = 4.dp, horizontal = 8.dp)
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            actionOptions.forEach { (actionKey, actionLabel) ->
                                DropdownMenuItem(
                                    text = { Text(actionLabel) },
                                    onClick = {
                                        scope.launch {
                                            database.appSettingDao().insertSetting(AppSetting("gesture_$gestureKey", actionKey))
                                            gestureActions[gestureKey] = actionKey
                                            expanded = false
                                            context.startService(Intent(context, AutomationService::class.java))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // App Theme Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("App Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("System" to "System Default", "Light" to "Light Mode", "Dark" to "Dark Mode").forEach { (themeKey, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    appTheme = themeKey
                                    scope.launch {
                                        database.appSettingDao().insertSetting(AppSetting("app_theme", themeKey))
                                    }
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = appTheme == themeKey,
                                onClick = {
                                    appTheme = themeKey
                                    scope.launch {
                                        database.appSettingDao().insertSetting(AppSetting("app_theme", themeKey))
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareImportDialog(
    sharedUris: List<Uri>,
    database: AppDatabase,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var albums by remember { mutableStateOf(emptyList<com.wallpaper.changer.data.Album>()) }
    var selectedAlbumId by remember { mutableStateOf<Long?>(null) }
    var newAlbumName by remember { mutableStateOf("") }
    
    var isImporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        albums = database.albumDao().getAll()
        if (albums.isNotEmpty()) {
            selectedAlbumId = albums.first().id
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Shared Photo(s)") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("You shared ${sharedUris.size} photo(s). Select an existing album or create a new one below:")
                
                if (albums.isNotEmpty()) {
                    Text("Select Album:", style = MaterialTheme.typography.titleSmall)
                    var expanded by remember { mutableStateOf(false) }
                    val selectedAlbumName = albums.find { it.id == selectedAlbumId }?.name ?: "Select Album"
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedAlbumName)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            albums.forEach { album ->
                                DropdownMenuItem(
                                    text = { Text(album.name) },
                                    onClick = {
                                        selectedAlbumId = album.id
                                        newAlbumName = ""
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                Text("Or Create New Album:", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = newAlbumName,
                    onValueChange = {
                        newAlbumName = it
                        if (it.isNotBlank()) {
                            selectedAlbumId = null
                        }
                    },
                    placeholder = { Text("Enter new album name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                if (isImporting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isImporting && (selectedAlbumId != null || newAlbumName.isNotBlank()),
                onClick = {
                    isImporting = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val albumId = if (newAlbumName.isNotBlank()) {
                                database.albumDao().insert(com.wallpaper.changer.data.Album(name = newAlbumName.trim()))
                            } else {
                                selectedAlbumId!!
                            }
                            
                            val photos = sharedUris.mapNotNull { uri ->
                                val name = getOriginalDisplayName(context, uri)
                                val localPath = copyUriToInternal(context, uri, name)
                                if (localPath != null) {
                                    com.wallpaper.changer.data.Photo(albumId = albumId, path = localPath)
                                } else {
                                    null
                                }
                            }
                            database.photoDao().insertAll(photos)
                            
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Successfully imported ${photos.size} photo(s)!", Toast.LENGTH_SHORT).show()
                                isImporting = false
                                onDismiss()
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Failed to import photos: ${e.message}", Toast.LENGTH_LONG).show()
                                isImporting = false
                            }
                        }
                    }
                }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isImporting,
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}
