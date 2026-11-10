package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор «сырых» значений топиков: запятая как разделитель, суффиксы
 * единиц, префиксы — раньше строгий toFloatOrNull() молча давал null и
 * прогресс/динамический цвет оставались пустыми.
 */
class ValueParseTest {

    @Test
    fun plain() {
        assertEquals(0.4, parseNumber("0.4")!!, 1e-9)
        assertEquals(21.5, parseNumber("21.5")!!, 1e-9)
        assertEquals(-3.0, parseNumber("-3")!!, 1e-9)
    }

    @Test
    fun commaDecimal() {
        assertEquals(0.4, parseNumber("0,4")!!, 1e-9)
        assertEquals(-12.75, parseNumber("-12,75")!!, 1e-9)
    }

    @Test
    fun withSuffixAndPrefix() {
        assertEquals(5.2, parseNumber("5.2 A")!!, 1e-9)
        assertEquals(21.5, parseNumber("+21.5°C")!!, 1e-9)
        assertEquals(230.0, parseNumber("U=230V")!!, 1e-9)
        assertEquals(0.36, parseNumber("current: 0.36")!!, 1e-9)
    }

    @Test
    fun whitespaceAndBlank() {
        assertEquals(7.0, parseNumber(" 7 ")!!, 1e-9)
        assertNull(parseNumber(""))
        assertNull(parseNumber("   "))
    }

    @Test
    fun noNumber() {
        assertNull(parseNumber("online"))
        assertNull(parseNumber("true"))
        assertNull(parseNumber(null))
    }

    @Test
    fun firstNumberWins() {
        // первое число в строке — ожидаемое поведение для «сырых» payload
        assertEquals(230.0, parseNumber("{\"v\":230,\"i\":3.2}")!!, 1e-9)
    }

    @Test
    fun scientificNotation() {
        assertEquals(1.5e-2, parseNumber("1.5e-2")!!, 1e-12)
    }
}
