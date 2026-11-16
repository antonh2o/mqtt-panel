package ru.mqtt.panel.data

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Юнит-тесты extractJsonPath: JSON-payload'ы топиков вида village/meteo
 * (вложенные объекты, массивы, строки, null, битый JSON).
 */
class JsonPathTest {

    private val meteo = """
        {
          "status": "OK",
          "light": {
            "value": {
              "value": 560,
              "raw": 560,
              "time": "14.11.26 15:33"
            },
            "extremums": {
              "daily": {
                "max": { "value": 8890 },
                "min": { "value": 0 }
              }
            }
          },
          "display": "3.1 м/с\nЗ",
          "sensors": [
            { "id": 1, "temperature": { "value": 21.5 } },
            { "id": 2, "temperature": { "value": -4.2 } }
          ],
          "absent": null
        }
    """.trimIndent()

    @Test
    fun `nested number path`() {
        assertEquals("560", extractJsonPath(meteo, "$.light.value.value"))
    }

    @Test
    fun `deeper path`() {
        assertEquals("8890", extractJsonPath(meteo, "$.light.extremums.daily.max.value"))
        assertEquals("0", extractJsonPath(meteo, "$.light.extremums.daily.min.value"))
    }

    @Test
    fun `array index path`() {
        assertEquals("21.5", extractJsonPath(meteo, "$.sensors.0.temperature.value"))
        assertEquals("-4.2", extractJsonPath(meteo, "$.sensors.1.temperature.value"))
    }

    @Test
    fun `string value without quotes`() {
        // строка возвращается БЕЗ кавычек, в т.ч. с переводом строки
        assertEquals("OK", extractJsonPath(meteo, "$.status"))
        assertEquals("3.1 м/с\nЗ", extractJsonPath(meteo, "$.display"))
    }

    @Test
    fun `json null`() {
        assertEquals("null", extractJsonPath(meteo, "$.absent"))
    }

    @Test
    fun `root object or array directly`() {
        // сам payload — объект или массив без «$»-префикса пути
        val obj = JSONObject(JSONTokener("""{"a":{"b":7}}""")).toString()
        assertEquals("7", extractJsonPath(obj, "$.a.b"))
        val arr = """[10, 20.5]"""
        assertEquals("20.5", extractJsonPath(arr, "$.1"))
    }

    @Test
    fun `missing path returns null`() {
        assertNull(extractJsonPath(meteo, "$.nope"))
        assertNull(extractJsonPath(meteo, "$.light.value.nope"))
        assertNull(extractJsonPath(meteo, "$.sensors.99.temperature.value"))
        assertNull(extractJsonPath(meteo, "$.light.value.0")) // число, не массив
    }

    @Test
    fun `invalid inputs return null`() {
        assertNull(extractJsonPath("not json at all", "$.a"))
        assertNull(extractJsonPath(meteo, "light.value.value")) // без $
        assertNull(extractJsonPath(meteo, ""))
        assertNull(extractJsonPath(meteo, "   "))
        assertNull(extractJsonPath(meteo, "$"))
    }

    @Test
    fun `whitespace trimmed`() {
        assertEquals("560", extractJsonPath(meteo, "  $.light.value.value  "))
    }
}
