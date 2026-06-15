package com.wallpaper.changer.ui.easy

import android.annotation.SuppressLint
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
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
                
                val triggerDesc = if (rule.type == "Time") {
                    "Time: ${rule.time} (${rule.daysOfWeek ?: "All Days"})"
                } else {
                    "Geofence: Lat=${rule.latitude?.toString()?.take(7)}, Lng=${rule.longitude?.toString()?.take(7)} (radius=${rule.radius}m)"
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
    var isTimeEnabled by remember { mutableStateOf(rule?.time != null || rule?.type == "Time" || (rule == null && rule?.type != "Interval")) }
    var isLocationEnabled by remember { mutableStateOf(rule?.latitude != null || rule?.type == "Location") }
    var isIntervalEnabled by remember { mutableStateOf(rule?.type == "Interval") }
    
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
    var locationAccuracy by remember { mutableStateOf(rule?.locationAccuracy ?: "Loose") }

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
            modifier = Modifier.fillMaxWidth().background(Color(0xFF689F38)).padding(16.dp),
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
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                    color = Color.DarkGray,
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
                                                    color = if (isSelected) Color(0xFF689F38) else Color(0xFFE0E0E0),
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
                                                color = if (isSelected) Color.White else Color.Black
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
                                    colors = CardDefaults.cardColors(containerColor = Color.LightGray)
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
                                    Text("Accuracy Tier:")
                                    Spacer(modifier = Modifier.width(16.dp))
                                    RadioButton(
                                        selected = locationAccuracy == "Loose",
                                        onClick = { locationAccuracy = "Loose" }
                                    )
                                    Text("Loose")
                                    Spacer(modifier = Modifier.width(16.dp))
                                    RadioButton(
                                        selected = locationAccuracy == "Precise",
                                        onClick = { locationAccuracy = "Precise" }
                                    )
                                    Text("Precise")
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



                        if (isTimeEnabled && isLocationEnabled) {
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
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
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
                    colors = CardDefaults.cardColors(containerColor = Color.White),
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
                                        color = Color(0xFF689F38),
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Rule Logic Statement", style = MaterialTheme.typography.titleSmall, color = Color(0xFF33691E))
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        val statement = when {
                            isTimeEnabled && !isLocationEnabled && actionType == "NextWallpaper" -> {
                                "IF clock hits $timeStr on [${selectedDays.joinToString(", ")}] THEN execute Next Wallpaper."
                            }
                            isTimeEnabled && !isLocationEnabled && actionType == "SwitchAlbum" -> {
                                val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                "IF clock hits $timeStr on [${selectedDays.joinToString(", ")}] THEN set active playlist to '$albumName'."
                            }
                            !isTimeEnabled && isLocationEnabled && actionType == "NextWallpaper" -> {
                                "IF device enters geofence (Lat=${latText.take(5)}, Lng=${lngText.take(5)}, Accuracy=$locationAccuracy) THEN execute Next Wallpaper."
                            }
                            !isTimeEnabled && isLocationEnabled && actionType == "SwitchAlbum" -> {
                                val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                "IF device enters geofence (Lat=${latText.take(5)}, Lng=${lngText.take(5)}, Accuracy=$locationAccuracy) THEN set active playlist to '$albumName'."
                            }
                            isTimeEnabled && isLocationEnabled -> {
                                val relationWord = connectionLogic
                                val actionWord = if (actionType == "NextWallpaper") "execute Next Wallpaper" else {
                                    val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                    "set active playlist to '$albumName'"
                                }
                                "IF clock hits $timeStr on [${selectedDays.joinToString(", ")}] $relationWord device enters geofence (Lat=${latText.take(5)}, Lng=${lngText.take(5)}, Accuracy=$locationAccuracy) THEN $actionWord."
                            }
                            isIntervalEnabled && actionType == "NextWallpaper" -> {
                                val orderText = if (randomOrder) "in Random order" else "in Sequential order"
                                "Rotate wallpapers inside active album every $intervalValText $intervalUnit $orderText."
                            }
                            isIntervalEnabled && actionType == "SwitchAlbum" -> {
                                val albumName = albums.filter { selectedAlbumIds.contains(it.id) }.joinToString(", ") { it.name }.ifBlank { "Selected Album" }
                                "Rotate wallpapers inside active album every $intervalValText $intervalUnit and switch to playlist '$albumName'."
                            }

                            else -> {
                                "Please select at least one trigger to save this rule."
                            }
                        }
                        
                        Text(statement, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
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
                        
                        val nameToUse = ruleName.ifBlank {
                            when {
                                isTimeEnabled && !isLocationEnabled -> "Schedule at $timeStr"
                                !isTimeEnabled && isLocationEnabled -> "Geofence rule"
                                isIntervalEnabled -> "Interval every $intervalValText $intervalUnit"
                                else -> "Compound Rule ($connectionLogic)"
                            }
                        }
                        
                        val newRule = AutomationRule(
                            id = rule?.id ?: 0L,
                            name = nameToUse,
                            type = when {
                                isTimeEnabled -> "Time"
                                isLocationEnabled -> "Location"
                                isIntervalEnabled -> "Interval"
                                else -> "Time"
                            },
                            priority = rule?.priority ?: 0,
                            isEnabled = true,
                            daysOfWeek = if (isTimeEnabled) daysString else null,
                            time = if (isTimeEnabled) timeStr else null,
                            latitude = lat,
                            longitude = lng,
                            radius = radius,
                            locationAccuracy = if (isLocationEnabled) locationAccuracy else null,
                            connectionLogic = if (isTimeEnabled && isLocationEnabled) connectionLogic else "NONE",
                            actionType = actionType,
                            targetAlbumId = if (actionType == "SwitchAlbum" && selectedAlbumIds.isNotEmpty()) selectedAlbumIds.first() else null,
                            targetAlbumIds = if (actionType == "SwitchAlbum") selectedAlbumIds.joinToString(",") else null,
                            intervalValue = if (isIntervalEnabled) intervalValText.toIntOrNull() ?: 10 else null,
                            intervalUnit = if (isIntervalEnabled) intervalUnit else null,
                            randomOrder = randomOrder
                        )
                        onSave(newRule)
                    },
                    enabled = isTimeEnabled || isLocationEnabled || isIntervalEnabled,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
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
    modifier: Modifier = Modifier
) {
    val htmlContent = remember {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body { margin: 0; padding: 0; }
                #map { height: 100vh; width: 100vw; }
                #search-container {
                    position: absolute;
                    top: 10px;
                    left: 10px;
                    right: 10px;
                    z-index: 1000;
                    display: flex;
                    background: white;
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
                }
                #search-button {
                    background: #689F38;
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
                var map = L.map('map', { zoomControl: false }).setView([$initialLat, $initialLng], 13);
                L.control.zoom({ position: 'bottomright' }).addTo(map);
                
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
                }).addTo(map);

                var marker = L.marker([$initialLat, $initialLng], {draggable: true}).addTo(map);
                var circle = L.circle([$initialLat, $initialLng], {
                    color: '#689F38',
                    fillColor: '#81C784',
                    fillOpacity: 0.4,
                    radius: $initialRadius
                }).addTo(map);

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
                webViewClient = WebViewClient()
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onLocationSelected(lat: Double, lng: Double) {
                        onLocationSelected(lat, lng)
                    }
                }, "AndroidBridge")
                loadDataWithBaseURL("https://openstreetmap.org", htmlContent, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.evaluateJavascript("if (typeof updateRadius === 'function') { updateRadius($initialRadius); }", null)
            webView.evaluateJavascript("if (typeof updateMarkerPosition === 'function') { updateMarkerPosition($initialLat, $initialLng); }", null)
        },
        modifier = modifier
    )
}
