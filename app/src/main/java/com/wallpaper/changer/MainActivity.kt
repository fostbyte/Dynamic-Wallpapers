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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val database = (application as WallpaperChangerApplication).database
        
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigationContainer(database)
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
                            val intent = Intent().apply {
                                action = android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER
                                putExtra(
                                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, DynamicWallpaperService::class.java)
                                )
                            }
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
                    ) {
                        Text("Set Live Wallpaper")
                    }
                }
            }
        }

        // App Theme Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("App Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("System" to "System Default", "Light" to "Light Mode", "Dark" to "Dark Mode").forEach { (themeKey, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                appTheme = themeKey
                                scope.launch {
                                    database.appSettingDao().insertSetting(AppSetting("app_theme", themeKey))
                                }
                            }
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
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        // 3. Cache toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Cache Online Sources", style = MaterialTheme.typography.titleMedium)
                Text("Cache files from online sources locally on disk.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = cacheOnlineSources,
                onCheckedChange = { checked ->
                    cacheOnlineSources = checked
                    scope.launch {
                        database.appSettingDao().insertSetting(AppSetting("cache_online_sources", checked.toString()))
                    }
                }
            )
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

        // 4.2 Google Photos & Media Access Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Google Photos & Media Access", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Manage storage permissions (Allow All, Limited Access, or None).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                val hasImagesPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                val hasVisualSelectionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                } else {
                    false
                }
                
                val accessText = when {
                    hasImagesPermission -> "Allow All (Full Access)"
                    hasVisualSelectionPermission -> "Limited Access (User Selected)"
                    else -> "None (Access Denied)"
                }
                
                Text("Current Access: $accessText", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Text("Change Photo Access")
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
                "NextAlbum" to "Next Album",
                "LastAlbum" to "Last Album",
                "Freeze" to "Freeze / Resume",
                "Custom" to "Custom Rules Trigger",
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
                    val displayActionName = actionOptions.find { it.first == currentAction }?.second ?: "Next Photo"
                    
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
    }
}
