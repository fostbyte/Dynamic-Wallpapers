package com.wallpaper.changer

import com.wallpaper.changer.automation.YamlParser
import com.wallpaper.changer.data.Album
import com.wallpaper.changer.data.AutomationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YamlParserTest {

    @Test
    fun testYamlSerializationAndParsing() {
        val albums = listOf(
            Album(id = 1, name = "Megan"),
            Album(id = 2, name = "Work")
        )

        val rules = listOf(
            AutomationRule(
                id = 10,
                name = "Work Location",
                type = "Location",
                priority = 5,
                latitude = 37.7749,
                longitude = -122.4194,
                radius = 200f,
                locationAccuracy = "Loose",
                actionType = "SwitchAlbum",
                targetAlbumId = 2
            ),
            AutomationRule(
                id = 11,
                name = "Evening schedule",
                type = "Time",
                priority = 2,
                time = "18:30",
                daysOfWeek = "Mon,Wed,Fri",
                actionType = "NextWallpaper"
            )
        )

        // 1. Serialize
        val yamlString = YamlParser.serialize(rules, albums)
        println("Generated YAML:\n$yamlString")
        
        assertTrue(yamlString.contains("Work Location"))
        assertTrue(yamlString.contains("Evening schedule"))
        assertTrue(yamlString.contains("37.7749"))
        assertTrue(yamlString.contains("Megan").not()) // Megan not active in rules
        assertTrue(yamlString.contains("album: Work"))

        // 2. Parse back
        val parsedRules = YamlParser.parse(yamlString, albums)
        assertEquals(2, parsedRules.size)
        
        val parsedLocationRule = parsedRules[0]
        assertEquals("Work Location", parsedLocationRule.name)
        assertEquals("Location", parsedLocationRule.type)
        assertEquals(5, parsedLocationRule.priority)
        assertEquals(37.7749, parsedLocationRule.latitude ?: 0.0, 0.0001)
        assertEquals("SwitchAlbum", parsedLocationRule.actionType)
        assertEquals(2L, parsedLocationRule.targetAlbumId)

        val parsedTimeRule = parsedRules[1]
        assertEquals("Evening schedule", parsedTimeRule.name)
        assertEquals("Time", parsedTimeRule.type)
        assertEquals(2, parsedTimeRule.priority)
        assertEquals("18:30", parsedTimeRule.time)
        assertEquals("Mon,Wed,Fri", parsedTimeRule.daysOfWeek)
        assertEquals("NextWallpaper", parsedTimeRule.actionType)
    }

    @Test
    fun testCompoundRules() {
        val albums = listOf(
            Album(id = 1, name = "Megan"),
            Album(id = 2, name = "Work")
        )

        val rules = listOf(
            AutomationRule(
                id = 12,
                name = "Compound Rule AND",
                type = "Time",
                priority = 10,
                time = "10:00",
                daysOfWeek = "Tue,Thu",
                latitude = 37.7833,
                longitude = -122.4167,
                radius = 150f,
                locationAccuracy = "Loose",
                connectionLogic = "AND",
                actionType = "SwitchAlbum",
                targetAlbumId = 2
            )
        )

        // Serialize
        val yamlString = YamlParser.serialize(rules, albums)
        assertTrue(yamlString.contains("logic: AND"))

        // Parse
        val parsedRules = YamlParser.parse(yamlString, albums)
        assertEquals(1, parsedRules.size)
        val parsedRule = parsedRules[0]
        assertEquals("Compound Rule AND", parsedRule.name)
        assertEquals("AND", parsedRule.connectionLogic)
        assertEquals("10:00", parsedRule.time)
        assertEquals(37.7833, parsedRule.latitude ?: 0.0, 0.0001)
    }
}
