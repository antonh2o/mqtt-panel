package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Множитель и десятичная точность показа (аналог «Фактор»/«Точность»
 * в IoT MQTT Panel). Нечисловые payload должны проходить без изменений —
 * иначе кнопки (ON/OFF) и текстовые статусы потеряют своё значение.
 */
class ValueFormatTest {

    @Test
    fun noFactorNoPrecision_keepsRaw() {
        assertEquals("22.4567", ValueFormat.apply("22.4567", 1.0, null))
        assertEquals("ON", ValueFormat.apply("ON", 1.0, null))
        assertEquals("—", ValueFormat.apply("—", 1.0, null))
    }

    @Test
    fun factorMultiplies() {
        // датчик отдаёт десятые доли — показываем целые
        assertEquals("23", ValueFormat.apply("2.3", 10.0, null))
        // датчик отдаёт тысячные доли вольта
        assertEquals("220", ValueFormat.apply("220000", 0.001, null))
    }

    @Test
    fun decimalsRounds() {
        assertEquals("22.5", ValueFormat.apply("22.4567", 1.0, 1))
        assertEquals("22.46", ValueFormat.apply("22.4567", 1.0, 2))
        assertEquals("22", ValueFormat.apply("22.4567", 1.0, 0))
    }

    @Test
    fun factorAndDecimalsTogether() {
        assertEquals("23.0", ValueFormat.apply("2.3", 10.0, 1))
        assertEquals("0.40", ValueFormat.apply("0.4", 1.0, 2))
    }

    @Test
    fun nonNumericIgnored() {
        assertEquals("ON", ValueFormat.apply("ON", 10.0, 2))
        assertEquals("true", ValueFormat.apply("true", 10.0, 2))
        assertEquals("", ValueFormat.apply("", 10.0, 2))
    }

    @Test
    fun commaAndSuffixParsed() {
        assertEquals("0.5", ValueFormat.apply("0,45 A", 1.0, 1))
        assertEquals("21.5", ValueFormat.apply("21,50°C", 1.0, 1))
    }

    @Test
    fun zeroFactorTreatedAsOne() {
        // старые конфиги могли хранить 0 в поле множителя — не обнуляем значение
        assertEquals("42", ValueFormat.apply("42", 0.0, null))
    }

    @Test
    fun decimalsClampedToSix() {
        assertEquals("1.234568", ValueFormat.apply("1.23456789", 1.0, 12))
    }

    /**
     * scaled() — числовое значение для расчётов (прогресс, точки графиков):
     * «Фактор» должен двигать не только подпись, но и шкалу с полосой.
     */
    @Test
    fun scaledAppliesFactor() {
        assertEquals(23.0, ValueFormat.scaled("2.3", 10.0)!!, 1e-9)
        assertEquals(0.22, ValueFormat.scaled("220", 0.001)!!, 1e-9)
        // без множителя и с 0 (старые конфиги) — исходное число
        assertEquals(42.0, ValueFormat.scaled("42", 1.0)!!, 1e-9)
        assertEquals(42.0, ValueFormat.scaled("42", 0.0)!!, 1e-9)
    }

    @Test
    fun scaledParsesCommaAndSuffix() {
        assertEquals(0.45, ValueFormat.scaled("0,45 A", 1.0)!!, 1e-9)
        assertEquals(21.5, ValueFormat.scaled("21,50°C", 1.0)!!, 1e-9)
    }

    @Test
    fun scaledNullForNonNumeric() {
        assertEquals(null, ValueFormat.scaled("ON", 10.0))
        assertEquals(null, ValueFormat.scaled("", 10.0))
    }
}
