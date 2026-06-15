package com.wallpaper.changer.ui.hobbyist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    val type: String, // "Time", "Location", "And", "Or", "Xor", "Not", "NextWallpaper", "SwitchAlbum", "Interval", "Gesture"
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
    val gestureType: String = "DoubleTap"
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
    
    var nodes by remember { mutableStateOf(emptyList<Node>()) }
    var connections by remember { mutableStateOf(emptyList<Connection>()) }
    var albums by remember { mutableStateOf(emptyList<Album>()) }
    
    // Tap to connect tracking state
    var selectedPortNode by remember { mutableStateOf<String?>(null) }
    var selectedPortType by remember { mutableStateOf<String?>(null) } // "Input" or "Output"
    var selectedPortIndex by remember { mutableStateOf<Int?>(null) }

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
                    gestureType = payloadMap["gestureType"] as? String ?: "DoubleTap"
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
                    "gestureType" to node.gestureType
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
            
            refreshGraph()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Flowchart Rule Builder", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Add Nodes dropdown/menu
                var expandedAddNode by remember { mutableStateOf(false) }
                Box {
                    Button(onClick = { expandedAddNode = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Node")
                        Text("Add Node")
                    }
                    DropdownMenu(expanded = expandedAddNode, onDismissRequest = { expandedAddNode = false }) {
                        val nodeTypes = listOf(
                            "Time Trigger" to "Time",
                            "Location Trigger" to "Location",
                            "Interval Trigger" to "Interval",
                            "Gesture Trigger" to "Gesture",
                            "AND Gate" to "And",
                            "OR Gate" to "Or",
                            "XOR Gate" to "Xor",
                            "NOT Gate" to "Not",
                            "Next Wallpaper" to "NextWallpaper",
                            "Switch Album" to "SwitchAlbum"
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
                    onClick = { saveAndCompileGraph() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
                ) {
                    Icon(Icons.Default.Save, contentDescription = "Save & Apply")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Apply Flow")
                }
            }
        }

        // Canvas Area
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFFAFAFA))
        ) {
            // Draw connections as paths
            Canvas(modifier = Modifier.fillMaxSize()) {
                connections.forEach { conn ->
                    val fromNode = nodes.find { it.id == conn.fromNode }
                    val toNode = nodes.find { it.id == conn.toNode }
                    if (fromNode != null && toNode != null) {
                        // Calculate vertical offset for output port (always 64.dp from top)
                        val fromPortY = 64.dp.toPx()
                        
                        // Calculate vertical offset for input port
                        val toPorts = when (toNode.type) {
                            "And", "Or", "Xor" -> listOf(50.dp, 78.dp)
                            "Not", "NextWallpaper", "SwitchAlbum" -> listOf(64.dp)
                            else -> emptyList()
                        }
                        val toPortY = toPorts.getOrNull(conn.toPort)?.toPx() ?: 64.dp.toPx()
                        
                        val fromOffset = Offset(
                            x = fromNode.x + 160.dp.toPx(),
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
                            color = Color(0xFF689F38),
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
                    onPositionChanged = { newX, newY ->
                        nodes = nodes.map {
                            if (it.id == node.id) it.copy(x = newX, y = newY) else it
                        }
                    },
                    onTimeChanged = { newTime ->
                        nodes = nodes.map {
                            if (it.id == node.id) it.copy(time = newTime) else it
                        }
                    },
                    onLocationChanged = { newLat, newLng, newRadius ->
                        nodes = nodes.map {
                            if (it.id == node.id) it.copy(lat = newLat, lng = newLng, radius = newRadius) else it
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
                                connections = connections + Connection(
                                    fromNode = firstNode,
                                    fromPort = firstIndex,
                                    toNode = node.id,
                                    toPort = portIndex
                                )
                            } else if (firstType == "Input" && portType == "Output" && firstNode != node.id) {
                                connections = connections + Connection(
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

@Composable
fun PortCircle(
    isSelected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clickable { onClick() }
            .height(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (isSelected) Color.Green else Color.Gray, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
fun NodeCard(
    node: Node,
    albums: List<Album>,
    onPositionChanged: (Float, Float) -> Unit,
    onTimeChanged: (String) -> Unit,
    onLocationChanged: (String, String, String) -> Unit,
    onAlbumIdsChanged: (Long, String) -> Unit,
    onIntervalChanged: (Int, String, Boolean) -> Unit,
    onGestureChanged: (String) -> Unit,
    onDelete: () -> Unit,
    onPortTap: (String, Int) -> Unit,
    isPortSelected: (String, Int) -> Boolean
) {
    val nodeWidth = 160.dp
    
    // Header colors based on categories
    val headerColor = when (node.type) {
        "Time", "Location", "Interval", "Gesture" -> Color(0xFF1E88E5) // Blue for Triggers
        "And", "Or", "Xor", "Not" -> Color(0xFFFB8C00) // Orange for Logic Gates
        else -> Color(0xFF8E24AA) // Purple for Actions
    }

    val currentOnPositionChanged by rememberUpdatedState(onPositionChanged)
    val currentNode by rememberUpdatedState(node)

    Box(
        modifier = Modifier
            .offset { IntOffset(node.x.roundToInt(), node.y.roundToInt()) }
            .width(nodeWidth)
            .background(Color.White, shape = RoundedCornerShape(8.dp))
            .border(1.dp, Color.LightGray, shape = RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnPositionChanged(currentNode.x + dragAmount.x, currentNode.y + dragAmount.y)
                }
            }
    ) {
        Column {
            // Title Header: Fixed height 36.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(headerColor, shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(node.title, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(16.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }

            // Body: Fixed height 56.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp)
            ) {
                // Input Ports Column/Box on the Left
                val inputPorts = when (node.type) {
                    "And", "Or", "Xor" -> listOf("In 1", "In 2")
                    "Not", "NextWallpaper", "SwitchAlbum" -> listOf("In")
                    else -> emptyList()
                }
                
                if (inputPorts.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .fillMaxHeight()
                            .align(Alignment.CenterStart)
                    ) {
                        if (inputPorts.size == 1) {
                            PortCircle(
                                isSelected = isPortSelected("Input", 0),
                                label = inputPorts[0],
                                onClick = { onPortTap("Input", 0) },
                                modifier = Modifier.align(Alignment.CenterStart)
                            )
                        } else {
                            PortCircle(
                                isSelected = isPortSelected("Input", 0),
                                label = inputPorts[0],
                                onClick = { onPortTap("Input", 0) },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(y = 8.dp)
                            )
                            PortCircle(
                                isSelected = isPortSelected("Input", 1),
                                label = inputPorts[1],
                                onClick = { onPortTap("Input", 1) },
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(y = 36.dp)
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
                            Text(
                                node.time,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showTimeDial = true }
                            )
                            if (showTimeDial) {
                                var tempTime by remember { mutableStateOf(node.time) }
                                AlertDialog(
                                    onDismissRequest = { showTimeDial = false },
                                    title = { Text("Set Time") },
                                    text = {
                                        OutlinedTextField(
                                            value = tempTime,
                                            onValueChange = { tempTime = it },
                                            label = { Text("HH:mm") }
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            onTimeChanged(tempTime)
                                            showTimeDial = false
                                        }) { Text("OK") }
                                    }
                                )
                            }
                        }
                        "Location" -> {
                            var showCoordsDialog by remember { mutableStateOf(false) }
                            Text(
                                "Coords",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showCoordsDialog = true }
                            )
                            if (showCoordsDialog) {
                                var tempLat by remember { mutableStateOf(node.lat) }
                                var tempLng by remember { mutableStateOf(node.lng) }
                                var tempRad by remember { mutableStateOf(node.radius) }
                                
                                AlertDialog(
                                    onDismissRequest = { showCoordsDialog = false },
                                    title = { Text("Set Geofence") },
                                    text = {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().height(250.dp),
                                                colors = CardDefaults.cardColors(containerColor = Color.LightGray)
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
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                onLocationChanged(tempLat, tempLng, tempRad)
                                                showCoordsDialog = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
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
                            
                            Text(
                                displayStr,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { expandedDropdown = true }
                            )
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
                            val orderText = if (node.randomOrder) "Rand" else "Seq"
                            Text(
                                "${node.intervalValue}$unitAbbr ($orderText)",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { showIntervalDialog = true }
                            )
                            if (showIntervalDialog) {
                                var tempValue by remember { mutableStateOf(node.intervalValue.toString()) }
                                var tempUnit by remember { mutableStateOf(node.intervalUnit) }
                                var tempRandom by remember { mutableStateOf(node.randomOrder) }
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
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Random Order")
                                                Switch(
                                                    checked = tempRandom,
                                                    onCheckedChange = { tempRandom = it }
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        Button(
                                            onClick = {
                                                val intVal = tempValue.toIntOrNull() ?: 10
                                                onIntervalChanged(intVal, tempUnit, tempRandom)
                                                showIntervalDialog = false
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
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
                        "Gesture" -> {
                            var expandedGesture by remember { mutableStateOf(false) }
                            Text(
                                node.gestureType,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { expandedGesture = true }
                            )
                            DropdownMenu(expanded = expandedGesture, onDismissRequest = { expandedGesture = false }) {
                                listOf("DoubleTap", "TwoFingerDoubleTap", "ThreeFingerTripleTap").forEach { gesture ->
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
                    }
                }
                
                // Output Port Column (Right)
                val hasOutput = when (node.type) {
                    "Time", "Location", "Interval", "Gesture", "And", "Or", "Xor", "Not" -> true
                    else -> false
                }
                
                if (hasOutput) {
                    PortCircle(
                        isSelected = isPortSelected("Output", 0),
                        label = "Out",
                        onClick = { onPortTap("Output", 0) },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

// --- FLOW GRAPH COMPILER ---
private suspend fun compileGraphToRules(database: AppDatabase, nodes: List<Node>, connections: List<Connection>) {
    // We clear all existing rules and compile the flow connections.
    // Standard triggers connect to actions: Time/Location -> NextWallpaper/SwitchAlbum
    // Or Time & Location (via AND gate) -> Action
    database.automationRuleDao().deleteAll()

    // Find all Action nodes
    val actionNodes = nodes.filter { it.type == "NextWallpaper" || it.type == "SwitchAlbum" }
    
    var ruleCount = 0

    actionNodes.forEach { actionNode ->
        val parentConns = connections.filter { it.toNode == actionNode.id }
        
        for (parentConn in parentConns) {
            val sourceNode = nodes.find { it.id == parentConn.fromNode } ?: continue
            
            if (sourceNode.type == "Time") {
                ruleCount++
                database.automationRuleDao().insert(
                    AutomationRule(
                        name = "Flow rule #$ruleCount (${sourceNode.time})",
                        type = "Time",
                        priority = ruleCount,
                        time = sourceNode.time,
                        daysOfWeek = sourceNode.days,
                        randomOrder = sourceNode.randomOrder,
                        actionType = actionNode.type,
                        targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                        targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null
                    )
                )
            } else if (sourceNode.type == "Location") {
                ruleCount++
                database.automationRuleDao().insert(
                    AutomationRule(
                        name = "Flow rule #$ruleCount (Geofence)",
                        type = "Location",
                        priority = ruleCount,
                        latitude = sourceNode.lat.toDoubleOrNull(),
                        longitude = sourceNode.lng.toDoubleOrNull(),
                        radius = sourceNode.radius.toFloatOrNull(),
                        locationAccuracy = "Loose",
                        randomOrder = sourceNode.randomOrder,
                        actionType = actionNode.type,
                        targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                        targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null
                    )
                )
            } else if (sourceNode.type == "Interval") {
                ruleCount++
                database.automationRuleDao().insert(
                    AutomationRule(
                        name = "Flow rule #$ruleCount (Interval)",
                        type = "Interval",
                        priority = ruleCount,
                        intervalValue = sourceNode.intervalValue,
                        intervalUnit = sourceNode.intervalUnit,
                        randomOrder = sourceNode.randomOrder,
                        actionType = actionNode.type,
                        targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                        targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null
                    )
                )
            } else if (sourceNode.type == "Gesture") {
                ruleCount++
                database.automationRuleDao().insert(
                    AutomationRule(
                        name = "Flow rule #$ruleCount (Gesture)",
                        type = "Gesture",
                        priority = ruleCount,
                        gestureType = sourceNode.gestureType,
                        randomOrder = sourceNode.randomOrder,
                        actionType = actionNode.type,
                        targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                        targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null
                    )
                )
            } else if (sourceNode.type == "And" || sourceNode.type == "Or" || sourceNode.type == "Xor") {
                val gateConns = connections.filter { it.toNode == sourceNode.id }
                var timeNode: Node? = null
                var locNode: Node? = null
                
                gateConns.forEach { gateConn ->
                    val gateSource = nodes.find { it.id == gateConn.fromNode }
                    if (gateSource?.type == "Time") timeNode = gateSource
                    if (gateSource?.type == "Location") locNode = gateSource
                }
                
                if (timeNode != null && locNode != null) {
                    val logicStr = sourceNode.type.uppercase()
                    ruleCount++
                    database.automationRuleDao().insert(
                        AutomationRule(
                            name = "Flow rule #$ruleCount (Time $logicStr Geofence)",
                            type = "Time", 
                            priority = ruleCount,
                            time = timeNode!!.time,
                            daysOfWeek = timeNode!!.days,
                            latitude = locNode!!.lat.toDoubleOrNull(),
                            longitude = locNode!!.lng.toDoubleOrNull(),
                            radius = locNode!!.radius.toFloatOrNull(),
                            locationAccuracy = "Loose",
                            connectionLogic = logicStr,
                            randomOrder = timeNode!!.randomOrder || locNode!!.randomOrder,
                            actionType = actionNode.type,
                            targetAlbumId = if (actionNode.type == "SwitchAlbum") actionNode.albumId else null,
                            targetAlbumIds = if (actionNode.type == "SwitchAlbum") actionNode.albumIds else null
                        )
                    )
                }
            }
        }
    }
}
