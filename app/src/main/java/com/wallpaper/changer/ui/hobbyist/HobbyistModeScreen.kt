package com.wallpaper.changer.ui.hobbyist

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.ArrowDropDown
import com.wallpaper.changer.ui.easy.rememberWifiSsids
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallpaper.changer.data.*
import com.wallpaper.changer.ui.easy.MapPickerWebView
import kotlinx.coroutines.launch
import org.yaml.snakeyaml.Yaml
import kotlin.math.roundToInt

// View representation of flowchart node
data class Node(
    val id: String,
    val title: String,
    val type: String, // "Time", "Location", "And", "Or", "Xor", "Not", "NextWallpaper", "SwitchAlbum", "Interval", "Gesture", "RandomOrder"
    val x: Float,
    val y: Float,
    val time: String = "12:00",
    val days: String = "Mon,Tue,Wed,Thu,Fri",
    val lat: String = "37.7749",
    val lng: String = "-122.4194",
    val radius: String = "100.0",
    val albumId: Long = 0L,
    val albumIds: String = "",
    val intervalValue: Int = 10,
    val intervalUnit: String = "Minutes",
    val randomOrder: Boolean = false,
    val gestureType: String = "DoubleTap",
    val timeCondition: String = "At",
    val locationCondition: String = "Entering",
    val wallpaperDirection: String = "Next",
    val dimmingPercent: Int = 0,
    val blurPercent: Int = 0,
    val greyscalePercent: Int = 0,
    val wifiState: String = "Connecting",
    val wifiSsid: String = ""
)

data class Connection(
    val fromNode: String,
    val fromPort: Int, // 0 = Output
    val toNode: String,
    val toPort: Int // 0 = Input 1, 1 = Input 2
)

@Composable
fun HobbyistModeScreen(database: AppDatabase) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var nodes by remember { mutableStateOf(emptyList<Node>()) }
    var connections by remember { mutableStateOf(emptyList<Connection>()) }
    var albums by remember { mutableStateOf(emptyList<Album>()) }
    
    // Tap to connect tracking state
    var selectedPortNode by remember { mutableStateOf<String?>(null) }
    var selectedPortType by remember { mutableStateOf<String?>(null) } // "Input" or "Output"
    var selectedPortIndex by remember { mutableStateOf<Int?>(null) }

    // Canvas zoom and pan states
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun refreshGraph() {
        scope.launch {
            albums = database.albumDao().getAll()
            val nodeEntities = database.nodeGraphDao().getAllNodes()
            val connEntities = database.nodeGraphDao().getAllConnections()
            
            val yaml = Yaml()
            nodes = nodeEntities.map { entity ->
                val payloadMap = try {
                    if (entity.payload != null) yaml.load<Map<String, Any>>(entity.payload) else emptyMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                
                Node(
                    id = entity.id,
                    title = entity.title,
                    type = entity.type,
                    x = entity.x,
                    y = entity.y,
                    time = payloadMap["time"] as? String ?: "12:00",
                    days = payloadMap["days"] as? String ?: "Mon,Tue,Wed,Thu,Fri",
                    lat = payloadMap["lat"]?.toString() ?: "37.7749",
                    lng = payloadMap["lng"]?.toString() ?: "-122.4194",
                    radius = payloadMap["radius"]?.toString() ?: "100.0",
                    albumId = (payloadMap["albumId"] as? Number)?.toLong() ?: (albums.firstOrNull()?.id ?: 0L),
                    albumIds = payloadMap["albumIds"] as? String ?: ((payloadMap["albumId"] as? Number)?.toLong()?.toString() ?: ""),
                    intervalValue = (payloadMap["intervalValue"] as? Number)?.toInt() ?: 10,
                    intervalUnit = payloadMap["intervalUnit"] as? String ?: "Minutes",
                    randomOrder = payloadMap["randomOrder"] as? Boolean ?: false,
                    gestureType = payloadMap["gestureType"] as? String ?: "DoubleTap",
                    timeCondition = payloadMap["timeCondition"] as? String ?: "At",
                    locationCondition = payloadMap["locationCondition"] as? String ?: "Entering",
                    wallpaperDirection = payloadMap["wallpaperDirection"] as? String ?: "Next",
                    dimmingPercent = (payloadMap["dimmingPercent"] as? Number)?.toInt() ?: 0,
                    blurPercent = (payloadMap["blurPercent"] as? Number)?.toInt() ?: 0,
                    greyscalePercent = (payloadMap["greyscalePercent"] as? Number)?.toInt() ?: 0,
                    wifiState = payloadMap["wifiState"] as? String ?: "Connecting",
                    wifiSsid = payloadMap["wifiSsid"] as? String ?: ""
                )
            }
            
            connections = connEntities.map { entity ->
                Connection(
                    fromNode = entity.fromNode,
                    fromPort = entity.fromPort,
                    toNode = entity.toNode,
                    toPort = entity.toPort
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshGraph()
    }

    fun saveAndCompileGraph() {
        scope.launch {
            for (node in nodes) {
                when (node.type) {
                    "Time" -> {
                        val parts = node.time.split(":")
                        val hour = parts.getOrNull(0)?.toIntOrNull()
                        val minute = parts.getOrNull(1)?.toIntOrNull()
                        if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                            Toast.makeText(context, "Validation Error: Node '${node.title}' has invalid time format '${node.time}' (expected HH:MM).", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                    "Location" -> {
                        if (node.lat.toDoubleOrNull() == null || node.lng.toDoubleOrNull() == null) {
                            Toast.makeText(context, "Validation Error: Node '${node.title}' has invalid coordinates.", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val radiusVal = node.radius.toFloatOrNull()
                        if (radiusVal == null || radiusVal <= 0f) {
                            Toast.makeText(context, "Validation Error: Node '${node.title}' has invalid radius.", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                    "Interval" -> {
                        if (node.intervalValue <= 0) {
                            Toast.makeText(context, "Validation Error: Node '${node.title}' must have a positive interval value.", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                }
            }

            // 1. Save state to Database
            val yaml = Yaml()
            val nodeEntities = nodes.map { node ->
                val payloadMap = mapOf(
                    "time" to node.time,
                    "days" to node.days,
                    "lat" to node.lat,
                    "lng" to node.lng,
                    "radius" to node.radius,
                    "albumId" to node.albumId,
                    "albumIds" to node.albumIds,
                    "intervalValue" to node.intervalValue,
                    "intervalUnit" to node.intervalUnit,
                    "randomOrder" to node.randomOrder,
                    "gestureType" to node.gestureType,
                    "timeCondition" to node.timeCondition,
                    "locationCondition" to node.locationCondition,
                    "wallpaperDirection" to node.wallpaperDirection,
                    "dimmingPercent" to node.dimmingPercent,
                    "blurPercent" to node.blurPercent,
                    "greyscalePercent" to node.greyscalePercent,
                    "wifiState" to node.wifiState,
                    "wifiSsid" to node.wifiSsid
                )
                val payloadStr = yaml.dump(payloadMap)
                NodeEntity(
                    id = node.id,
                    title = node.title,
                    type = node.type,
                    x = node.x,
                    y = node.y,
                    payload = payloadStr
                )
            }
            
            val connEntities = connections.map { conn ->
                ConnectionEntity(
                    fromNode = conn.fromNode,
                    fromPort = conn.fromPort,
                    toNode = conn.toNode,
                    toPort = conn.toPort
                )
            }
            
            database.nodeGraphDao().saveGraph(nodeEntities, connEntities)

            // 2. Compile Graph to AutomationRules
            compileGraphToRules(database, nodes, connections)
            
            // Notify service
            context.startService(android.content.Intent(context, com.wallpaper.changer.automation.AutomationService::class.java))
            
            refreshGraph()

            Toast.makeText(context, "Flowchart saved and compiled!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) {
            val isNarrow = maxWidth < 480.dp
            
            val buttonsContent = @Composable {
                var expandedAddNode by remember { mutableStateOf(false) }
                var showHelpDialog by remember { mutableStateOf(false) }

                if (showHelpDialog) {
                    com.wallpaper.changer.ui.RuleHelpDialog(onDismiss = { showHelpDialog = false })
                }

                Box {
                    Button(onClick = { expandedAddNode = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Node")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Node")
                    }
                    DropdownMenu(expanded = expandedAddNode, onDismissRequest = { expandedAddNode = false }) {
                        val nodeTypes = listOf(
                            "Time Trigger" to "Time",
                            "Location Trigger" to "Location",
                            "Interval Trigger" to "Interval",
                            "Gesture Trigger" to "Gesture",
                            "Unlock Trigger" to "Unlock",
                            "Home Screen Trigger" to "HomeScreen",
                            "AND Gate" to "And",
                            "OR Gate" to "Or",
                            "XOR Gate" to "Xor",
                            "NOT Gate" to "Not",
                            "Change Wallpaper" to "NextWallpaper",
                            "Switch Album" to "SwitchAlbum",
                            "Rotate Favorites" to "RotateFavorites",
                            "Random Order" to "RandomOrder"
                        )
                        nodeTypes.forEach { (label, type) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    val id = "node_${System.currentTimeMillis()}"
                                    nodes = nodes + Node(
                                        id = id,
                                        title = label,
                                        type = type,
                                        x = 100f,
                                        y = 200f + (nodes.size * 20)
                                    )
                                    expandedAddNode = false
                                }
                            )
                        }
                    }
                }

                Button(
                    onClick = { saveAndCompileGraph() }
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save & Apply")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Apply Flow")
                }

                IconButton(onClick = { showHelpDialog = true }) {
                    Icon(Icons.Default.Help, contentDescription = "Help", tint = MaterialTheme.colorScheme.primary)
                }
            }

            if (isNarrow) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Flowchart Rule Builder",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        buttonsContent()
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Flowchart Rule Builder",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        buttonsContent()
                    }
                }
            }
        }

        // Canvas Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 3.0f)
                        offset = offset + pan
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        transformOrigin = TransformOrigin(0f, 0f)
                    )
            ) {
                val lineColor = MaterialTheme.colorScheme.primary
                // Draw connections as paths
                Canvas(modifier = Modifier.fillMaxSize()) {
                    connections.forEach { conn ->
                        val fromNode = nodes.find { it.id == conn.fromNode }
                        val toNode = nodes.find { it.id == conn.toNode }
                        if (fromNode != null && toNode != null) {
                            // Calculate vertical offset for output port (always 82.5.dp from top)
                            val fromPortY = 82.5.dp.toPx()
                            
                            // Calculate vertical offset for input port
                            val toPorts = when (toNode.type) {
                                "And", "Or", "Xor" -> listOf(60.dp, 105.dp)
                                else -> listOf(82.5.dp)
                            }
                            val toPortY = toPorts.getOrNull(conn.toPort)?.toPx() ?: 82.5.dp.toPx()
                            
                            val fromOffset = Offset(
                                x = fromNode.x + 200.dp.toPx(),
                                y = fromNode.y + fromPortY
                            )
                            val toOffset = Offset(
                                x = toNode.x,
                                y = toNode.y + toPortY
                            )
                            
                            val path = Path().apply {
                                moveTo(fromOffset.x, fromOffset.y)
                                cubicTo(
                                    fromOffset.x + 150f, fromOffset.y,
                                    toOffset.x - 150f, toOffset.y,
                                    toOffset.x, toOffset.y
                                )
                            }
                            drawPath(
                                path = path,
                                color = lineColor,
                                style = Stroke(width = 4f)
                            )
                        }
                    }
                }

                // Render Nodes
                nodes.forEach { node ->
                    NodeCard(
                        node = node,
                        albums = albums,
                        scale = scale,
                        onPositionChanged = { newX, newY ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(x = newX, y = newY) else it
                            }
                        },
                        onTimeChanged = { newTime, newCondition, newDays ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(time = newTime, timeCondition = newCondition, days = newDays) else it
                            }
                        },
                        onLocationChanged = { newLat, newLng, newRadius, newCondition ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(lat = newLat, lng = newLng, radius = newRadius, locationCondition = newCondition) else it
                            }
                        },
                        onAlbumIdsChanged = { firstId, idsVal ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(albumId = firstId, albumIds = idsVal) else it
                            }
                        },
                        onIntervalChanged = { valVal, unitVal, randVal ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(intervalValue = valVal, intervalUnit = unitVal, randomOrder = randVal) else it
                            }
                        },
                        onGestureChanged = { gestVal ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(gestureType = gestVal) else it
                            }
                        },
                        onWallpaperDirectionChanged = { dirVal ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(wallpaperDirection = dirVal) else it
                            }
                        },
                        onVisualEffectsChanged = { dimming, blur, greyscale ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(dimmingPercent = dimming, blurPercent = blur, greyscalePercent = greyscale) else it
                            }
                        },
                        onWifiChanged = { newState, newSsid ->
                            nodes = nodes.map {
                                if (it.id == node.id) it.copy(wifiState = newState, wifiSsid = newSsid) else it
                            }
                        },
                        onDelete = {
                            nodes = nodes.filter { it.id != node.id }
                            connections = connections.filter { it.fromNode != node.id && it.toNode != node.id }
                        },
                        onPortTap = { portType, portIndex ->
                            if (selectedPortNode == null) {
                                // First selection
                                selectedPortNode = node.id
                                selectedPortType = portType
                                selectedPortIndex = portIndex
                            } else {
                                // Second selection
                                val firstNode = selectedPortNode!!
                                val firstType = selectedPortType!!
                                val firstIndex = selectedPortIndex!!
                                
                                // Check if connection is valid: Output -> Input
                                if (firstType == "Output" && portType == "Input" && firstNode != node.id) {
                                    connections = connections.filterNot { it.toNode == node.id && it.toPort == portIndex } + Connection(
                                        fromNode = firstNode,
                                        fromPort = firstIndex,
                                        toNode = node.id,
                                        toPort = portIndex
                                    )
                                } else if (firstType == "Input" && portType == "Output" && firstNode != node.id) {
                                    connections = connections.filterNot { it.toNode == firstNode && it.toPort == firstIndex } + Connection(
                                        fromNode = node.id,
                                        fromPort = portIndex,
                                        toNode = firstNode,
                                        toPort = firstIndex
                                    )
                                }
                                
                                // Reset
                                selectedPortNode = null
                                selectedPortType = null
                                selectedPortIndex = null
                            }
                        },
                        isPortSelected = { portType, portIndex ->
                            selectedPortNode == node.id && selectedPortType == portType && selectedPortIndex == portIndex
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PortCircle(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (isSelected) Color.Green else Color.Gray, shape = CircleShape)
        )
    }
}

@Composable
fun NodeCard(
    node: Node,
    albums: List<Album>,
    scale: Float,
    onPositionChanged: (Float, Float) -> Unit,
    onTimeChanged: (String, String, String) -> Unit,
    onLocationChanged: (String, String, String, String) -> Unit,
    onAlbumIdsChanged: (Long, String) -> Unit,
    onIntervalChanged: (Int, String, Boolean) -> Unit,
    onGestureChanged: (String) -> Unit,
    onWallpaperDirectionChanged: (String) -> Unit,
    onVisualEffectsChanged: (Int, Int, Int) -> Unit,
    onWifiChanged: (String, String) -> Unit = { _, _ -> },
    onDelete: () -> Unit,
    onPortTap: (String, Int) -> Unit,
    isPortSelected: (String, Int) -> Boolean
) {
    val nodeWidth = 200.dp
    
    // Header colors based on categories
    val headerColor = when (node.type) {
        "Time", "Location", "Interval", "Gesture", "Unlock", "HomeScreen", "WiFi" -> Color(0xFF3B82F6) // Indigo/Blue for Triggers
        "And", "Or", "Xor", "Not" -> Color(0xFFF59E0B) // Amber for Logic Gates
        else -> Color(0xFF8B5CF6) // Purple/Violet for Actions
    }

    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentNode by rememberUpdatedState(node)
    var showFxDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .offset { IntOffset(node.x.roundToInt(), node.y.roundToInt()) }
            .width(nodeWidth)
            .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(8.dp))
            .pointerInput(scale) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnPositionChanged(currentNode.x + dragAmount.x / scale, currentNode.y + dragAmount.y / scale)
                }
            }
    ) {
        Column {
            // Title Header: Fixed height 40.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(headerColor, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(node.title, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                IconButton(onClick = onDelete, modifier = Modifier.size(16.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }

            // Body: Fixed height 85.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(85.dp)
                    .padding(horizontal = 8.dp)
            ) {
                // Input Ports Column/Box on the Left
                val inputPorts = when (node.type) {
                    "And", "Or", "Xor" -> listOf("In 1", "In 2")
                    "Not", "NextWallpaper", "SwitchAlbum", "RotateFavorites", "RandomOrder" -> listOf("In")
                    else -> emptyList()
                }
                
                if (inputPorts.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .fillMaxHeight()
                            .align(Alignment.CenterStart)
                    ) {
                        if (inputPorts.size == 1) {
                            PortCircle(
                                isSelected = isPortSelected("Input", 0),
                                onClick = { onPortTap("Input", 0) },
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                        } else {
                            PortCircle(
                                isSelected = isPortSelected("Input", 0),
                                onClick = { onPortTap("Input", 0) },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(y = 14.dp)
                            )
                            PortCircle(
                                isSelected = isPortSelected("Input", 1),
                                onClick = { onPortTap("Input", 1) },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(y = 59.dp)
                            )
                        }
                    }
                }
                
                // Node internal configs (Center)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (node.type) {
                        "Time" -> {
                            var showTimeDial by remember { mutableStateOf(false) }
                            val formattedTime = formatTimeBySystem(node.time, LocalContext.current)
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { showTimeDial = true }
                            ) {
                                Text(
                                    text = "${node.timeCondition} $formattedTime",
                                    fontSize = 12.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val daysAbbr = when {
                                    node.days.split(",").size == 7 -> "Daily"
                                    node.days == "Mon,Tue,Wed,Thu,Fri" -> "Weekdays"
                                    node.days == "Sat,Sun" -> "Weekends"
                                    else -> node.days
                                }
                                Text(
                                    text = daysAbbr,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            
                            if (showTimeDial) {
                                var tempTime by remember { mutableStateOf(node.time) }
                                var tempCondition by remember { mutableStateOf(node.timeCondition) }
                                var tempDays by remember { mutableStateOf(node.days.split(",").filter { it.isNotBlank() }.toSet()) }
                                var expandedCondition by remember { mutableStateOf(false) }
                                val context = LocalContext.current
                                
                                AlertDialog(
                                    onDismissRequest = { showTimeDial = false },
                                    title = { Text("Configure Time Trigger") },
                                    text = {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    val cal = java.util.Calendar.getInstance()
                                                    val parts = tempTime.split(":")
                                                    val initialHour = parts.getOrNull(0)?.toIntOrNull() ?: cal.get(java.util.Calendar.HOUR_OF_DAY)
                                                    val initialMinute = parts.getOrNull(1)?.toIntOrNull() ?: cal.get(java.util.Calendar.MINUTE)
                                                    android.app.TimePickerDialog(
                                                        context,
                                                        { _, hour, minute ->
                                                            tempTime = String.format("%02d:%02d", hour, minute)
                                                        },
                                                        initialHour,
                                                        initialMinute,
                                                        android.text.format.DateFormat.is24HourFormat(context)
                                                    ).show()
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Time: ${formatTimeBySystem(tempTime, context)}")
                                            }
                                            
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(
                                                    onClick = { expandedCondition = true },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("Condition: $tempCondition")
                                                }
                                                DropdownMenu(
                                                    expanded = expandedCondition,
                                                    onDismissRequest = { expandedCondition = false }
                                                ) {
                                                    listOf("At", "Before", "After").forEach { cond ->
                                                        DropdownMenuItem(
                                                            text = { Text(cond) },
                                                            onClick = {
                                                                tempCondition = cond
                                                                expandedCondition = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            
                                            Text("Days of Week:", style = MaterialTheme.typography.titleSmall)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { day ->
                                                    val isSelected = tempDays.contains(day)
                                                    Box(
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .background(
                                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                                shape = CircleShape
                                                            )
                                                            .clickable {
                                                                tempDays = if (isSelected) tempDays - day else tempDays + day
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = day.take(2),
                                                            fontSize = 10.sp,
                                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            onTimeChanged(tempTime, tempCondition, tempDays.joinToString(","))
                                            showTimeDial = false
                                        }) { Text("OK") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showTimeDial = false }) { Text("Cancel") }
                                    }
                                )
                            }
                        }
                        "Location" -> {
                            var showCoordsDialog by remember { mutableStateOf(false) }
                            val displayLocWord = if (node.locationCondition == "Leaving") "Leaving" else "Entering"
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { showCoordsDialog = true }
                            ) {
                                Text(
                                    text = displayLocWord,
                                    fontSize = 12.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Radius: ${node.radius}m",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            if (showCoordsDialog) {
                                var tempLat by remember { mutableStateOf(node.lat) }
                                var tempLng by remember { mutableStateOf(node.lng) }
                                var tempRad by remember { mutableStateOf(node.radius) }
                                var tempCondition by remember { mutableStateOf(node.locationCondition) }
                                
                                AlertDialog(
                                    onDismissRequest = { showCoordsDialog = false },
                                    title = { Text("Set Geofence") },
                                    text = {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                        ) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().height(250.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                            ) {
                                                val latVal = tempLat.toDoubleOrNull() ?: 37.7749
                                                val lngVal = tempLng.toDoubleOrNull() ?: -122.4194
                                                val radVal = tempRad.toFloatOrNull() ?: 100f
                                                
                                                MapPickerWebView(
                                                    initialLat = latVal,
                                                    initialLng = lngVal,
                                                    initialRadius = radVal,
                                                    onLocationSelected = { lat, lng ->
                                                        tempLat = String.format(java.util.Locale.US, "%.6f", lat)
                                                        tempLng = String.format(java.util.Locale.US, "%.6f", lng)
                                                    },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            
                                            OutlinedTextField(
                                                value = tempRad,
                                                onValueChange = { tempRad = it },
                                                label = { Text("Radius (meters)") },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                            ) {
                                                val isEntering = tempCondition == "Entering"
                                                Text(
                                                    text = "Entering",
                                                    fontWeight = if (isEntering) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                                    color = if (isEntering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                    modifier = Modifier.clickable { tempCondition = "Entering" }
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Switch(
                                                    checked = tempCondition == "Leaving",
                                                    onCheckedChange = { checked ->
                                                        tempCondition = if (checked) "Leaving" else "Entering"
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = "Leaving",
                                                    fontWeight = if (!isEntering) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                                    color = if (!isEntering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                    modifier = Modifier.clickable { tempCondition = "Leaving" }
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                onLocationChanged(tempLat, tempLng, tempRad, tempCondition)
                                                showCoordsDialog = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) { Text("OK") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showCoordsDialog = false }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }
                        }
                        "SwitchAlbum" -> {
                            var expandedDropdown by remember { mutableStateOf(false) }
                            val selectedIds = node.albumIds.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
                            val displayStr = if (selectedIds.isEmpty()) {
                                val matched = albums.find { it.id == node.albumId }
                                matched?.name ?: "Select"
                            } else {
                                albums.filter { selectedIds.contains(it.id) }.joinToString(", ") { it.name }
                            }
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { expandedDropdown = true }
                                ) {
                                    Text("Switch Album", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = displayStr,
                                        fontSize = 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (node.dimmingPercent > 0 || node.blurPercent > 0 || node.greyscalePercent > 0) "FX (Set)" else "Configure FX",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showFxDialog = true }.padding(top = 4.dp)
                                )
                            }
                            
                            DropdownMenu(expanded = expandedDropdown, onDismissRequest = { expandedDropdown = false }) {
                                albums.forEach { alb ->
                                    val isChecked = selectedIds.contains(alb.id) || (selectedIds.isEmpty() && alb.id == node.albumId)
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(checked = isChecked, onCheckedChange = null)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(alb.name, fontSize = 10.sp)
                                            }
                                        },
                                        onClick = {
                                            val newIds = if (isChecked) {
                                                selectedIds - alb.id
                                            } else {
                                                selectedIds + alb.id
                                            }
                                            val newIdsStr = newIds.joinToString(",")
                                            val firstId = newIds.firstOrNull() ?: 0L
                                            onAlbumIdsChanged(firstId, newIdsStr)
                                        }
                                    )
                                }
                            }
                        }
                        "Interval" -> {
                            var showIntervalDialog by remember { mutableStateOf(false) }
                            val unitAbbr = when (node.intervalUnit) {
                                "Seconds" -> "s"
                                "Minutes" -> "m"
                                "Hours" -> "h"
                                "Days" -> "d"
                                else -> "m"
                            }
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { showIntervalDialog = true }
                            ) {
                                Text(
                                    text = "Every ${node.intervalValue}$unitAbbr",
                                    fontSize = 12.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            if (showIntervalDialog) {
                                var tempValue by remember { mutableStateOf(node.intervalValue.toString()) }
                                var tempUnit by remember { mutableStateOf(node.intervalUnit) }
                                var unitExpanded by remember { mutableStateOf(false) }
                                
                                AlertDialog(
                                    onDismissRequest = { showIntervalDialog = false },
                                    title = { Text("Configure Interval") },
                                    text = {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedTextField(
                                                value = tempValue,
                                                onValueChange = { tempValue = it },
                                                label = { Text("Interval Value") },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(
                                                    onClick = { unitExpanded = true },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("Unit: $tempUnit")
                                                }
                                                DropdownMenu(
                                                    expanded = unitExpanded,
                                                    onDismissRequest = { unitExpanded = false }
                                                ) {
                                                    listOf("Seconds", "Minutes", "Hours", "Days").forEach { unit ->
                                                        DropdownMenuItem(
                                                            text = { Text(unit) },
                                                            onClick = {
                                                                tempUnit = unit
                                                                unitExpanded = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                val intVal = tempValue.toIntOrNull() ?: 10
                                                onIntervalChanged(intVal, tempUnit, false)
                                                showIntervalDialog = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                        ) { Text("OK") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showIntervalDialog = false }) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }
                        }
                        "WiFi" -> {
                            var showWifiDialog by remember { mutableStateOf(false) }
                            val displayState = node.wifiState
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { showWifiDialog = true }
                            ) {
                                Text(
                                    text = "WiFi $displayState",
                                    fontSize = 11.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (node.wifiSsid.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "SSID: ${node.wifiSsid}",
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            
                            if (showWifiDialog) {
                                var tempState by remember { mutableStateOf(node.wifiState) }
                                var tempSsid by remember { mutableStateOf(node.wifiSsid) }
                                val context = LocalContext.current
                                val wifiList = rememberWifiSsids(context)
                                var showWifiDropdown by remember { mutableStateOf(false) }
                                
                                AlertDialog(
                                    onDismissRequest = { showWifiDialog = false },
                                    title = { Text("Configure WiFi Trigger") },
                                    text = {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Event:")
                                                Spacer(modifier = Modifier.width(16.dp))
                                                RadioButton(
                                                    selected = tempState == "Connecting",
                                                    onClick = { tempState = "Connecting" }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Connecting")
                                                Spacer(modifier = Modifier.width(16.dp))
                                                RadioButton(
                                                    selected = tempState == "Disconnecting",
                                                    onClick = { tempState = "Disconnecting" }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("Disconnecting")
                                            }
                                            
                                            Box(modifier = Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = tempSsid,
                                                    onValueChange = { tempSsid = it },
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
                                                                    tempSsid = ssid
                                                                    showWifiDropdown = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                onWifiChanged(tempState, tempSsid)
                                                showWifiDialog = false
                                            }
                                        ) { Text("OK") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showWifiDialog = false }) { Text("Cancel") }
                                    }
                                )
                            }
                        }
                        "Gesture" -> {
                            var expandedGesture by remember { mutableStateOf(false) }
                            
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { expandedGesture = true }
                            ) {
                                Text("On Gesture", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = node.gestureType,
                                    fontSize = 12.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            
                            DropdownMenu(expanded = expandedGesture, onDismissRequest = { expandedGesture = false }) {
                                listOf("DoubleTap", "TwoFingerDoubleTap", "ThreeFingerDoubleTap").forEach { gesture ->
                                    DropdownMenuItem(
                                        text = { Text(gesture, fontSize = 10.sp) },
                                        onClick = {
                                            onGestureChanged(gesture)
                                            expandedGesture = false
                                        }
                                    )
                                }
                            }
                        }
                        "Unlock" -> {
                            Text("Device Unlocked", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        "HomeScreen" -> {
                            Text("Go to Home Screen", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        "RotateFavorites" -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Rotate Favorites", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (node.dimmingPercent > 0 || node.blurPercent > 0 || node.greyscalePercent > 0) "FX (Set)" else "Configure FX",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showFxDialog = true }.padding(top = 2.dp)
                                )
                            }
                        }
                        "NextWallpaper" -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Change Wallpaper", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = node.wallpaperDirection == "Next",
                                            onClick = { onWallpaperDirectionChanged("Next") },
                                            modifier = Modifier.size(24.dp).graphicsLayer(scaleX = 0.8f, scaleY = 0.8f)
                                        )
                                        Text("Next", fontSize = 9.sp)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = node.wallpaperDirection == "Previous",
                                            onClick = { onWallpaperDirectionChanged("Previous") },
                                            modifier = Modifier.size(24.dp).graphicsLayer(scaleX = 0.8f, scaleY = 0.8f)
                                        )
                                        Text("Prev", fontSize = 9.sp)
                                    }
                                }
                                Text(
                                    text = if (node.dimmingPercent > 0 || node.blurPercent > 0 || node.greyscalePercent > 0) "FX (Set)" else "Configure FX",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showFxDialog = true }.padding(top = 2.dp)
                                )
                            }
                        }
                        "RandomOrder" -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Random Order", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (node.dimmingPercent > 0 || node.blurPercent > 0 || node.greyscalePercent > 0) "FX (Set)" else "Configure FX",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { showFxDialog = true }.padding(top = 2.dp)
                                )
                            }
                        }
                        "And", "Or", "Xor", "Not" -> {
                            Text(node.type.uppercase(), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                
                // Output Port Column (Right)
                val hasOutput = when (node.type) {
                    "Time", "Location", "Interval", "Gesture", "Unlock", "HomeScreen", "And", "Or", "Xor", "Not" -> true
                    else -> false
                }
                
                if (hasOutput) {
                    PortCircle(
                        isSelected = isPortSelected("Output", 0),
                        onClick = { onPortTap("Output", 0) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }

        if (showFxDialog) {
            var tempDimming by remember { mutableStateOf(node.dimmingPercent) }
            var tempBlur by remember { mutableStateOf(node.blurPercent) }
            var tempGreyscale by remember { mutableStateOf(node.greyscalePercent) }

            AlertDialog(
                onDismissRequest = { showFxDialog = false },
                title = { Text("Configure Visual Effects") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Dimming
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Dimming")
                                Text("$tempDimming%")
                            }
                            Slider(
                                value = tempDimming.toFloat(),
                                onValueChange = { tempDimming = it.toInt() },
                                valueRange = 0f..100f,
                                steps = 99
                            )
                        }
                        // Blur
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Blur")
                                Text("$tempBlur%")
                            }
                            Slider(
                                value = tempBlur.toFloat(),
                                onValueChange = { tempBlur = it.toInt() },
                                valueRange = 0f..100f,
                                steps = 99
                            )
                        }
                        // Greyscale
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Greyscale")
                                Text("$tempGreyscale%")
                            }
                            Slider(
                                value = tempGreyscale.toFloat(),
                                onValueChange = { tempGreyscale = it.toInt() },
                                valueRange = 0f..100f,
                                steps = 99
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            onVisualEffectsChanged(tempDimming, tempBlur, tempGreyscale)
                            showFxDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { showFxDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}

// --- FLOW GRAPH COMPILER ---
private suspend fun compileGraphToRules(database: AppDatabase, nodes: List<Node>, connections: List<Connection>) {
    database.automationRuleDao().deleteAll()

    // Find all Action nodes
    val actionNodes = nodes.filter { it.type == "NextWallpaper" || it.type == "SwitchAlbum" || it.type == "RotateFavorites" || it.type == "RandomOrder" }
    
    data class TempRule(val rule: AutomationRule, val actionY: Float)
    val tempRules = mutableListOf<TempRule>()

    fun resolveGateInput(gateId: String, port: Int): Pair<Node, Boolean>? {
        val conn = connections.find { it.toNode == gateId && it.toPort == port } ?: return null
        var currentId = conn.fromNode
        var negated = false
        
        var currentNode = nodes.find { it.id == currentId } ?: return null
        while (currentNode.type == "Not") {
            negated = !negated
            val nextConn = connections.find { it.toNode == currentNode.id && it.toPort == 0 } ?: return null
            currentId = nextConn.fromNode
            currentNode = nodes.find { it.id == currentId } ?: return null
        }
        
        return Pair(currentNode, negated)
    }

    actionNodes.forEach { actionNode ->
        val parentConns = connections.filter { it.toNode == actionNode.id }
        
        val resolvedActionType = when (actionNode.type) {
            "NextWallpaper" -> if (actionNode.wallpaperDirection == "Previous") "PrevWallpaper" else "NextWallpaper"
            else -> actionNode.type
        }

        for (parentConn in parentConns) {
            val resolved = resolveGateInput(actionNode.id, parentConn.toPort) ?: continue
            val resolvedNode = resolved.first
            val isNegated = resolved.second
            
            if (resolvedNode.type == "Time") {
                tempRules.add(
                    TempRule(
                        rule = AutomationRule(
                            name = "Flow rule (temp)",
                            type = "Time",
                            priority = 0,
                            time = resolvedNode.time,
                            daysOfWeek = resolvedNode.days,
                            timeCondition = resolvedNode.timeCondition,
                            connectionLogic = if (isNegated) "NOT" else "NONE",
                            randomOrder = resolvedNode.randomOrder,
                            actionType = resolvedActionType,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                            dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                            blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                            greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                        ),
                        actionY = actionNode.y
                    )
                )
            } else if (resolvedNode.type == "Location") {
                tempRules.add(
                    TempRule(
                        rule = AutomationRule(
                            name = "Flow rule (temp)",
                            type = "Location",
                            priority = 0,
                            latitude = resolvedNode.lat.toDoubleOrNull(),
                            longitude = resolvedNode.lng.toDoubleOrNull(),
                            radius = resolvedNode.radius.toFloatOrNull(),
                            locationAccuracy = "Precise",
                            locationCondition = resolvedNode.locationCondition,
                            connectionLogic = if (isNegated) "NOT" else "NONE",
                            randomOrder = resolvedNode.randomOrder,
                            actionType = resolvedActionType,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                            dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                            blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                            greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                        ),
                        actionY = actionNode.y
                    )
                )
            } else if (resolvedNode.type == "WiFi") {
                tempRules.add(
                    TempRule(
                        rule = AutomationRule(
                            name = "Flow rule (temp)",
                            type = "WiFi",
                            priority = 0,
                            wifiState = resolvedNode.wifiState,
                            wifiSsid = if (resolvedNode.wifiSsid.isNotBlank()) resolvedNode.wifiSsid else null,
                            connectionLogic = if (isNegated) "NOT" else "NONE",
                            randomOrder = resolvedNode.randomOrder,
                            actionType = resolvedActionType,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                            dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                            blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                            greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                        ),
                        actionY = actionNode.y
                    )
                )
            } else if (resolvedNode.type == "Interval") {
                tempRules.add(
                    TempRule(
                        rule = AutomationRule(
                            name = "Flow rule (temp)",
                            type = "Interval",
                            priority = 0,
                            intervalValue = resolvedNode.intervalValue,
                            intervalUnit = resolvedNode.intervalUnit,
                            connectionLogic = if (isNegated) "NOT" else "NONE",
                            randomOrder = resolvedNode.randomOrder,
                            actionType = resolvedActionType,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                            dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                            blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                            greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                        ),
                        actionY = actionNode.y
                    )
                )
            } else if (resolvedNode.type == "Gesture") {
                tempRules.add(
                    TempRule(
                        rule = AutomationRule(
                            name = "Flow rule (temp)",
                            type = "Gesture",
                            priority = 0,
                            gestureType = resolvedNode.gestureType,
                            connectionLogic = if (isNegated) "NOT" else "NONE",
                            randomOrder = resolvedNode.randomOrder,
                            actionType = resolvedActionType,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                            dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                            blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                            greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                        ),
                        actionY = actionNode.y
                    )
                )
            } else if (resolvedNode.type == "Unlock") {
                tempRules.add(
                    TempRule(
                        rule = AutomationRule(
                            name = "Flow rule (temp)",
                            type = "Unlock",
                            priority = 0,
                            connectionLogic = if (isNegated) "NOT" else "NONE",
                            randomOrder = resolvedNode.randomOrder,
                            actionType = resolvedActionType,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                            dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                            blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                            greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                        ),
                        actionY = actionNode.y
                    )
                )
            } else if (resolvedNode.type == "HomeScreen") {
                tempRules.add(
                    TempRule(
                        rule = AutomationRule(
                            name = "Flow rule (temp)",
                            type = "HomeScreen",
                            priority = 0,
                            connectionLogic = if (isNegated) "NOT" else "NONE",
                            randomOrder = resolvedNode.randomOrder,
                            actionType = resolvedActionType,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                            dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                            blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                            greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                        ),
                        actionY = actionNode.y
                    )
                )
            } else if (resolvedNode.type == "And" || resolvedNode.type == "Or" || resolvedNode.type == "Xor") {
                val input0 = resolveGateInput(resolvedNode.id, 0)
                val input1 = resolveGateInput(resolvedNode.id, 1)
                
                val timeInput = when {
                    input0?.first?.type == "Time" -> input0
                    input1?.first?.type == "Time" -> input1
                    else -> null
                }
                val locInput = when {
                    input0?.first?.type == "Location" -> input0
                    input1?.first?.type == "Location" -> input1
                    else -> null
                }
                
                if (timeInput != null && locInput != null) {
                    val timeNode = timeInput.first
                    val isTimeNegated = timeInput.second
                    val locNode = locInput.first
                    val isLocNegated = locInput.second
                    
                    val logicStr = when (resolvedNode.type) {
                        "And" -> {
                            if (isLocNegated && !isTimeNegated) "AND_NOT"
                            else if (!isLocNegated && isTimeNegated) "AND_NOT"
                            else "AND"
                        }
                        "Or" -> {
                            if (isLocNegated && !isTimeNegated) "OR_NOT"
                            else if (!isLocNegated && isTimeNegated) "OR_NOT"
                            else "OR"
                        }
                        else -> "XOR"
                    }
                    
                    tempRules.add(
                        TempRule(
                            rule = AutomationRule(
                                name = "Flow rule (temp)",
                                type = "Time", 
                                priority = 0,
                                time = timeNode.time,
                                daysOfWeek = timeNode.days,
                                timeCondition = timeNode.timeCondition,
                                latitude = locNode.lat.toDoubleOrNull(),
                                longitude = locNode.lng.toDoubleOrNull(),
                                radius = locNode.radius.toFloatOrNull(),
                                locationAccuracy = "Loose",
                                locationCondition = locNode.locationCondition,
                                connectionLogic = logicStr,
                                randomOrder = timeNode.randomOrder || locNode.randomOrder,
                                actionType = resolvedActionType,
                                targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                                targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null,
                                dimmingPercent = if (actionNode.dimmingPercent > 0) actionNode.dimmingPercent else null,
                                blurPercent = if (actionNode.blurPercent > 0) actionNode.blurPercent else null,
                                greyscalePercent = if (actionNode.greyscalePercent > 0) actionNode.greyscalePercent else null
                            ),
                            actionY = actionNode.y
                        )
                    )
                }
            }
        }
    }

    val sortedRules = tempRules.sortedBy { it.actionY }
    val totalRules = sortedRules.size
    sortedRules.forEachIndexed { index, temp ->
        val priority = totalRules - index
        val name = when (temp.rule.type) {
            "Time" -> if (temp.rule.connectionLogic.contains("AND") || temp.rule.connectionLogic.contains("OR") || temp.rule.connectionLogic.contains("XOR")) {
                "Flow rule #${index + 1} (Time ${temp.rule.connectionLogic.replace("_", " ")} Geofence)"
            } else {
                "Flow rule #${index + 1} (${temp.rule.time})"
            }
            "Location" -> "Flow rule #${index + 1} (Geofence)"
            "Interval" -> "Flow rule #${index + 1} (Interval)"
            "Gesture" -> "Flow rule #${index + 1} (Gesture)"
            "Unlock" -> "Flow rule #${index + 1} (Device Unlock)"
            "HomeScreen" -> "Flow rule #${index + 1} (Go to Home Screen)"
            else -> "Flow rule #${index + 1}"
        }
        database.automationRuleDao().insert(
            temp.rule.copy(
                priority = priority,
                name = name
            )
        )
    }
}
