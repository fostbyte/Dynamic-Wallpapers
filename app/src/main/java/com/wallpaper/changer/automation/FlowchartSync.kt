package com.wallpaper.changer.automation

import com.wallpaper.changer.data.AppDatabase
import com.wallpaper.changer.data.NodeEntity
import com.wallpaper.changer.data.ConnectionEntity
import org.yaml.snakeyaml.Yaml

object FlowchartSync {
    suspend fun sync(database: AppDatabase) {
        val rules = database.automationRuleDao().getAllRules().sortedByDescending { it.priority }
        val nodeEntities = mutableListOf<NodeEntity>()
        val connEntities = mutableListOf<ConnectionEntity>()
        val yaml = Yaml()

        rules.forEachIndexed { i, rule ->
            val y = i * 250f
            
            // 1. Create Action Node
            val actionId = "action_${rule.id}"
            val actionTitle = when (rule.actionType) {
                "NextWallpaper", "PrevWallpaper" -> "Change Wallpaper"
                "SwitchAlbum" -> "Switch Album"
                "RotateFavorites" -> "Rotate Favorites"
                "RandomOrder" -> "Random Order"
                else -> "Change Wallpaper"
            }
            val actionTypeStr = when (rule.actionType) {
                "PrevWallpaper" -> "NextWallpaper"
                else -> rule.actionType
            }
            val actionPayload = mapOf(
                "albumId" to (rule.targetAlbumId ?: 0L),
                "albumIds" to (rule.targetAlbumIds ?: ""),
                "wallpaperDirection" to (if (rule.actionType == "PrevWallpaper") "Previous" else "Next"),
                "dimmingPercent" to (rule.dimmingPercent ?: 0),
                "blurPercent" to (rule.blurPercent ?: 0),
                "greyscalePercent" to (rule.greyscalePercent ?: 0)
            )
            nodeEntities.add(
                NodeEntity(
                    id = actionId,
                    title = actionTitle,
                    type = actionTypeStr,
                    x = 600f,
                    y = y,
                    payload = yaml.dump(actionPayload)
                )
            )

            // 2. Create Trigger and Logic nodes
            if (rule.connectionLogic == "NONE" || rule.connectionLogic == "NOT") {
                val triggerId = "trigger_${rule.id}_1"
                val triggerTitle = when (rule.type) {
                    "Time" -> "Time Trigger"
                    "Location" -> "Location Trigger"
                    "Interval" -> "Interval Trigger"
                    "Gesture" -> "Gesture Trigger"
                    "Unlock" -> "Unlock Trigger"
                    "HomeScreen" -> "Home Screen Trigger"
                    "WiFi" -> "WiFi Trigger"
                    else -> "Time Trigger"
                }
                val triggerPayload = mapOf(
                    "time" to (rule.time ?: "12:00"),
                    "days" to (rule.daysOfWeek ?: "Mon,Tue,Wed,Thu,Fri"),
                    "lat" to (rule.latitude?.toString() ?: "37.7749"),
                    "lng" to (rule.longitude?.toString() ?: "-122.4194"),
                    "radius" to (rule.radius?.toString() ?: "100.0"),
                    "intervalValue" to (rule.intervalValue ?: 10),
                    "intervalUnit" to (rule.intervalUnit ?: "Minutes"),
                    "gestureType" to (rule.gestureType ?: "DoubleTap"),
                    "timeCondition" to (rule.timeCondition ?: "At"),
                    "locationCondition" to (rule.locationCondition ?: "Entering"),
                    "randomOrder" to rule.randomOrder,
                    "wifiState" to (rule.wifiState ?: "Connecting"),
                    "wifiSsid" to (rule.wifiSsid ?: "")
                )
                nodeEntities.add(
                    NodeEntity(
                        id = triggerId,
                        title = triggerTitle,
                        type = rule.type,
                        x = 100f,
                        y = y,
                        payload = yaml.dump(triggerPayload)
                    )
                )

                if (rule.connectionLogic == "NOT") {
                    val notId = "not_${rule.id}"
                    nodeEntities.add(
                        NodeEntity(
                            id = notId,
                            title = "NOT Gate",
                            type = "Not",
                            x = 350f,
                            y = y
                        )
                    )
                    connEntities.add(ConnectionEntity(triggerId, 0, notId, 0))
                    connEntities.add(ConnectionEntity(notId, 0, actionId, 0))
                } else {
                    connEntities.add(ConnectionEntity(triggerId, 0, actionId, 0))
                }
            } else {
                // connectionLogic is AND, OR, XOR, AND_NOT, OR_NOT
                // 2a. Time Trigger
                val timeId = "time_${rule.id}"
                val timePayload = mapOf(
                    "time" to (rule.time ?: "12:00"),
                    "days" to (rule.daysOfWeek ?: "Mon,Tue,Wed,Thu,Fri"),
                    "timeCondition" to (rule.timeCondition ?: "At"),
                    "randomOrder" to rule.randomOrder
                )
                nodeEntities.add(
                    NodeEntity(
                        id = timeId,
                        title = "Time Trigger",
                        type = "Time",
                        x = 50f,
                        y = y - 50f,
                        payload = yaml.dump(timePayload)
                    )
                )

                // 2b. Location Trigger
                val locId = "location_${rule.id}"
                val locPayload = mapOf(
                    "lat" to (rule.latitude?.toString() ?: "37.7749"),
                    "lng" to (rule.longitude?.toString() ?: "-122.4194"),
                    "radius" to (rule.radius?.toString() ?: "100.0"),
                    "locationCondition" to (rule.locationCondition ?: "Entering"),
                    "randomOrder" to rule.randomOrder
                )
                nodeEntities.add(
                    NodeEntity(
                        id = locId,
                        title = "Location Trigger",
                        type = "Location",
                        x = 50f,
                        y = y + 50f,
                        payload = yaml.dump(locPayload)
                    )
                )

                // 2c. Gate Node
                val gateId = "gate_${rule.id}"
                val gateType = when {
                    rule.connectionLogic.startsWith("AND") -> "And"
                    rule.connectionLogic.startsWith("OR") -> "Or"
                    else -> "Xor"
                }
                val gateTitle = "$gateType Gate"
                nodeEntities.add(
                    NodeEntity(
                        id = gateId,
                        title = gateTitle,
                        type = gateType,
                        x = 350f,
                        y = y
                    )
                )

                // Connections
                connEntities.add(ConnectionEntity(timeId, 0, gateId, 0))

                if (rule.connectionLogic.endsWith("NOT")) {
                    val notLocId = "not_loc_${rule.id}"
                    nodeEntities.add(
                        NodeEntity(
                            id = notLocId,
                            title = "NOT Gate",
                            type = "Not",
                            x = 220f,
                            y = y + 50f
                        )
                    )
                    connEntities.add(ConnectionEntity(locId, 0, notLocId, 0))
                    connEntities.add(ConnectionEntity(notLocId, 0, gateId, 1))
                } else {
                    connEntities.add(ConnectionEntity(locId, 0, gateId, 1))
                }

                connEntities.add(ConnectionEntity(gateId, 0, actionId, 0))
            }
        }

        database.nodeGraphDao().saveGraph(nodeEntities, connEntities)
    }
}
