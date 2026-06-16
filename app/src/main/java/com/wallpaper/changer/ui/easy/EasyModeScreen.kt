package com.wallpaper.changer.ui.easy

import android.annotation.SuppressLint
import android.content.Context
import android.app.TimePickerDialog
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wallpaper.changer.data.Album
import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.AutomationRule
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyModeScreen(database: AppDatabase) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var rules by remember { mutableStateOf(emptyList<AutomationRule>()) }
    var albums by remember { mutableStateOf(emptyList<Album>()) }
    
    var isEditingRule by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AutomationRule?>(null) }
    
    fun refresh() {
        scope.launch {
            rules = database.automationRuleDao().getAllRules()
            albums = database.albumDao().getAll()
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    if (isEditingRule) {
        AddRulePanel(
            rule = editingRule,
            albums = albums,
            onBack = {
                isEditingRule = false
                editingRule = null
            },
            onSave = { ruleToSave ->
                scope.launch {
                    if (ruleToSave.id == 0L) {
                        // New rule, priority is max + 1
                        val nextPriority = (rules.maxOfOrNull { it.priority } ?: 0) + 1
                        database.automationRuleDao().insert(ruleToSave.copy(priority = nextPriority))
                    } else {
                        database.automationRuleDao().update(ruleToSave)
                    }
                    refresh()
                    isEditingRule = false
                    editingRule = null
                }
            },
            onDelete = { ruleToDelete ->
                scope.launch {
                    database.automationRuleDao().delete(ruleToDelete)
                    refresh()
                    isEditingRule = false
                    editingRule = null
                }
            }
        )
    } else {
        // Rules list showing priorities (drag-and-drop reorder proxy via Up/Down keys)
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Automation Rules", style = MaterialTheme.typography.headlineSmall)
                Button(
                    onClick = {
                        editingRule = null
                        isEditingRule = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Rule")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Rule")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Priority Hierarchy: Rules at the top have higher priority and resolve conflicts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (rules.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No automation rules defined yet.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(rules) { index, rule ->
                        RuleListRow(
                            rule = rule,
                            albums = albums,
                            canMoveUp = index > 0,
                            canMoveDown = index < rules.lastIndex,
                            onMoveUp = {
                                scope.launch {
                                    val upperRule = rules[index - 1]
                                    val tempPriority = rule.priority
                                    database.automationRuleDao().update(rule.copy(priority = upperRule.priority))
                                    database.automationRuleDao().update(upperRule.copy(priority = tempPriority))
                                    refresh()
                                }
                            },
                            onMoveDown = {
                                scope.launch {
                                    val lowerRule = rules[index + 1]
                                    val tempPriority = rule.priority
                                    database.automationRuleDao().update(rule.copy(priority = lowerRule.priority))
                                    database.automationRuleDao().update(lowerRule.copy(priority = tempPriority))
                                    refresh()
                                }
                            },
                            onClick = {
                                editingRule = rule
                                isEditingRule = true
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RuleListRow(
    rule: AutomationRule,
    albums: List<Album>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Reorder controls
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                
                val triggerDesc = when (rule.type) {
                    "Time" -> "Time: ${rule.time} (${rule.daysOfWeek ?: "All Days"})"
                    "WiFi" -> {
                        val ssidText = if (!rule.wifiSsid.isNullOrBlank()) " (${rule.wifiSsid})" else ""
                        "WiFi: ${rule.wifiState}$ssidText"
                    }
                    "Interval" -> "Interval: Every ${rule.intervalValue} ${rule.intervalUnit}"
                    "Gesture" -> "Gesture: ${rule.gestureType}"
                    "Unlock" -> "Device Unlocked"
                    "HomeScreen" -> "Go to Home Screen"
                    else -> "Geofence: Lat=${rule.latitude?.toString()?.take(7)}, Lng=${rule.longitude?.toString()?.take(7)} (radius=${rule.radius}m, ${rule.locationCondition ?: "Entering"})"
                }
                
                val actionDesc = if (rule.actionType == "NextWallpaper") {
                    "Action: Next Wallpaper"
                } else {
                    val albumName = albums.find { it.id == rule.targetAlbumId }?.name ?: "Unknown Album"
                    "Action: Switch to '$albumName'"
                }
                
                Text(triggerDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(actionDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRulePanel(
    rule: AutomationRule?,
    albums: List<Album>,
    onBack: () -> Unit,
    onSave: (AutomationRule) -> Unit,
    onDelete: (AutomationRule) -> Unit
) {
    val context = LocalContext.current
    
    // Panel configurations matching screenshot.png
    var ruleName by remember { mutableStateOf(rule?.name ?: "") }
    
    // Multiple Trigger switches
    var isTimeEnabled by remember { mutableStateOf(rule?.time != null || rule?.type == "Time" || (rule == null && rule?.type != "Interval" && rule?.type != "WiFi")) }
    var isLocationEnabled by remember { mutableStateOf(rule?.latitude != null || rule?.type == "Location") }
    var isIntervalEnabled by remember { mutableStateOf(rule?.type == "Interval") }
    var isWifiEnabled by remember { mutableStateOf(rule?.type == "WiFi") }
    
    var connectionLogic by remember { mutableStateOf(rule?.connectionLogic ?: "AND") }
    var showLogicDropdown by remember { mutableStateOf(false) }
    
    // Time states
    var timeStr by remember { mutableStateOf(rule?.time ?: "17:35") }
    var selectedDays by remember { 
        mutableStateOf(
            rule?.daysOfWeek?.split(",")?.toSet() ?: setOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        )
    }
    
    // Location states
    var latText by remember { mutableStateOf(rule?.latitude?.toString() ?: "37.7749") }
    var lngText by remember { mutableStateOf(rule?.longitude?.toString() ?: "-122.4194") }
    var radiusText by remember { mutableStateOf(rule?.radius?.toString() ?: "100.0") }
    var locationCondition by remember { mutableStateOf(rule?.locationCondition ?: "Entering") }

    // WiFi states
    var wifiState by remember { mutableStateOf(rule?.wifiState ?: "Connecting") }
    var wifiSsid by remember { mutableStateOf(rule?.wifiSsid ?: "") }

    // Interval states
    var intervalValText by remember { mutableStateOf(rule?.intervalValue?.toString() ?: "10") }
    var intervalUnit by remember { mutableStateOf(rule?.intervalUnit ?: "Minutes") }
    var randomOrder by remember { mutableStateOf(rule?.randomOrder ?: false) }
    var showIntervalUnitDropdown by remember { mutableStateOf(false) }



    // Action states
    var actionType by remember { mutableStateOf(rule?.actionType ?: "NextWallpaper") } // "NextWallpaper", "SwitchAlbum"
    var selectedAlbumIds by remember {
        mutableStateOf(
            rule?.targetAlbumIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet()
                ?: if (rule?.targetAlbumId != null) setOf(rule.targetAlbumId) else emptySet()
        )
    }
    var showAlbumDropdown by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Green Header bar matching screenshot.png
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (rule == null) "Add rule" else "Edit rule", 
                    color = Color.White, 
                    style = MaterialTheme.typography.titleLarge
                )
            }
            if (rule != null) {
                IconButton(onClick = { onDelete(rule) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value = ruleName,
                    onValueChange = { ruleName = it },
                    label = { Text("Rule Description (e.g., Work Profile)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Event Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Event Triggers", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Time Trigger Switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { 
                                isTimeEnabled = !isTimeEnabled 
                                if (isTimeEnabled) {
                                    isIntervalEnabled = false
                                }
                            }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Time Schedule Trigger", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = isTimeEnabled, 
                                onCheckedChange = { checked -> 
                                    isTimeEnabled = checked
                                    if (checked) {
                                        isIntervalEnabled = false
                                    }
                                }
                            )
                        }
 
                        if (isTimeEnabled) {
                            Column(modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                                // Time picker clickable text
                                Text(
                                    text = timeStr,
                                    fontSize = 32.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        val cal = Calendar.getInstance()
                                        val parts = timeStr.split(":")
                                        val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: cal.get(Calendar.HOUR_OF_DAY)
                                        val initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: cal.get(Calendar.MINUTE)
                                        
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute ->
                                                timeStr = String.format("%02d:%02d", hour, minute)
                                            },
                                            initialHour,
                                            initialMinute,
                                            true
                                        ).show()
                                    }
                                )
 
                                Spacer(modifier = Modifier.height(16.dp))
 
                                // Days selection row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                                        val isSelected = selectedDays.contains(day)
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    selectedDays = if (isSelected) {
                                                        selectedDays - day
                                                    } else {
                                                        selectedDays + day
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = day,
                                                fontSize = 11.sp,
                                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
 
                        Spacer(modifier = Modifier.height(8.dp))
 
                        // Location Trigger Switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { 
                                isLocationEnabled = !isLocationEnabled 
                                if (isLocationEnabled) {
                                    isIntervalEnabled = false
                                }
                            }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Geofence Location Trigger", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = isLocationEnabled, 
                                onCheckedChange = { checked -> 
                                    isLocationEnabled = checked
                                    if (checked) {
                                        isIntervalEnabled = false
                                    }
                                }
                            )
                        }
 
                        if (isLocationEnabled) {
                            Column(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Leaflet Map WebView
                                Card(
                                    modifier = Modifier.fillMaxWidth().height(250.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    val latVal = latText.toDoubleOrNull() ?: 37.7749
                                    val lngVal = lngText.toDoubleOrNull() ?: -122.4194
                                    val radVal = radiusText.toFloatOrNull() ?: 100f
                                    
                                    MapPickerWebView(
                                        initialLat = latVal,
                                        initialLng = lngVal,
                                        initialRadius = radVal,
                                        onLocationSelected = { lat, lng ->
                                            latText = String.format(java.util.Locale.US, "%.6f", lat)
                                            lngText = String.format(java.util.Locale.US, "%.6f", lng)
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                OutlinedTextField(
                                    value = radiusText,
                                    onValueChange = { radiusText = it },
                                    label = { Text("Geofence Radius (meters)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Trigger when:")
                                    Spacer(modifier = Modifier.width(16.dp))
                                    RadioButton(
                                        selected = locationCondition == "Entering",
                                        onClick = { locationCondition = "Entering" }
                                    )
                                    Text("Entering")
                                    Spacer(modifier = Modifier.width(16.dp))
                                    RadioButton(
                                        selected = locationCondition == "Leaving",
                                        onClick = { locationCondition = "Leaving" }
                                    )
                                    Text("Leaving")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Interval Trigger Switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { 
                                isIntervalEnabled = !isIntervalEnabled 
                                if (isIntervalEnabled) {
                                    isTimeEnabled = false
                                    isLocationEnabled = false
                                    isWifiEnabled = false
                                }
                            }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Interval Schedule Trigger", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = isIntervalEnabled, 
                                onCheckedChange = { checked -> 
                                    isIntervalEnabled = checked
                                    if (checked) {
                                        isTimeEnabled = false
                                        isLocationEnabled = false
                                        isWifiEnabled = false
                                    }
                                }
                            )
                        }

                        if (isIntervalEnabled) {
                            Column(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = intervalValText,
                                        onValueChange = { intervalValText = it },
                                        label = { Text("Change Photo Every") },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        OutlinedTextField(
                                            value = intervalUnit,
                                            onValueChange = {},
                                            readOnly = true,
                                            label = { Text("Unit") },
                                            modifier = Modifier.fillMaxWidth().clickable { showIntervalUnitDropdown = true },
                                            trailingIcon = {
                                                IconButton(onClick = { showIntervalUnitDropdown = true }) {
                                                    Text("▼", fontSize = 10.sp)
                                                }
                                            }
                                        )
                                        DropdownMenu(
                                            expanded = showIntervalUnitDropdown,
                                            onDismissRequest = { showIntervalUnitDropdown = false }
                                        ) {
                                            listOf("Seconds", "Minutes", "Hours", "Days").forEach { unit ->
                                                DropdownMenuItem(
                                                    text = { Text(unit) },
                                                    onClick = {
                                                        intervalUnit = unit
                                                        showIntervalUnitDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Switch(
                                        checked = randomOrder,
                                        onCheckedChange = { randomOrder = it }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Random order rotation")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // WiFi Trigger Switch
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { 
                                isWifiEnabled = !isWifiEnabled 
                                if (isWifiEnabled) {
                                    isIntervalEnabled = false
                                }
                            }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("WiFi Connection Trigger", style = MaterialTheme.typography.bodyLarge)
                            Switch(
                                checked = isWifiEnabled, 
                                onCheckedChange = { checked -> 
                                    isWifiEnabled = checked
                                    if (checked) {
                                        isIntervalEnabled = false
                                    }
                                }
                            )
                        }

                        if (isWifiEnabled) {
                            Column(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Event:")
                                    Spacer(modifier = Modifier.width(16.dp))
                                    RadioButton(
                                        selected = wifiState == "Connecting",
                                        onClick = { wifiState = "Connecting" }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Connecting")
                                    Spacer(modifier = Modifier.width(16.dp))
                                    RadioButton(
                                        selected = wifiState == "Disconnecting",
                                        onClick = { wifiState = "Disconnecting" }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Disconnecting")
                                }

                                val wifiList = rememberWifiSsids(context)
                                var showWifiDropdown by remember { mutableStateOf(false) }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = wifiSsid,
                                        onValueChange = { wifiSsid = it },
                                        label = { Text("WiFi SSID (Optional)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        trailingIcon = {
                                            if (wifiList.isNotEmpty()) {
                                                IconButton(onClick = { showWifiDropdown = !showWifiDropdown }) {
                                                    Icon(
                                                        imageVector = Icons.Default.ArrowDropDown,
                                                        contentDescription = "Show known Wi-Fi networks"
                                                    )
                                                }
                                            }
                                        }
                                    )
                                    if (wifiList.isNotEmpty()) {
                                        DropdownMenu(
                                            expanded = showWifiDropdown,
                                            onDismissRequest = { showWifiDropdown = false },
                                            modifier = Modifier.fillMaxWidth(0.9f)
                                        ) {
                                            wifiList.forEach { ssid ->
                                                DropdownMenuItem(
                                                    text = { Text(ssid) },
                                                    onClick = {
                                                        wifiSsid = ssid
                                                        showWifiDropdown = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val enabledEventCount = (if (isTimeEnabled) 1 else 0) + (if (isLocationEnabled) 1 else 0) + (if (isWifiEnabled) 1 else 0)
                        if (enabledEventCount >= 2) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Trigger Logic statement relation:", style = MaterialTheme.typography.bodyMedium)
                                Box {
                                    Button(
                                        onClick = { showLogicDropdown = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text(connectionLogic)
                                    }
                                    DropdownMenu(
                                        expanded = showLogicDropdown,
                                        onDismissRequest = { showLogicDropdown = false }
                                    ) {
                                        listOf("AND", "OR", "XOR").forEach { logic ->
                                            DropdownMenuItem(
                                                text = { Text(logic) },
                                                onClick = {
                                                    connectionLogic = logic
                                                    showLogicDropdown = false
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
            // Action Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Action", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Next Wallpaper Radio
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = actionType == "NextWallpaper",
                                onClick = { actionType = "NextWallpaper" }
                            )
                            Text("Next wallpaper", style = MaterialTheme.typography.bodyLarge)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Switch Album Radio
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = actionType == "SwitchAlbum",
                                onClick = { actionType = "SwitchAlbum" }
                            )
                            Text("Switch to album:", style = MaterialTheme.typography.bodyLarge)
                            
                            if (actionType == "SwitchAlbum" && albums.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                val displayNames = if (selectedAlbumIds.isEmpty()) {
                                    "Select albums..."
                                } else {
                                    albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }
                                }
                                Box {
                                    Text(
                                        text = displayNames,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { showAlbumDropdown = true }
                                    )
                                    DropdownMenu(
                                        expanded = showAlbumDropdown,
                                        onDismissRequest = { showAlbumDropdown = false }
                                    ) {
                                        albums.forEach { album ->
                                            val isChecked = selectedAlbumIds.contains(album.id)
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Checkbox(checked = isChecked, onCheckedChange = null)
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(album.name)
                                                    }
                                                },
                                                onClick = {
                                                    selectedAlbumIds = if (isChecked) {
                                                        selectedAlbumIds - album.id
                                                    } else {
                                                        selectedAlbumIds + album.id
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (!isIntervalEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { randomOrder = !randomOrder }
                            ) {
                                Switch(
                                    checked = randomOrder,
                                    onCheckedChange = { randomOrder = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Random order rotation", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            // Box 3: Logic Statement Selection Box
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Rule Logic Statement", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        val enabledEventCount = (if (isTimeEnabled) 1 else 0) + (if (isLocationEnabled) 1 else 0) + (if (isWifiEnabled) 1 else 0)
                        val locVerb = if (locationCondition == "Leaving") "leaves" else "enters"
                        val statement = when {
                            enabledEventCount >= 2 -> {
                                val relationWord = " $connectionLogic "
                                val conditions = mutableListOf<String>()
                                if (isTimeEnabled) {
                                    conditions.add("clock hits $timeStr on [${selectedDays.joinToString(", ")}]")
                                }
                                if (isLocationEnabled) {
                                    conditions.add("device $locVerb geofence (Lat=${latText.take(5)}, Lng=${lngText.take(5)})")
                                }
                                if (isWifiEnabled) {
                                    val ssidText = if (wifiSsid.isNotBlank()) " to '$wifiSsid'" else ""
                                    conditions.add("device is $wifiState WiFi$ssidText")
                                }
                                val actionWord = if (actionType == "NextWallpaper") {
                                    "execute Next Wallpaper"
                                } else {
                                    val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                    "set active playlist to '$albumName'"
                                }
                                "IF ${conditions.joinToString(relationWord)} THEN $actionWord."
                            }
                            isTimeEnabled && actionType == "NextWallpaper" -> {
                                "IF clock hits $timeStr on [${selectedDays.joinToString(", ")}] THEN execute Next Wallpaper."
                            }
                            isTimeEnabled && actionType == "SwitchAlbum" -> {
                                val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                "IF clock hits $timeStr on [${selectedDays.joinToString(", ")}] THEN set active playlist to '$albumName'."
                            }
                            isLocationEnabled && actionType == "NextWallpaper" -> {
                                "IF device $locVerb geofence (Lat=${latText.take(5)}, Lng=${lngText.take(5)}) THEN execute Next Wallpaper."
                            }
                            isLocationEnabled && actionType == "SwitchAlbum" -> {
                                val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                "IF device $locVerb geofence (Lat=${latText.take(5)}, Lng=${lngText.take(5)}) THEN set active playlist to '$albumName'."
                            }
                            isIntervalEnabled && actionType == "NextWallpaper" -> {
                                val orderText = if (randomOrder) "in Random order" else "in Sequential order"
                                "Rotate wallpapers inside active album every $intervalValText $intervalUnit $orderText."
                            }
                            isIntervalEnabled && actionType == "SwitchAlbum" -> {
                                val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                "Rotate wallpapers inside active album every $intervalValText $intervalUnit and switch to playlist '$albumName'."
                            }
                            isWifiEnabled && actionType == "NextWallpaper" -> {
                                val ssidText = if (wifiSsid.isNotBlank()) " to '$wifiSsid'" else ""
                                "IF device is $wifiState WiFi$ssidText THEN execute Next Wallpaper."
                            }
                            isWifiEnabled && actionType == "SwitchAlbum" -> {
                                val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                val ssidText = if (wifiSsid.isNotBlank()) " to '$wifiSsid'" else ""
                                "IF device is $wifiState WiFi$ssidText THEN set active playlist to '$albumName'."
                            }

                            else -> {
                                "Please select at least one trigger to save this rule."
                            }
                        }
                        
                        Text(statement, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val daysString = if (selectedDays.size == 7) null else selectedDays.joinToString(",")
                        val lat = if (isLocationEnabled) latText.toDoubleOrNull() else null
                        val lng = if (isLocationEnabled) lngText.toDoubleOrNull() else null
                        val radius = if (isLocationEnabled) radiusText.toFloatOrNull() else null
                        
                        val enabledCount = (if (isTimeEnabled) 1 else 0) + (if (isLocationEnabled) 1 else 0) + (if (isWifiEnabled) 1 else 0)
                        
                        val nameToUse = ruleName.ifBlank {
                            when {
                                enabledCount >= 2 -> "Compound Rule ($connectionLogic)"
                                isTimeEnabled -> "Schedule at $timeStr"
                                isLocationEnabled -> "Geofence rule"
                                isIntervalEnabled -> "Interval every $intervalValText $intervalUnit"
                                isWifiEnabled -> "WiFi rule"
                                else -> "Rule"
                            }
                        }
                        
                        val newRule = AutomationRule(
                            id = rule?.id ?: 0L,
                            name = nameToUse,
                            type = when {
                                enabledCount >= 2 -> "Compound"
                                isTimeEnabled -> "Time"
                                isLocationEnabled -> "Location"
                                isIntervalEnabled -> "Interval"
                                isWifiEnabled -> "WiFi"
                                else -> "Time"
                            },
                            priority = rule?.priority ?: 0,
                            isEnabled = true,
                            daysOfWeek = if (isTimeEnabled) daysString else null,
                            time = if (isTimeEnabled) timeStr else null,
                            latitude = lat,
                            longitude = lng,
                            radius = radius,
                            locationAccuracy = "Precise",
                            locationCondition = if (isLocationEnabled) locationCondition else "Entering",
                            connectionLogic = if (enabledCount >= 2) connectionLogic else "NONE",
                            actionType = actionType,
                            targetAlbumId = if (actionType == "SwitchAlbum" && selectedAlbumIds.isNotEmpty()) selectedAlbumIds.first() else null,
                            targetAlbumIds = if (actionType == "SwitchAlbum") selectedAlbumIds.joinToString(",") else null,
                            intervalValue = if (isIntervalEnabled) intervalValText.toIntOrNull() ?: 10 else null,
                            intervalUnit = if (isIntervalEnabled) intervalUnit else null,
                            randomOrder = randomOrder,
                            wifiState = if (isWifiEnabled) wifiState else null,
                            wifiSsid = if (isWifiEnabled && wifiSsid.isNotBlank()) wifiSsid else null
                        )
                        onSave(newRule)
                    },
                    enabled = isTimeEnabled || isLocationEnabled || isIntervalEnabled || isWifiEnabled,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Save Rule", fontSize = 16.sp)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapPickerWebView(
    initialLat: Double,
    initialLng: Double,
    initialRadius: Float,
    onLocationSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    isDark: Boolean = isSystemInDarkTheme()
) {
    val primaryHex = remember(primaryColor) {
        String.format("#%06X", 0xFFFFFF and primaryColor.toArgb())
    }

    val htmlContent = remember(primaryHex, isDark) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                html, body {
                    height: 100% !important;
                    width: 100% !important;
                    margin: 0;
                    padding: 0;
                    overflow: hidden;
                }
                #map {
                    width: 100%;
                    height: 100%;
                }
                ${if (isDark) """
                .leaflet-tile-container {
                    filter: invert(100%) hue-rotate(180deg) brightness(95%) contrast(90%);
                }
                .leaflet-container {
                    background: #121016 !important;
                }
                """ else ""}
                #search-container {
                    position: absolute;
                    top: 10px;
                    left: 10px;
                    right: 10px;
                    z-index: 1000;
                    display: flex;
                    background: ${if (isDark) "#1A1722" else "white"};
                    padding: 5px;
                    border-radius: 4px;
                    box-shadow: 0 2px 4px rgba(0,0,0,0.2);
                }
                #search-input {
                    flex-grow: 1;
                    border: none;
                    padding: 8px;
                    font-size: 14px;
                    outline: none;
                    background: transparent;
                    color: ${if (isDark) "#F3F4F6" else "#111827"};
                }
                #search-button {
                    background: $primaryHex;
                    color: white;
                    border: none;
                    padding: 8px 15px;
                    border-radius: 4px;
                    cursor: pointer;
                    font-size: 14px;
                }
            </style>
        </head>
        <body>
            <div id="search-container">
                <input type="text" id="search-input" placeholder="Search address or city..." />
                <button id="search-button" onclick="searchAddress()">Search</button>
            </div>
            <div id="map"></div>
            <script>
                var map;
                var marker;
                var circle;
                try {
                    map = L.map('map', { zoomControl: false }).setView([$initialLat, $initialLng], 13);
                    L.control.zoom({ position: 'bottomright' }).addTo(map);
                    
                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        maxZoom: 19,
                        attribution: '© OpenStreetMap'
                    }).addTo(map);
 
                    marker = L.marker([$initialLat, $initialLng], {draggable: true}).addTo(map);
                    circle = L.circle([$initialLat, $initialLng], {
                        color: '$primaryHex',
                        fillColor: '$primaryHex',
                        fillOpacity: 0.4,
                        radius: $initialRadius
                    }).addTo(map);
                } catch (e) {
                    console.error("Error during map init: " + e.message);
                }

                function adjustMapSize() {
                    var mapDiv = document.getElementById('map');
                    if (mapDiv) {
                        mapDiv.style.height = window.innerHeight + 'px';
                        mapDiv.style.width = window.innerWidth + 'px';
                    }
                    if (map) {
                        map.invalidateSize();
                    }
                }

                window.addEventListener('resize', adjustMapSize);
                window.addEventListener('load', adjustMapSize);
                
                adjustMapSize();
                setTimeout(adjustMapSize, 100);
                setTimeout(adjustMapSize, 300);
                setTimeout(adjustMapSize, 800);
                setTimeout(adjustMapSize, 1500);

                function updateLocation(lat, lng) {
                    var latlng = L.latLng(lat, lng);
                    marker.setLatLng(latlng);
                    circle.setLatLng(latlng);
                    map.panTo(latlng);
                }

                function updateRadius(newRadius) {
                    circle.setRadius(newRadius);
                }

                function updateMarkerPosition(lat, lng) {
                    var latlng = L.latLng(lat, lng);
                    marker.setLatLng(latlng);
                    circle.setLatLng(latlng);
                    map.panTo(latlng);
                }

                window.updateRadius = updateRadius;
                window.updateMarkerPosition = updateMarkerPosition;

                marker.on('dragend', function(e) {
                    var pos = marker.getLatLng();
                    circle.setLatLng(pos);
                    AndroidBridge.onLocationSelected(pos.lat, pos.lng);
                });

                map.on('click', function(e) {
                    updateLocation(e.latlng.lat, e.latlng.lng);
                    AndroidBridge.onLocationSelected(e.latlng.lat, e.latlng.lng);
                });

                function searchAddress() {
                    var query = document.getElementById('search-input').value;
                    if (!query) return;
                    fetch('https://nominatim.openstreetmap.org/search?format=json&q=' + encodeURIComponent(query))
                        .then(response => response.json())
                        .then(data => {
                            if (data && data.length > 0) {
                                var lat = parseFloat(data[0].lat);
                                var lon = parseFloat(data[0].lon);
                                updateLocation(lat, lon);
                                AndroidBridge.onLocationSelected(lat, lon);
                            } else {
                                alert('Location not found');
                            }
                        })
                        .catch(err => {
                            console.error(err);
                            alert('Error searching address');
                        });
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "DynamicWallpaperManager/1.0 (contact@fostbyte.com) Mobile"
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("if (typeof adjustMapSize === 'function') { adjustMapSize(); }", null)
                    }
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        android.util.Log.d("MapWebViewConsole", "${consoleMessage?.messageLevel()}: ${consoleMessage?.message()} (at ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                        return true
                    }
                }
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onLocationSelected(lat: Double, lng: Double) {
                        onLocationSelected(lat, lng)
                    }
                }, "AndroidBridge")
                loadDataWithBaseURL("https://unpkg.com", htmlContent, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.evaluateJavascript("if (typeof updateRadius === 'function') { updateRadius($initialRadius); }", null)
            webView.evaluateJavascript("if (typeof updateMarkerPosition === 'function') { updateMarkerPosition($initialLat, $initialLng); }", null)
        },
        modifier = modifier
    )
}

@Composable
fun rememberWifiSsids(context: Context): List<String> {
    return remember(context) {
        val ssids = mutableListOf<String>()
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val capabilities = cm?.getNetworkCapabilities(activeNetwork)
        val wifiInfo = capabilities?.transportInfo as? android.net.wifi.WifiInfo
        val currentSsid = wifiInfo?.ssid?.trim('"', ' ')?.takeIf { it != "<unknown ssid>" && it != "0x" }
        if (currentSsid != null) {
            ssids.add(currentSsid)
        }
        
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager != null) {
            val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                              ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (hasLocation) {
                try {
                    @Suppress("DEPRECATION")
                    val configured = wifiManager.configuredNetworks
                    configured?.forEach { config ->
                        val ssid = config.SSID?.trim('"', ' ')
                        if (ssid != null && ssid.isNotBlank() && ssid != "<unknown ssid>" && !ssids.contains(ssid)) {
                            ssids.add(ssid)
                        }
                    }
                } catch (e: Exception) {}
                
                try {
                    val scanResults = wifiManager.scanResults
                    scanResults?.forEach { result ->
                        val ssid = result.SSID?.trim('"', ' ')
                        if (ssid != null && ssid.isNotBlank() && !ssids.contains(ssid)) {
                            ssids.add(ssid)
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        ssids
    }
}

