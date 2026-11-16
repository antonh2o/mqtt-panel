package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Юнит-тесты extractJsonLeaves: раскрываем JSON-тему (village/meteo) в
 * скалярные листья с путями для сканера датчиков.
 */
class JsonLeavesTest {

    private val meteo = """
        {
          "status": "OK",
          "light": {
            "value": {
              "value": 560,
              "raw": 560,
              "time": "14.11.26 15:33"
            }
          },
          "sensors": [
            { "id": 1, "temperature": { "value": 21.5 } },
            { "id": 2, "temperature": { "value": -4.2 } }
          ],
          "absent": null,
          "empty": "",
          "flag": true
        }
    """.trimIndent()

    private fun paths(): List<String> = extractJsonLeaves(meteo).map { it.path }

    @Test
    fun `scalar leaves with values`() {
        val leaves = extractJsonLeaves(meteo)
        val byPath = leaves.associate { it.path to it.value }
        assertEquals("560", byPath["$.light.value.value"])
        assertEquals("OK", byPath["$.status"])
        assertEquals("21.5", byPath["$.sensors.0.temperature.value"])
        assertEquals("-4.2", byPath["$.sensors.1.temperature.value"])
        assertEquals("true", byPath["$.flag"])
    }

    @Test
    fun `null and empty string are not leaves`() {
        val p = paths()
        assertTrue("null поле не лист", !p.contains("$.absent"))
        assertTrue("пустая строка не лист", !p.contains("$.empty"))
    }

    @Test
    fun `nested objects and arrays expand recursively`() {
        val p = paths()
        assertTrue(p.contains("$.light.value.raw"))
        assertTrue(p.contains("$.light.value.time"))
        assertTrue(p.contains("$.sensors.0.id"))
        assertTrue(p.contains("$.sensors.1.id"))
    }

    @Test
    fun `non-json returns empty`() {
        assertTrue(extractJsonLeaves("23.5").isEmpty())
        assertTrue(extractJsonLeaves("ON").isEmpty())
        assertTrue(extractJsonLeaves("not json at all").isEmpty())
        assertTrue(extractJsonLeaves("").isEmpty())
    }

    @Test
    fun `empty object and array return empty`() {
        assertTrue(extractJsonLeaves("{}").isEmpty())
        assertTrue(extractJsonLeaves("[]").isEmpty())
        assertTrue(extractJsonLeaves("""{"a":{},"b":[null,null]}""").isEmpty())
    }

    @Test
    fun `maxLeaves limits the list`() {
        val json = (0 until 50).joinToString(",") { "\"k$it\":$it" }
        val leaves = extractJsonLeaves("{$json}", maxLeaves = 7)
        assertEquals(7, leaves.size)
        assertTrue(leaves.all { it.path.startsWith("$.k") })
    }

    @Test
    fun `maxDepth stops recursion`() {
        val json = """{"a":{"b":"deep"}}"""
        // «$.a.b» лежит на глубине 2 — при maxDepth=2 листья глубже корня
        // не достраиваются, при maxDepth=3 — достраиваются
        assertTrue(extractJsonLeaves(json, maxDepth = 2).isEmpty())
        assertEquals(listOf("$.a.b"), extractJsonLeaves(json, maxDepth = 3).map { it.path })
    }

    @Test
    fun `order is stable across calls`() {
        // Android-версия org.json сортирует ключи объекта — порядок не
        // исходный, но детерминированный: повторный скан не перемешивает список
        val json = """{"b":1,"a":{"z":2,"y":3}}"""
        val first = extractJsonLeaves(json).map { it.path }
        val second = extractJsonLeaves(json).map { it.path }
        assertEquals(first, second)
        assertEquals(setOf("$.b", "$.a.z", "$.a.y"), first.toSet())
    }
}
