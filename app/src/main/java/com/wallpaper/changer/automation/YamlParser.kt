package com.wallpaper.changer.automation

import com.wallpaper.changer.data.AutomationRule
import com.wallpaper.changer.data.Album
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions
import java.io.StringWriter

object YamlParser {

    @Suppress("UNCHECKED_CAST")
    fun parse(yamlString: String, albums: List<Album>): List<AutomationRule> {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(yamlString) ?: throw Exception("Empty YAML document")
        val rulesList = data["rules"] as? List<Map<String, Any>> ?: throw Exception("Root element must contain a 'rules' list")
        
        return rulesList.mapIndexed { index, map ->
            val name = map["name"] as? String ?: "Rule #${index + 1}"
            val type = map["type"] as? String ?: throw Exception("Rule '$name' is missing field 'type'")
            if (type != "Time" && type != "Location" && type != "Interval" && type != "Gesture" && type != "Unlock" && type != "HomeScreen" && type != "WiFi") {
                throw Exception("Rule '$name' has invalid type '$type'. Must be 'Time', 'Location', 'Interval', 'Gesture', 'Unlock', 'HomeScreen' or 'WiFi'")
            }
            val priority = (map["priority"] as? Number)?.toInt() ?: 0
            
            val daysOfWeek = map["days"] as? String
            val time = map["time"] as? String
            val timeCondition = map["time_condition"] as? String ?: "At"
            val locationCondition = map["location_condition"] as? String ?: "Entering"
            val wifiState = map["wifi_state"] as? String ?: "Connecting"
            val wifiSsid = map["wifi_ssid"] as? String
            
            val logic = (map["logic"] as? String ?: "NONE").uppercase()
            if (logic != "NONE" && logic != "AND" && logic != "OR" && logic != "XOR" && logic != "AND_NOT" && logic != "OR_NOT" && logic != "NOT") {
                throw Exception("Rule '$name' has invalid logic '$logic'. Must be 'NONE', 'AND', 'OR', 'XOR', 'AND_NOT', 'OR_NOT', or 'NOT'")
            }

            if (type == "Time" && time == null) {
                throw Exception("Time rule '$name' must specify a 'time' (e.g. '17:35')")
            }
            
            val latitude = (map["latitude"] as? Number)?.toDouble()
            val longitude = (map["longitude"] as? Number)?.toDouble()
            val radius = (map["radius"] as? Number)?.toFloat()
            val locationAccuracy = map["accuracy"] as? String ?: "Loose"
            
            if (type == "Location" && (latitude == null || longitude == null || radius == null)) {
                throw Exception("Location rule '$name' must specify 'latitude', 'longitude', and 'radius'")
            }

            if (logic != "NONE" && logic != "NOT") {
                if (time == null) {
                    throw Exception("Compound rule '$name' has logic '$logic' but is missing 'time'")
                }
                if (latitude == null || longitude == null || radius == null) {
                    throw Exception("Compound rule '$name' has logic '$logic' but is missing location fields 'latitude', 'longitude', or 'radius'")
                }
            }

            val intervalValue = (map["interval_value"] as? Number)?.toInt()
            val intervalUnit = map["interval_unit"] as? String
            val randomOrder = map["random"] as? Boolean ?: false

            if (type == "Interval" && (intervalValue == null || intervalUnit == null)) {
                throw Exception("Interval rule '$name' must specify 'interval_value' and 'interval_unit'")
            }
            if (type == "Interval" && intervalUnit != "Seconds" && intervalUnit != "Minutes" && intervalUnit != "Hours" && intervalUnit != "Days") {
                throw Exception("Interval rule '$name' has invalid interval_unit '$intervalUnit'. Must be 'Seconds', 'Minutes', 'Hours' or 'Days'")
            }

            if (type == "WiFi" && wifiState != "Connecting" && wifiState != "Disconnecting") {
                throw Exception("WiFi rule '$name' has invalid wifi_state '$wifiState'. Must be 'Connecting' or 'Disconnecting'")
            }

            val gestureType = map["gesture"] as? String
            val playFavoritesOnly = map["favorites_only"] as? Boolean ?: false
            
            val dimmingPercent = (map["dimming"] as? Number)?.toInt()
            val blurPercent = (map["blur"] as? Number)?.toInt()
            val greyscalePercent = (map["greyscale"] as? Number)?.toInt()

            if (type == "Gesture" && gestureType == null) {
                throw Exception("Gesture rule '$name' must specify a 'gesture' (e.g. 'DoubleTap')")
            }
            if (type == "Gesture" && gestureType != "DoubleTap" && gestureType != "TwoFingerDoubleTap" && gestureType != "ThreeFingerDoubleTap") {
                throw Exception("Gesture rule '$name' has invalid gesture '$gestureType'. Must be 'DoubleTap', 'TwoFingerDoubleTap' or 'ThreeFingerDoubleTap'")
            }
            
            val actionType = map["action"] as? String ?: throw Exception("Rule '$name' is missing field 'action'")
            if (actionType != "NextWallpaper" && actionType != "PrevWallpaper" && actionType != "RandomOrder" && actionType != "SwitchAlbum" && actionType != "RotateFavorites") {
                throw Exception("Rule '$name' has invalid action '$actionType'. Must be 'NextWallpaper', 'PrevWallpaper', 'RandomOrder', 'SwitchAlbum' or 'RotateFavorites'")
            }
            
            val targetAlbumName = map["album"] as? String
            val targetAlbumNamesList = map["albums"] as? List<String>
            val targetAlbumNamesStr = map["albums"] as? String
            
            var targetAlbumId: Long? = null
            var targetAlbumIds: String? = null
            
            if (actionType == "SwitchAlbum") {
                val names = mutableListOf<String>()
                if (targetAlbumName != null) {
                    names.add(targetAlbumName)
                }
                if (targetAlbumNamesList != null) {
                    names.addAll(targetAlbumNamesList)
                }
                if (targetAlbumNamesStr != null) {
                    names.addAll(targetAlbumNamesStr.split(",").map { it.trim() })
                }
                
                if (names.isEmpty()) {
                    throw Exception("SwitchAlbum rule '$name' must specify target 'album' or 'albums'")
                }
                
                val matchedIds = names.map { n ->
                    val matchedAlbum = albums.find { it.name.equals(n, ignoreCase = true) }
                        ?: throw Exception("SwitchAlbum rule '$name' references unknown album '$n'")
                    matchedAlbum.id
                }
                
                targetAlbumId = matchedIds.firstOrNull()
                targetAlbumIds = matchedIds.joinToString(",")
            }
            
            AutomationRule(
                name = name,
                type = type,
                priority = priority,
                daysOfWeek = daysOfWeek,
                time = time,
                latitude = latitude,
                longitude = longitude,
                radius = radius,
                locationAccuracy = locationAccuracy,
                connectionLogic = logic,
                actionType = actionType,
                targetAlbumId = targetAlbumId,
                targetAlbumIds = targetAlbumIds,
                intervalValue = intervalValue,
                intervalUnit = intervalUnit,
                randomOrder = randomOrder,
                gestureType = gestureType,
                timeCondition = timeCondition,
                locationCondition = locationCondition,
                dimmingPercent = dimmingPercent,
                blurPercent = blurPercent,
                greyscalePercent = greyscalePercent,
                wifiState = if (type == "WiFi") wifiState else null,
                wifiSsid = if (type == "WiFi") wifiSsid else null,
                playFavoritesOnly = playFavoritesOnly
            )
        }
    }

    fun serialize(rules: List<AutomationRule>, albums: List<Album>): String {
        val rulesMapList = rules.map { rule ->
            val map = LinkedHashMap<String, Any>()
            map["name"] = rule.name
            map["type"] = rule.type
            map["priority"] = rule.priority
            
            rule.daysOfWeek?.let { map["days"] = it }
            rule.time?.let { map["time"] = it }
            rule.timeCondition?.let { map["time_condition"] = it }
            rule.latitude?.let { map["latitude"] = it }
            rule.longitude?.let { map["longitude"] = it }
            rule.radius?.let { map["radius"] = it }
            rule.locationAccuracy?.let { map["accuracy"] = it }
            rule.locationCondition?.let { map["location_condition"] = it }
            
            if (rule.connectionLogic != "NONE") {
                map["logic"] = rule.connectionLogic
            }

            rule.intervalValue?.let { map["interval_value"] = it }
            rule.intervalUnit?.let { map["interval_unit"] = it }
            map["random"] = rule.randomOrder
            rule.gestureType?.let { map["gesture"] = it }
            rule.wifiState?.let { map["wifi_state"] = it }
            rule.wifiSsid?.let { map["wifi_ssid"] = it }
            
            rule.dimmingPercent?.let { map["dimming"] = it }
            rule.blurPercent?.let { map["blur"] = it }
            rule.greyscalePercent?.let { map["greyscale"] = it }
            if (rule.playFavoritesOnly) {
                map["favorites_only"] = true
            }
            
            map["action"] = rule.actionType
            if (rule.actionType == "SwitchAlbum") {
                val ids = rule.targetAlbumIds?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
                val finalIds = if (ids.isNotEmpty()) ids else if (rule.targetAlbumId != null) listOf(rule.targetAlbumId) else emptyList()
                
                if (finalIds.size == 1) {
                    val album = albums.find { it.id == finalIds.first() }
                    map["album"] = album?.name ?: "Unknown Album (ID: ${finalIds.first()})"
                } else if (finalIds.size > 1) {
                    val albumNames = finalIds.map { id ->
                        albums.find { it.id == id }?.name ?: "Unknown Album (ID: $id)"
                    }
                    map["albums"] = albumNames
                }
            }
            map
        }
        
        val root = mapOf("rules" to rulesMapList)
        
        val options = DumperOptions()
        options.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        options.isPrettyFlow = true
        
        val yaml = Yaml(options)
        val writer = StringWriter()
        yaml.dump(root, writer)
        return writer.toString()
    }
}
