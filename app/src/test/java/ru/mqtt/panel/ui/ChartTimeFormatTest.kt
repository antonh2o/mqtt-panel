package ru.mqtt.panel.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartTimeFormatTest {

    private val H = 3600_000L
    private val M = 60_000L

    @Test
    fun shortSpanShowsSeconds() {
        assertEquals("HH:mm:ss", ChartTimeFormat.pattern(2 * M))
        assertEquals("HH:mm:ss", ChartTimeFormat.pattern(0L))
    }

    @Test
    fun daySpanShowsHoursMinutes() {
        assertEquals("HH:mm", ChartTimeFormat.pattern(5 * M))
        assertEquals("HH:mm", ChartTimeFormat.pattern(1 * H))
        assertEquals("HH:mm", ChartTimeFormat.pattern(23 * H))
    }

    @Test
    fun dayBoundaryIsInclusive() {
        assertEquals("HH:mm", ChartTimeFormat.pattern(24 * H))
        assertEquals("dd.MM", ChartTimeFormat.pattern(24 * H + 1))
    }

    @Test
    fun weekSpanShowsDates() {
        assertEquals("dd.MM", ChartTimeFormat.pattern(7 * 24 * H))
        assertEquals("dd.MM", ChartTimeFormat.pattern(30L * 24 * H))
        assertEquals("dd.MM", ChartTimeFormat.pattern(365L * 24 * H))
    }

    @Test
    fun labelMatchesPattern() {
        // фиксированный момент: 15.06.2026 14:30 UTC — формат не зависит от зоны для шаблона
        val t = java.util.Calendar.getInstance().apply {
            set(2026, 5, 15, 14, 30, 0)
        }.timeInMillis
        val expected = java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault())
            .format(java.util.Date(t))
        assertEquals(expected, ChartTimeFormat.label(7L * 24 * H, t))
    }
}
