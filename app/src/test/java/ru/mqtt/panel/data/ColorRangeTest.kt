package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Динамический цвет значения по диапазону: трети, интерполяция, мусор в hex */
class ColorRangeTest {

    private val cMin = "#0000FF" // синий
    private val cMid = "#00FF00" // зелёный
    private val cMax = "#FF0000" // красный

    @Test
    fun `минимум диапазона - цвет минимума`() {
        assertEquals(0xFF0000FF.toInt(), ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 170.0))
    }

    @Test
    fun `максимум диапазона - цвет максимума`() {
        assertEquals(0xFFFF0000.toInt(), ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 250.0))
    }

    @Test
    fun `выход за диапазон - прижимается к краевым цветам`() {
        assertEquals(0xFF0000FF.toInt(), ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 100.0))
        assertEquals(0xFFFF0000.toInt(), ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 500.0))
    }

    @Test
    fun `середина диапазона - средний цвет`() {
        assertEquals(0xFF00FF00.toInt(), ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 210.0))
    }

    @Test
    fun `промежуточное значение - промежуточный цвет`() {
        // 190 = первая половина трети мин→средний: примерно наполовину синий+зелёный
        val half = ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 190.0)!!
        val r = half shr 16 and 0xFF
        val g = half shr 8 and 0xFF
        assertEquals("красного ещё нет в первой половине", 0, r)
        assertTrue("зелёный канал между 0 и 255", g in 1..254)
    }

    @Test
    fun `шестизначный hex парсится с альфой FF`() {
        assertEquals(0xFF4CAF50.toInt(), ColorRange.parse("#4CAF50"))
        assertEquals(0xFF4CAF50.toInt(), ColorRange.parse("4CAF50"))
    }

    @Test
    fun `короткий hex расширяется`() {
        val c = ColorRange.parse("#F0A")!!
        assertEquals(0xFF, c shr 24 and 0xFF)
        assertEquals(0xFF, c shr 16 and 0xFF) // F -> FF
        assertEquals(0x00, c shr 8 and 0xFF)  // 0 -> 00
        assertEquals(0xAA, c and 0xFF)        // A -> AA
    }

    @Test
    fun `мусор и пустые цвета - null`() {
        assertNull(ColorRange.parse("#GGHHHH"))
        assertNull(ColorRange.parse(""))
        assertNull(ColorRange.resolve("", cMid, cMax, 0.0, 100.0, 50.0))
        assertNull(ColorRange.resolve(cMin, "", cMax, 0.0, 100.0, 50.0))
    }

    @Test
    fun `вырожденный диапазон - null`() {
        assertNull(ColorRange.resolve(cMin, cMid, cMax, 100.0, 100.0, 100.0))
    }

    @Test
    fun `цвета не совпадают у соседних значений (плавный переход)`() {
        val a = ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 185.0)
        val b = ColorRange.resolve(cMin, cMid, cMax, 170.0, 250.0, 190.0)
        assertNotEquals(a, b)
    }
}
