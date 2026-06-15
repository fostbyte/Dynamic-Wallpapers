package com.wallpaper.changer.ui.expert

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallpaper.changer.automation.YamlParser
import com.wallpaper.changer.data.Album
import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.AutomationRule
import kotlinx.coroutines.launch

@Composable
fun ExpertModeScreen(database: AppDatabase) {
    val scope = rememberCoroutineScope()
    
    var yamlText by remember { mutableStateOf("") }
    var albums by remember { mutableStateOf(emptyList<Album>()) }
    
    var validationError by remember { mutableStateOf<String?>(null) }
    var validationSuccess by remember { mutableStateOf(false) }

    fun refreshYaml() {
        scope.launch {
            albums = database.albumDao().getAll()
            val rules = database.automationRuleDao().getAllRules()
            yamlText = YamlParser.serialize(rules, albums)
            validationError = null
            validationSuccess = true
        }
    }

    LaunchedEffect(Unit) {
        refreshYaml()
    }

    // Trigger validation when text changes
    LaunchedEffect(yamlText) {
        if (yamlText.isBlank()) return@LaunchedEffect
        try {
            YamlParser.parse(yamlText, albums)
            validationError = null
            validationSuccess = true
        } catch (e: Exception) {
            validationError = e.message ?: "Invalid YAML structure"
            validationSuccess = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("YAML Payload Editor", style = MaterialTheme.typography.titleMedium)
                Text("Configure all rules and priorities as text.", style = MaterialTheme.typography.bodySmall)
            }
            
            Button(
                onClick = {
                    if (validationSuccess) {
                        scope.launch {
                            try {
                                val parsedRules = YamlParser.parse(yamlText, albums)
                                database.automationRuleDao().deleteAll()
                                database.automationRuleDao().insertAll(parsedRules)
                                refreshYaml()
                            } catch (e: Exception) {
                                validationError = e.message
                                validationSuccess = false
                            }
                        }
                    }
                },
                enabled = validationSuccess,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF689F38))
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Apply")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Apply Payload")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        var showTemplateHelp by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("YAML Schema Example Template", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { showTemplateHelp = !showTemplateHelp }) {
                        Text(if (showTemplateHelp) "Hide" else "Show")
                    }
                }
                if (showTemplateHelp) {
                    Text(
                        text = """
                            rules:
                              # 1. Simple Time-based Rule
                              - name: "Switch to Morning Playlist"
                                type: "Time"
                                priority: 3
                                time: "08:00"
                                days: "Mon,Tue,Wed,Thu,Fri"
                                action: "SwitchAlbum"
                                album: "Morning Vibes"
                            
                              # 2. Simple Location-based Rule (Geofence)
                              - name: "Home Profile"
                                type: "Location"
                                priority: 4
                                latitude: 37.7749
                                longitude: -122.4194
                                radius: 150.0
                                accuracy: "Precise"
                                action: "SwitchAlbum"
                                album: "Relaxing Home"
                            
                              # 3. Compound Rule: Time AND Location
                              - name: "Work Hours at Location"
                                type: "Time"
                                priority: 5
                                time: "09:00"
                                days: "Mon,Tue,Wed,Thu,Fri"
                                latitude: 37.7833
                                longitude: -122.4167
                                radius: 100.0
                                accuracy: "Loose"
                                logic: "AND"
                                action: "SwitchAlbum"
                                album: "Work Mode"

                              # 4. Interval Schedule Rule
                              - name: "Rotate wallpaper"
                                type: "Interval"
                                priority: 1
                                interval_value: 10
                                interval_unit: "Minutes"
                                random: true
                                action: "NextWallpaper"

                              # 5. Gesture Trigger Rule
                              - name: "Double Tap switches album"
                                type: "Gesture"
                                priority: 2
                                gesture: "DoubleTap"
                                action: "SwitchAlbum"
                                album: "Vacation Photos"
                        """.trimIndent(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Editor
        OutlinedTextField(
            value = yamlText,
            onValueChange = { yamlText = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 16.sp
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (validationSuccess) Color(0xFF689F38) else Color(0xFFE57373)
            ),
            placeholder = { Text("rules:\n  - name: Rule\n    type: Time\n    priority: 1\n    ...") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status banner
        if (validationError != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFEBEE), shape = MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                Text(
                    text = "Lint Error: ${validationError!!}",
                    color = Color(0xFFC62828),
                    fontSize = 12.sp
                )
            }
        } else if (validationSuccess) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE8F5E9), shape = MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                Text(
                    text = "YAML Syntax Valid",
                    color = Color(0xFF2E7D32),
                    fontSize = 12.sp
                )
            }
        }
    }
}
