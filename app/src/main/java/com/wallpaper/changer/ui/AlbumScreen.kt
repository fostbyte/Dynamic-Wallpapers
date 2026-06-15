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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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

    BackHandler(enabled = selectedAlbum != null) {
        selectedAlbum = null
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
            
            // Calculate missing photo count in background
            list.forEach { album ->
                scope.launch(Dispatchers.IO) {
                    val photos = database.photoDao().getPhotosForAlbum(album.id)
                    val missing = photos.count { !it.isOnline && !existsUri(context, it.path) }
                    missingCounts[album.id] = missing
                    
                    val firstPhotoPath = photos.firstOrNull { it.isOnline || existsUri(context, it.path) }?.path
                    coverPhotoPaths[album.id] = album.coverPhotoPath ?: firstPhotoPath
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

    LaunchedEffect(selectedAlbum?.id, selectedAlbum?.showPlaceholders) {
        selectedAlbum?.let { album ->
            database.photoDao().getPhotosForAlbumFlow(album.id).collect { list ->
                scope.launch(Dispatchers.IO) {
                    val filtered = if (album.showPlaceholders) {
                        list
                    } else {
                        coroutineScope {
                            list.map { photo ->
                                async {
                                    val exists = photo.isOnline || existsUri(context, photo.path)
                                    if (exists) photo else null
                                }
                            }.awaitAll().filterNotNull()
                        }
                    }
                    withContext(Dispatchers.Main) {
                        albumPhotos = filtered
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selectedAlbum == null) {
            // Album list
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                
                Spacer(modifier = Modifier.height(16.dp))

                if (activeWallpaperPath != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Current Wallpaper", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .background(Color.Black, shape = RoundedCornerShape(8.dp))
                            ) {
                                AsyncImage(
                                    model = if (activeWallpaperPath!!.startsWith("content://") || activeWallpaperPath!!.startsWith("http://") || activeWallpaperPath!!.startsWith("https://")) Uri.parse(activeWallpaperPath) else File(activeWallpaperPath!!),
                                    contentDescription = "Current Wallpaper",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
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
                
                if (albums.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No albums created yet. Click Add to create one.", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                val displayPhotos = albumPhotos
                
                if (displayPhotos.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No photos in this album yet.", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayPhotos) { photo ->
                            PhotoGridCard(
                                photo = photo,
                                onDelete = {
                                    scope.launch {
                                        database.photoDao().delete(photo)
                                        refreshAlbums()
                                    }
                                },
                                onLongClick = {
                                    photoForActions = photo
                                }
                            )
                        }
                    }
                }
            }
        }

        // Actions Dialog for long press
        if (photoForActions != null) {
            AlertDialog(
                onDismissRequest = { photoForActions = null },
                title = { Text("Photo Actions") },
                text = { Text("Choose what you would like to do with this photo:") },
                confirmButton = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val clickedPhoto = photoForActions!!
                                    database.appSettingDao().insertSetting(AppSetting("active_wallpaper_path", clickedPhoto.path))
                                    val scaleMode = if (clickedPhoto.scalingOverride != "None") {
                                        clickedPhoto.scalingOverride
                                    } else {
                                        selectedAlbum?.scalingMode ?: "Fill"
                                    }
                                    database.appSettingDao().insertSetting(AppSetting("active_wallpaper_scaling", scaleMode))
                                    
                                    val serviceIntent = Intent(context, com.wallpaper.changer.automation.AutomationService::class.java).apply {
                                        putExtra("apply_wallpaper", true)
                                    }
                                    context.startService(serviceIntent)
                                    
                                    Toast.makeText(context, "Wallpaper updated!", Toast.LENGTH_SHORT).show()
                                    photoForActions = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
                        ) {
                            Text("Set as Wallpaper")
                        }
                        
                        Button(
                            onClick = {
                                scope.launch {
                                    val currentAlbum = selectedAlbum
                                    if (currentAlbum != null && photoForActions != null) {
                                        val updatedAlbum = currentAlbum.copy(coverPhotoPath = photoForActions!!.path)
                                        database.albumDao().update(updatedAlbum)
                                        selectedAlbum = updatedAlbum
                                        refreshAlbums()
                                    }
                                    Toast.makeText(context, "Album cover updated!", Toast.LENGTH_SHORT).show()
                                    photoForActions = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Set as Cover")
                        }
                        
                        TextButton(
                            onClick = { photoForActions = null },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }
                },
                dismissButton = null
            )
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
    
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Cover photo preview
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color.Gray, shape = RoundedCornerShape(4.dp))
                    ) {
                        if (coverPath != null) {
                            AsyncImage(
                                model = if (coverPath.startsWith("content://")) Uri.parse(coverPath) else File(coverPath),
                                contentDescription = "Cover photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = "Folder",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp).align(Alignment.Center)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(album.name, style = MaterialTheme.typography.titleMedium)
                            if (missingCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color.Red.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color.Red, modifier = Modifier.size(10.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text("$missingCount Missing", color = Color.Red, fontSize = 8.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Album", tint = MaterialTheme.colorScheme.error)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scaling mode dropdown/selection
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        onUpdate(album.copy(scalingMode = mode))
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Show placeholders switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show Placeholders", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = album.showPlaceholders,
                        onCheckedChange = { checked ->
                            onUpdate(album.copy(showPlaceholders = checked))
                        }
                    )
                }
 
                // Random order switch
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Random Order", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = album.randomOrder,
                        onCheckedChange = { checked ->
                            onUpdate(album.copy(randomOrder = checked))
                        }
                    )
                }
            }
 
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
 
            // Active album checkbox & badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isActive,
                        onCheckedChange = { checked ->
                            onToggleActive(checked)
                        }
                    )
                    Text("Select as Active Album", style = MaterialTheme.typography.bodyMedium)
                }
                
                if (isCurrentlyActive) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF689F38), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Active / In Use", color = Color.White, fontSize = 10.sp)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGridCard(photo: Photo, onDelete: () -> Unit, onLongClick: () -> Unit) {
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
            .background(Color.DarkGray)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongClick
            )
    ) {
        if (isMissing) {
            // Placeholder view
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFFE57373)).padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Warning, 
                    contentDescription = "Missing File", 
                    tint = Color.White,
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
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Dead Path", tint = Color.White, modifier = Modifier.size(16.dp))
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
