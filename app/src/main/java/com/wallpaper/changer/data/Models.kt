package com.wallpaper.changer.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "albums")
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val showPlaceholders: Boolean = false,
    val scalingMode: String = "Fill", // "Fill", "Stretch", "Fit"
    val coverPhotoPath: String? = null,
    val randomOrder: Boolean = false,
    val googlePhotosUrl: String? = null,
    val refreshIntervalValue: Int? = null,
    val refreshIntervalUnit: String? = null, // "Minutes", "Hours", "Days"
    val lastRefreshedTime: Long? = null,
    val isHidden: Boolean = false,
    val lockType: String? = null, // "PIN", "Gesture", "Device"
    val lockValue: String? = null // PIN string (e.g. "1234") or gesture pattern representation string (e.g. "0,1,2")
)

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = Album::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["albumId"])]
)
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumId: Long,
    val path: String, // File path (local hidden path, or web URL)
    val scalingOverride: String = "None", // "None", "Fill", "Stretch", "Fit"
    val isOnline: Boolean = false,
    val cachePath: String? = null,
    val isFavorite: Boolean = false,
    val displayOrder: Int = 0,
    val wasSeen: Boolean = false
)

@Entity(tableName = "automation_rules")
data class AutomationRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // "Time", "Location"
    val priority: Int, // Higher number = higher priority
    val isEnabled: Boolean = true,
    
    // Time triggers: days of week (e.g. "Mon,Tue,Wed"), time (e.g. "17:35")
    val daysOfWeek: String? = null,
    val time: String? = null,
    
    // Location triggers: lat, lng, radius (meters), accuracy ("Loose", "Precise")
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radius: Float? = null,
    val locationAccuracy: String? = null, // "Loose", "Precise"
    
    // Connection logic: "NONE", "AND", "OR", "XOR"
    val connectionLogic: String = "NONE",
    
    // Actions
    val actionType: String, // "NextWallpaper", "SwitchAlbum"
    val targetAlbumId: Long? = null, // For "SwitchAlbum"
    val targetAlbumIds: String? = null, // For "SwitchAlbum" (comma-separated list of IDs)
    
    // Interval triggers: interval value (e.g. 10), interval unit (e.g. "Minutes"), random order flag
    val intervalValue: Int? = null,
    val intervalUnit: String? = null, // "Seconds", "Minutes", "Hours", "Days"
    val randomOrder: Boolean = false,
    
    // Gesture triggers: gesture type (e.g. "DoubleTap", "TripleTap")
    val gestureType: String? = null,
    
    // Extra conditions
    val timeCondition: String? = "At",
    val locationCondition: String? = "Entering",
    
    // Visual effects (0-100 values)
    val dimmingPercent: Int? = null,
    val blurPercent: Int? = null,
    val greyscalePercent: Int? = null,
    
    // WiFi triggers: WiFi state ("Connecting", "Disconnecting"), SSID name (optional)
    val wifiState: String? = null,
    val wifiSsid: String? = null,
    val playFavoritesOnly: Boolean = false,

    // Unlock / HomeScreen trigger conditions
    val lockCondition: String? = null,       // e.g. "Always"
    val homeScreenCondition: String? = null  // e.g. "Always"
)

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String, // "Time", "Location", "And", "Or", "Not", "ActionNext", "ActionAlbum"
    val x: Float,
    val y: Float,
    val payload: String? = null // JSON data for specific configuration of the node
)

@Entity(
    tableName = "connections",
    primaryKeys = ["fromNode", "fromPort", "toNode", "toPort"]
)
data class ConnectionEntity(
    val fromNode: String,
    val fromPort: Int,
    val toNode: String,
    val toPort: Int
)

@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)
