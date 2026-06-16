package com.wallpaper.changer.ui

import android.net.Uri
import android.os.Environment
import android.widget.Toast
import com.wallpaper.changer.data.AppSetting
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.wallpaper.changer.data.Album
import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.Photo
import com.wallpaper.changer.data.existsUri
import androidx.documentfile.provider.DocumentFile
import android.content.Intent
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(database: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var albums by remember { mutableStateOf(emptyList<Album>()) }
    var selectedAlbum by remember { mutableStateOf<Album?>(null) }
    var enlargedPhoto by remember { mutableStateOf<Photo?>(null) }

    BackHandler(enabled = enlargedPhoto != null || selectedAlbum != null) {
        if (enlargedPhoto != null) {
            enlargedPhoto = null
        } else {
            selectedAlbum = null
        }
    }
    var showAddAlbumDialog by remember { mutableStateOf(false) }
    var albumPhotos by remember { mutableStateOf(emptyList<Photo>()) }
    
    // Missing photo counts cache per album
    val missingCounts = remember { mutableStateMapOf<Long, Int>() }
    // Cover photo paths cache per album
    val coverPhotoPaths = remember { mutableStateMapOf<Long, String?>() }
    
    var photoForActions by remember { mutableStateOf<Photo?>(null) }

    var activeAlbumIds by remember { mutableStateOf(setOf<Long>()) }
    var activeAlbumId by remember { mutableStateOf<Long?>(null) }
    var activeWallpaperPath by remember { mutableStateOf<String?>(null) }
    var widgetHeight by remember { mutableStateOf(120f) }
    var activeDimming by remember { mutableStateOf(0) }
    var activeBlur by remember { mutableStateOf(0) }
    var activeGreyscale by remember { mutableStateOf(0) }

    fun refreshAlbums() {
        scope.launch {
            val list = database.albumDao().getAll()
            albums = list
            
            val activeIdsSetting = database.appSettingDao().getSetting("active_album_ids")
            var activeIds = activeIdsSetting?.value?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toSet() ?: emptySet()
            if (activeIds.isEmpty()) {
                val legacyActive = database.appSettingDao().getSetting("active_album_id")?.value?.toLongOrNull()
                if (legacyActive != null) {
                    activeIds = setOf(legacyActive)
                }
            }
            activeAlbumIds = activeIds

            val activeIdSetting = database.appSettingDao().getSetting("active_album_id")
            activeAlbumId = activeIdSetting?.value?.toLongOrNull()
            
            val pathSet = database.appSettingDao().getSetting("active_wallpaper_path")
            activeWallpaperPath = pathSet?.value

            val heightSet = database.appSettingDao().getSetting("wallpaper_widget_height")
            widgetHeight = heightSet?.value?.toFloatOrNull() ?: 120f

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
            activeDimming = dimming
            activeBlur = blur
            activeGreyscale = greyscale
            
            // Calculate missing photo count and fetch cover photo paths efficiently in background
            list.forEach { album ->
                scope.launch(Dispatchers.IO) {
                    // 1. Fetch cover photo path instantly using limited query
                    val coverPath = album.coverPhotoPath ?: run {
                        val limitPhotos = database.photoDao().getPhotosForAlbumLimit(album.id, 10)
                        limitPhotos.firstOrNull { it.isOnline || existsUri(context, it.path) }?.path
                    }
                    withContext(Dispatchers.Main) {
                        coverPhotoPaths[album.id] = coverPath
                    }
                    
                    // 2. Calculate missing photo count asynchronously
                    val photos = database.photoDao().getPhotosForAlbum(album.id)
                    val missing = photos.count { !it.isOnline && !existsUri(context, it.path) }
                    withContext(Dispatchers.Main) {
                        missingCounts[album.id] = missing
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                refreshAlbums()
            }
        }
        val filter = android.content.IntentFilter(com.wallpaper.changer.wallpaper.DynamicWallpaperService.ACTION_UPDATE_WALLPAPER)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        refreshAlbums()
    }

    LaunchedEffect(selectedAlbum?.id) {
        selectedAlbum?.let { album ->
            database.photoDao().getPhotosForAlbumFlow(album.id).collect { list ->
                albumPhotos = list
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedAlbum == null) {
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val calculatedWidgetHeight = screenHeight * 0.5f

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header (span = 2)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Albums / Playlists", style = MaterialTheme.typography.headlineSmall)
                        Button(onClick = { showAddAlbumDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Album")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }
                
                // Current Wallpaper Widget (span = 2)
                if (activeWallpaperPath != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val blurModifier = if (activeBlur > 0) {
                            Modifier.blur(((activeBlur / 100f) * 25f).dp)
                        } else {
                            Modifier
                        }
                        val saturationMatrix = remember(activeGreyscale) {
                            val saturation = 1f - (activeGreyscale / 100f)
                            ColorMatrix().apply {
                                setToSaturation(saturation)
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(calculatedWidgetHeight)
                                .padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Current Wallpaper", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(Color.Black, shape = RoundedCornerShape(8.dp))
                                        .clip(RoundedCornerShape(8.dp))
                                ) {
                                    AsyncImage(
                                        model = if (activeWallpaperPath!!.startsWith("content://") || activeWallpaperPath!!.startsWith("http://") || activeWallpaperPath!!.startsWith("https://")) Uri.parse(activeWallpaperPath) else File(activeWallpaperPath!!),
                                        contentDescription = "Current Wallpaper",
                                        modifier = Modifier.fillMaxSize().then(blurModifier),
                                        contentScale = ContentScale.Fit,
                                        colorFilter = if (activeGreyscale > 0) ColorFilter.colorMatrix(saturationMatrix) else null
                                    )
                                    if (activeDimming > 0) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = activeDimming / 100f))
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(onClick = {
                                        val intent = Intent(context, com.wallpaper.changer.automation.AutomationService::class.java).apply {
                                            putExtra("prev_photo", true)
                                        }
                                        context.startService(intent)
                                    }) {
                                        Text("Previous Photo")
                                    }
                                    Button(onClick = {
                                        val intent = Intent(context, com.wallpaper.changer.automation.AutomationService::class.java).apply {
                                            putExtra("force_rotate", true)
                                        }
                                        context.startService(intent)
                                    }) {
                                        Text("Next Photo")
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Albums list / grid
                if (albums.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(modifier = Modifier.fillMaxSize().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                            Text("No albums created yet. Click Add to create one.", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    items(albums) { album ->
                        AlbumItemCard(
                            album = album,
                            coverPath = coverPhotoPaths[album.id],
                            missingCount = missingCounts[album.id] ?: 0,
                            isActive = album.id in activeAlbumIds,
                            isCurrentlyActive = album.id == activeAlbumId,
                            onToggleActive = { checked ->
                                scope.launch {
                                    val currentActive = database.appSettingDao().getSetting("active_album_ids")?.value
                                    val currentList = currentActive?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toMutableList() ?: mutableListOf()
                                    if (currentList.isEmpty()) {
                                        database.appSettingDao().getSetting("active_album_id")?.value?.toLongOrNull()?.let {
                                            currentList.add(it)
                                        }
                                    }

                                    if (!checked && currentList.size <= 1 && currentList.contains(album.id)) {
                                        Toast.makeText(context, "At least 1 album must be selected.", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }

                                    if (checked) {
                                        if (!currentList.contains(album.id)) {
                                            currentList.add(album.id)
                                        }
                                    } else {
                                        currentList.remove(album.id)
                                    }
                                    
                                    val newString = currentList.joinToString(",")
                                    database.appSettingDao().insertSetting(AppSetting("active_album_ids", newString))
                                    
                                    val currentActiveId = database.appSettingDao().getSetting("active_album_id")?.value?.toLongOrNull()
                                    if (currentActiveId == null || !currentList.contains(currentActiveId)) {
                                        if (currentList.isNotEmpty()) {
                                            database.appSettingDao().insertSetting(AppSetting("active_album_id", currentList.first().toString()))
                                        } else {
                                            database.appSettingDao().deleteSetting("active_album_id")
                                        }
                                    }
                                    
                                    val serviceIntent = Intent(context, com.wallpaper.changer.automation.AutomationService::class.java).apply {
                                        putExtra("force_rotate", true)
                                    }
                                    context.startService(serviceIntent)
                                    refreshAlbums()
                                }
                            },
                            onSelect = { selectedAlbum = album },
                            onDelete = {
                                scope.launch {
                                    database.albumDao().delete(album)
                                    val currentActive = database.appSettingDao().getSetting("active_album_ids")?.value
                                    val currentList = currentActive?.split(",")?.mapNotNull { it.trim().toLongOrNull() }?.toMutableList() ?: mutableListOf()
                                    val activeIdSetting = database.appSettingDao().getSetting("active_album_id")
                                    val currentActiveId = activeIdSetting?.value?.toLongOrNull()
                                    
                                    val wasInPool = currentList.contains(album.id)
                                    val wasActive = currentActiveId == album.id
                                    
                                    if (wasInPool || wasActive) {
                                        if (wasInPool) {
                                            currentList.remove(album.id)
                                        }
                                        val remaining = database.albumDao().getAll().filter { it.id != album.id }
                                        if (currentList.isEmpty() && remaining.isNotEmpty()) {
                                            currentList.add(remaining.first().id)
                                        }
                                        
                                        val newString = currentList.joinToString(",")
                                        database.appSettingDao().insertSetting(AppSetting("active_album_ids", newString))
                                        
                                        if (currentList.isNotEmpty()) {
                                            val fallbackActiveId = if (wasActive || !currentList.contains(currentActiveId)) {
                                                currentList.first().toString()
                                            } else {
                                                currentActiveId.toString()
                                            }
                                            database.appSettingDao().insertSetting(AppSetting("active_album_id", fallbackActiveId))
                                        } else {
                                            database.appSettingDao().deleteSetting("active_album_id")
                                        }
                                    }
                                    refreshAlbums()
                                }
                            },
                            onUpdate = { updated ->
                                scope.launch {
                                    database.albumDao().update(updated)
                                    refreshAlbums()
                                }
                            }
                        )
                    }
                }
            }
        } else {
            // Photos inside selected album
            val album = selectedAlbum!!
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(album.name, style = MaterialTheme.typography.headlineSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Random Order", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = album.randomOrder,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    val updated = album.copy(randomOrder = checked)
                                    database.albumDao().update(updated)
                                    selectedAlbum = updated
                                    refreshAlbums()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Scaling: ", style = MaterialTheme.typography.bodyMedium)
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                album.scalingMode,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { expanded = true }.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                listOf("Fill", "Stretch", "Fit").forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode) },
                                        onClick = {
                                            scope.launch {
                                                val updated = album.copy(scalingMode = mode)
                                                database.albumDao().update(updated)
                                                
                                                // If the currently active photo belongs to this album and doesn't have an override,
                                                // update active_wallpaper_scaling setting and trigger AutomationService to redraw
                                                val activePathSetting = database.appSettingDao().getSetting("active_wallpaper_path")
                                                val activePath = activePathSetting?.value
                                                if (activePath != null) {
                                                    val activePhoto = database.photoDao().getAllPhotos().find { it.path == activePath }
                                                    if (activePhoto != null && activePhoto.albumId == album.id) {
                                                        if (activePhoto.scalingOverride == "None") {
                                                            database.appSettingDao().insertSetting(AppSetting("active_wallpaper_scaling", mode))
                                                            
                                                            // Trigger background service to broadcast updated scaling immediately
                                                            val serviceIntent = Intent(context, com.wallpaper.changer.automation.AutomationService::class.java)
                                                            context.startService(serviceIntent)
                                                        }
                                                    }
                                                }
                                                
                                                selectedAlbum = updated
                                                refreshAlbums()
                                            }
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        TextButton(onClick = { selectedAlbum = null }) {
                            Text("Back to Albums")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                PhotoManagementHeader(
                    albumId = album.id,
                    onPhotosAdded = { refreshAlbums() },
                    database = database
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Display photo list (already filtered asynchronously in LaunchedEffect on Dispatchers.IO)
                val gridState = rememberLazyGridState()
                var draggingIndex by remember { mutableStateOf<Int?>(null) }
                var dragOffset by remember { mutableStateOf(Offset.Zero) }

                LaunchedEffect(draggingIndex) {
                    if (draggingIndex != null) {
                        while (true) {
                            val layoutInfo = gridState.layoutInfo
                            val visibleItems = layoutInfo.visibleItemsInfo
                            val draggedItem = visibleItems.find { it.index == draggingIndex }
                            if (draggedItem != null) {
                                val currentY = draggedItem.offset.y + dragOffset.y
                                val centerY = currentY + draggedItem.size.height / 2
                                
                                val topThreshold = layoutInfo.viewportStartOffset + 120
                                val bottomThreshold = layoutInfo.viewportEndOffset - 120
                                
                                var scrollAmount = 0f
                                if (centerY < topThreshold) {
                                    val distance = topThreshold - centerY
                                    scrollAmount = -((distance / 10f).coerceIn(5f, 40f))
                                } else if (centerY > bottomThreshold) {
                                    val distance = centerY - bottomThreshold
                                    scrollAmount = (distance / 10f).coerceIn(5f, 40f)
                                }
                                
                                if (scrollAmount != 0f) {
                                    val scrolled = gridState.scrollBy(scrollAmount)
                                    if (scrolled != 0f) {
                                        dragOffset = Offset(dragOffset.x, dragOffset.y + scrolled)
                                    }
                                }
                            }
                            kotlinx.coroutines.delay(16)
                        }
                    }
                }

                if (albumPhotos.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No photos in this album yet.", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(albumPhotos) { index, photo ->
                            val isDragging = draggingIndex == index
                            
                            Box(
                                modifier = Modifier
                                    .zIndex(if (isDragging) 2f else 1f)
                                    .offset {
                                        if (isDragging) {
                                            IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                                        } else {
                                            IntOffset.Zero
                                        }
                                    }
                                    .pointerInput(photo.id, index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { offset ->
                                                draggingIndex = index
                                                dragOffset = Offset.Zero
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffset += dragAmount
                                                
                                                val layoutInfo = gridState.layoutInfo
                                                val visibleItems = layoutInfo.visibleItemsInfo
                                                val draggedItemInfo = visibleItems.find { it.index == draggingIndex }
                                                if (draggedItemInfo != null) {
                                                    val currentX = draggedItemInfo.offset.x + dragOffset.x
                                                    val currentY = draggedItemInfo.offset.y + dragOffset.y
                                                    val centerX = currentX + draggedItemInfo.size.width / 2
                                                    val centerY = currentY + draggedItemInfo.size.height / 2
                                                    
                                                    val targetItem = visibleItems.find { visibleItem ->
                                                        val left = visibleItem.offset.x
                                                        val right = visibleItem.offset.x + visibleItem.size.width
                                                        val top = visibleItem.offset.y
                                                        val bottom = visibleItem.offset.y + visibleItem.size.height
                                                        visibleItem.index != draggingIndex &&
                                                                centerX >= left && centerX <= right &&
                                                                centerY >= top && centerY <= bottom
                                                    }
                                                    
                                                    if (targetItem != null) {
                                                        val offsetA = draggedItemInfo.offset
                                                        val offsetB = targetItem.offset
                                                        
                                                        val newList = albumPhotos.toMutableList()
                                                        val fromPhoto = newList[draggingIndex!!]
                                                        val toPhoto = newList[targetItem.index]
                                                        
                                                        val finalFromPhoto = if (fromPhoto.isFavorite != toPhoto.isFavorite) {
                                                            fromPhoto.copy(isFavorite = toPhoto.isFavorite)
                                                        } else {
                                                            fromPhoto
                                                        }
                                                        val finalToPhoto = if (toPhoto.isFavorite != fromPhoto.isFavorite) {
                                                            toPhoto.copy(isFavorite = fromPhoto.isFavorite)
                                                        } else {
                                                            toPhoto
                                                        }
                                                        
                                                        newList[draggingIndex!!] = finalToPhoto
                                                        newList[targetItem.index] = finalFromPhoto
                                                        albumPhotos = newList
                                                        
                                                        dragOffset += Offset(
                                                            (offsetA.x - offsetB.x).toFloat(),
                                                            (offsetA.y - offsetB.y).toFloat()
                                                        )
                                                        draggingIndex = targetItem.index
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                val currentList = albumPhotos
                                                scope.launch(Dispatchers.IO) {
                                                    currentList.forEachIndexed { idx, p ->
                                                        database.photoDao().update(p.copy(displayOrder = idx))
                                                    }
                                                }
                                                draggingIndex = null
                                            },
                                            onDragCancel = {
                                                draggingIndex = null
                                            }
                                        )
                                    }
                            ) {
                                PhotoGridCard(
                                    photo = photo,
                                    onClick = {
                                        enlargedPhoto = photo
                                    },
                                    onDelete = {
                                        scope.launch {
                                            database.photoDao().delete(photo)
                                            refreshAlbums()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add Album dialog
        if (showAddAlbumDialog) {
            var newAlbumName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddAlbumDialog = false },
                title = { Text("Add New Album") },
                text = {
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it },
                        label = { Text("Album Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newAlbumName.isNotBlank()) {
                                scope.launch {
                                    database.albumDao().insert(Album(name = newAlbumName))
                                    refreshAlbums()
                                    showAddAlbumDialog = false
                                }
                            }
                        }
                    ) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddAlbumDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Full-screen Enlarged Photo dialog viewer
        if (enlargedPhoto != null) {
            val photo = enlargedPhoto!!
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { enlargedPhoto = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f))
                ) {
                    // Top: Path display
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { enlargedPhoto = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = photo.path,
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val resolvedMode = if (photo.scalingOverride != "None") {
                        photo.scalingOverride
                    } else {
                        selectedAlbum?.scalingMode ?: "Fill"
                    }
                    val contentScale = when (resolvedMode) {
                        "Fill" -> ContentScale.Crop
                        "Stretch" -> ContentScale.FillBounds
                        "Fit" -> ContentScale.Fit
                        else -> ContentScale.Crop
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp, bottom = 180.dp)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(9f / 16f)
                                .background(Color.DarkGray, shape = RoundedCornerShape(24.dp))
                                .border(4.dp, Color.LightGray, shape = RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                        ) {
                            AsyncImage(
                                model = if (photo.path.startsWith("content://")) Uri.parse(photo.path) else File(photo.path),
                                contentDescription = "Enlarged photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = contentScale
                            )
                            
                            // Visual hints inside the phone frame (Mock Status Bar)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp, start = 16.dp, end = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("9:41 AM", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("5G", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                                        Text("100%", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                                    }
                                }
                            }
                            
                            // Mock App Icons Dock
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    repeat(4) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(Color.White.copy(alpha = 0.3f), shape = CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom: Action buttons
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Scaling override dropdown selector
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("Scaling Override: ", color = Color.White, fontSize = 14.sp)
                            var overrideExpanded by remember { mutableStateOf(false) }
                            Box {
                                Text(
                                    text = photo.scalingOverride,
                                    fontSize = 14.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { overrideExpanded = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                                DropdownMenu(
                                    expanded = overrideExpanded,
                                    onDismissRequest = { overrideExpanded = false }
                                ) {
                                    listOf("None", "Fill", "Stretch", "Fit").forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode) },
                                            onClick = {
                                                scope.launch {
                                                    val updated = photo.copy(scalingOverride = mode)
                                                    database.photoDao().update(updated)
                                                    enlargedPhoto = updated
                                                    refreshAlbums()
                                                }
                                                overrideExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val updated = photo.copy(isFavorite = !photo.isFavorite)
                                        database.photoDao().update(updated)
                                        enlargedPhoto = updated
                                        refreshAlbums()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(if (photo.isFavorite) "Unfavorite" else "Favorite")
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        val currentAlbum = selectedAlbum
                                        if (currentAlbum != null) {
                                            val updatedAlbum = currentAlbum.copy(coverPhotoPath = photo.path)
                                            database.albumDao().update(updatedAlbum)
                                            selectedAlbum = updatedAlbum
                                            refreshAlbums()
                                            Toast.makeText(context, "Cover photo updated!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Set Cover")
                            }

                            Button(
                                onClick = {
                                    scope.launch {
                                        database.appSettingDao().insertSetting(AppSetting("active_wallpaper_path", photo.path))
                                        val scaleMode = if (photo.scalingOverride != "None") {
                                            photo.scalingOverride
                                        } else {
                                            selectedAlbum?.scalingMode ?: "Fill"
                                        }
                                        database.appSettingDao().insertSetting(AppSetting("active_wallpaper_scaling", scaleMode))
                                        
                                        val serviceIntent = Intent(context, com.wallpaper.changer.automation.AutomationService::class.java).apply {
                                            putExtra("apply_wallpaper", true)
                                        }
                                        context.startService(serviceIntent)
                                        Toast.makeText(context, "Wallpaper updated!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Set Wallpaper")
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    database.photoDao().delete(photo)
                                    enlargedPhoto = null
                                    refreshAlbums()
                                    Toast.makeText(context, "Photo removed from album.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Text("Delete Photo")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumItemCard(
    album: Album,
    coverPath: String?,
    missingCount: Int,
    isActive: Boolean,
    isCurrentlyActive: Boolean,
    onToggleActive: (Boolean) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (Album) -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    val borderModifier = if (isActive) {
        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(borderModifier)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (coverPath != null) {
                AsyncImage(
                    model = if (coverPath.startsWith("content://")) Uri.parse(coverPath) else File(coverPath),
                    contentDescription = "Album cover",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = "Folder",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isActive,
                    onCheckedChange = { checked ->
                        onToggleActive(checked)
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = Color.White.copy(alpha = 0.8f)
                    )
                )

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete Album",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(8.dp)
            ) {
                Text(
                    text = album.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                
                if (missingCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = Color.Red,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$missingCount Missing",
                            color = Color.Red,
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Album") },
            text = { Text("Are you sure you want to delete '${album.name}'? All photo mappings inside will be removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun getOriginalDisplayName(context: android.content.Context, uri: Uri): String {
    var name = ""
    try {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    if (name.isBlank()) {
        name = DocumentFile.fromSingleUri(context, uri)?.name ?: ""
    }
    if (name.isBlank()) {
        name = "photo_${System.currentTimeMillis()}.jpg"
    }
    return name
}

fun copyUriToInternal(context: android.content.Context, uri: Uri, targetFileName: String): String? {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val photosDir = File(context.filesDir, "photos")
        if (!photosDir.exists()) {
            photosDir.mkdirs()
        }
        var destFile = File(photosDir, targetFileName)
        if (destFile.exists()) {
            val nameWithoutExt = targetFileName.substringBeforeLast(".")
            val ext = targetFileName.substringAfterLast(".", "jpg")
            destFile = File(photosDir, "${nameWithoutExt}_${System.currentTimeMillis()}.$ext")
        }
        destFile.outputStream().use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        return destFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}

@Composable
fun PhotoManagementHeader(albumId: Long, onPhotosAdded: () -> Unit, database: AppDatabase) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var isScanning by remember { mutableStateOf(false) }

    var duplicateUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showDuplicateDialog by remember { mutableStateOf(false) }

    var duplicateFolders by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var pendingFolderPhotos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var showFolderDuplicateDialog by remember { mutableStateOf(false) }

    // Multi-photo picker launcher (visual file select UI for standard Photos/Images)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val existingPhotos = database.photoDao().getPhotosForAlbum(albumId)
                    val duplicates = uris.filter { uri ->
                        val name = getOriginalDisplayName(context, uri)
                        existingPhotos.any { it.path.endsWith(name) }
                    }
                    
                    if (duplicates.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            duplicateUris = duplicates
                            pendingUris = uris
                            showDuplicateDialog = true
                        }
                    } else {
                        val photos = uris.mapNotNull { uri ->
                            val name = getOriginalDisplayName(context, uri)
                            val localPath = copyUriToInternal(context, uri, name)
                            if (localPath != null) {
                                Photo(albumId = albumId, path = localPath)
                            } else {
                                null
                            }
                        }
                        database.photoDao().insertAll(photos)
                        withContext(Dispatchers.Main) {
                            onPhotosAdded()
                        }
                    }
                }
            }
        }
    )

    // Visual Folder Tree Picker launcher (uses SAF tree document selection UI)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                isScanning = true
                scope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val addedPhotos = mutableListOf<Photo>()

                    fun scanTree(parentDocId: String) {
                        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentDocId)
                        val projection = arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                        )
                        try {
                            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                while (cursor.moveToNext()) {
                                    val docId = cursor.getString(idColumn)
                                    val mime = cursor.getString(mimeColumn) ?: ""
                                    val name = cursor.getString(nameColumn) ?: ""
                                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                                        scanTree(docId)
                                    } else {
                                        val isImage = mime.startsWith("image/") || 
                                                      name.endsWith(".jpg", ignoreCase = true) || 
                                                      name.endsWith(".jpeg", ignoreCase = true) || 
                                                      name.endsWith(".png", ignoreCase = true) || 
                                                      name.endsWith(".webp", ignoreCase = true) || 
                                                      name.endsWith(".gif", ignoreCase = true)
                                        if (isImage) {
                                            val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                                            addedPhotos.add(Photo(albumId = albumId, path = childUri.toString()))
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    try {
                        val rootDocId = DocumentsContract.getTreeDocumentId(uri)
                        scanTree(rootDocId)
                        
                        val existingPhotos = database.photoDao().getPhotosForAlbum(albumId)
                        val existingPaths = existingPhotos.map { it.path }.toSet()
                        val duplicates = addedPhotos.filter { existingPaths.contains(it.path) }
                        
                        if (duplicates.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                duplicateFolders = duplicates
                                pendingFolderPhotos = addedPhotos
                                showFolderDuplicateDialog = true
                            }
                        } else {
                            database.photoDao().insertAll(addedPhotos)
                            withContext(Dispatchers.Main) {
                                isScanning = false
                                onPhotosAdded()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            isScanning = false
                        }
                    }
                }
            }
        }
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Picker
            Button(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.weight(1f),
                enabled = !isScanning
            ) {
                Icon(Icons.Default.Image, contentDescription = "Add Photos")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Photos")
            }

            // Folder scan button (launches standard Document Tree chooser)
            Button(
                onClick = { folderPickerLauncher.launch(null) },
                modifier = Modifier.weight(1f),
                enabled = !isScanning
            ) {
                Icon(Icons.Default.Folder, contentDescription = "Select Folder")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Select Folder")
            }
        }

        if (isScanning) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Scanning directory hierarchy visually...",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    if (showDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = false },
            title = { Text("Duplicate Photos Warning") },
            text = {
                Text("You are adding ${pendingUris.size} photos, and ${duplicateUris.size} of them are already in this album. Do you want to skip duplicates or add them anyway?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val existingPhotos = database.photoDao().getPhotosForAlbum(albumId)
                            val nonDuplicates = pendingUris.filter { uri ->
                                val name = getOriginalDisplayName(context, uri)
                                !existingPhotos.any { it.path.endsWith(name) }
                            }
                            val photos = nonDuplicates.mapNotNull { uri ->
                                val name = getOriginalDisplayName(context, uri)
                                val localPath = copyUriToInternal(context, uri, name)
                                if (localPath != null) {
                                    Photo(albumId = albumId, path = localPath)
                                } else {
                                    null
                                }
                            }
                            database.photoDao().insertAll(photos)
                            withContext(Dispatchers.Main) {
                                showDuplicateDialog = false
                                onPhotosAdded()
                            }
                        }
                    }
                ) {
                    Text("Skip Duplicates")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val photos = pendingUris.mapNotNull { uri ->
                                    val name = getOriginalDisplayName(context, uri)
                                    val localPath = copyUriToInternal(context, uri, name)
                                    if (localPath != null) {
                                        Photo(albumId = albumId, path = localPath)
                                    } else {
                                        null
                                    }
                                }
                                database.photoDao().insertAll(photos)
                                withContext(Dispatchers.Main) {
                                    showDuplicateDialog = false
                                    onPhotosAdded()
                                }
                            }
                        }
                    ) {
                        Text("Add All Anyway")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = { showDuplicateDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    if (showFolderDuplicateDialog) {
        AlertDialog(
            onDismissRequest = { showFolderDuplicateDialog = false },
            title = { Text("Duplicate Photos Warning") },
            text = {
                Text("Folder scan found ${pendingFolderPhotos.size} photos, and ${duplicateFolders.size} of them are already in this album. Do you want to skip duplicates or add them anyway?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val existingPhotos = database.photoDao().getPhotosForAlbum(albumId)
                            val existingPaths = existingPhotos.map { it.path }.toSet()
                            val nonDuplicates = pendingFolderPhotos.filter { !existingPaths.contains(it.path) }
                            database.photoDao().insertAll(nonDuplicates)
                            withContext(Dispatchers.Main) {
                                showFolderDuplicateDialog = false
                                isScanning = false
                                onPhotosAdded()
                            }
                        }
                    }
                ) {
                    Text("Skip Duplicates")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                database.photoDao().insertAll(pendingFolderPhotos)
                                withContext(Dispatchers.Main) {
                                    showFolderDuplicateDialog = false
                                    isScanning = false
                                    onPhotosAdded()
                                }
                            }
                        }
                    ) {
                        Text("Add All Anyway")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { 
                            showFolderDuplicateDialog = false
                            isScanning = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
fun PhotoGridCard(photo: Photo, onClick: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var isMissing by remember { mutableStateOf(false) }
    
    LaunchedEffect(photo) {
        if (!photo.isOnline) {
            withContext(Dispatchers.IO) {
                isMissing = !existsUri(context, photo.path)
            }
        }
    }
    
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (photo.isFavorite) {
                    Modifier.border(2.5.dp, Color(0xFFFFD700), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
    ) {
        if (isMissing) {
            // Placeholder view
            Column(
                 modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
                 verticalArrangement = Arrangement.SpaceBetween,
                 horizontalAlignment = Alignment.CenterHorizontally
             ) {
                 Icon(
                     Icons.Default.Warning, 
                     contentDescription = "Missing File", 
                     tint = MaterialTheme.colorScheme.onErrorContainer,
                     modifier = Modifier.size(28.dp).weight(1f)
                 )
                 
                 val displayName = remember(photo.path) {
                     if (photo.path.startsWith("content://")) {
                         try {
                             val uri = Uri.parse(photo.path)
                             DocumentFile.fromSingleUri(context, uri)?.name ?: "content_file"
                         } catch (e: Exception) {
                             "content_file"
                         }
                     } else {
                         File(photo.path).name
                     }
                 }
                 
                 Text(
                     text = displayName,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onErrorContainer,
                     maxLines = 1,
                     modifier = Modifier.padding(bottom = 4.dp)
                 )
                 IconButton(
                     onClick = onDelete,
                     modifier = Modifier.size(24.dp)
                 ) {
                     Icon(Icons.Default.Delete, contentDescription = "Delete Dead Path", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                 }
             }
        } else {
            // Standard loaded image
            AsyncImage(
                model = if (photo.path.startsWith("content://")) Uri.parse(photo.path) else File(photo.path),
                contentDescription = "Image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // Delete button on top right of image
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp).background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Photo", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
