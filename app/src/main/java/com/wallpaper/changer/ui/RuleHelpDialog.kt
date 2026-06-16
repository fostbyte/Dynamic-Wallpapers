package com.wallpaper.changer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RuleHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rules & Attributes Help") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("TRIGGERS (When to activate)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                item {
                    Text("• Time Trigger:\n  - hh:mm (e.g. 08:00)\n  - days: Comma-separated list of Mon, Tue, Wed, Thu, Fri, Sat, Sun\n  - YAML fields: time, days", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• Location Trigger:\n  - latitude, longitude (degrees)\n  - radius: Radius in meters\n  - condition: Entering / Leaving\n  - YAML fields: latitude, longitude, radius, location_condition", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• WiFi Trigger:\n  - wifi_state: Connecting / Disconnecting\n  - wifi_ssid: Optional SSID name\n  - YAML fields: type: \"WiFi\", wifi_state, wifi_ssid", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• Interval Trigger:\n  - interval_value: number (e.g. 10)\n  - interval_unit: Seconds, Minutes, Hours, Days\n  - random: true/false (random vs sequential order)\n  - YAML fields: interval_value, interval_unit, random", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• Gesture Trigger:\n  - gesture: DoubleTap, TwoFingerDoubleTap, ThreeFingerDoubleTap\n  - YAML fields: gesture", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• Unlock Trigger:\n  - Triggers when device screen is unlocked.\n  - YAML field: type: \"Unlock\"", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• Home Screen Trigger:\n  - Triggers when navigating to the home screen.\n  - YAML field: type: \"HomeScreen\"", style = MaterialTheme.typography.bodyMedium)
                }
                
                item {
                    Text("LOGIC GATES (Combining Triggers)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                item {
                    Text("• AND / OR / XOR / NOT:\n  - Combine multiple triggers logically.\n  - YAML fields: logic: \"AND\" / \"OR\" / \"XOR\" / \"NOT\"", style = MaterialTheme.typography.bodyMedium)
                }
                
                item {
                    Text("ACTIONS (What to do)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                item {
                    Text("• NextWallpaper:\n  - Rotates to the next wallpaper in the active album(s).\n  - YAML field: action: \"NextWallpaper\"", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• SwitchAlbum:\n  - Changes the active album to the specified album.\n  - YAML fields: action: \"SwitchAlbum\", album: \"[Album Name]\"", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Text("• RotateFavorites:\n  - Rotates to a favorited photo across all albums.\n  - YAML fields: action: \"RotateFavorites\"", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}
