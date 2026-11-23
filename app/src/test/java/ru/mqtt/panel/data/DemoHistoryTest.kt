package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Синтетическая история демо-графиков: форма кривой и детерминированность */
class DemoHistoryTest {

    private val now = 1_796_500_000_000L

    @Test
    fun `day period - last point is now with base value`() {
        val pts = DemoHistory.points("day", now, 21.5f, 3)
        assertEquals(now, pts.last().t)
        assertEquals(21.5f, pts.last().v, 0.01f)
    }

    @Test
    fun `day period - spans exactly 24 hours`() {
        val pts = DemoHistory.points("day", now, 21.5f, 3)
        assertEquals(now - 24 * 3600_000L, pts.first().t)
        assertTrue(pts.first().t < pts.last().t)
    }

    @Test
    fun `time is strictly increasing`() {
        for (p in listOf("hour", "day", "week", "month", "year")) {
            val pts = DemoHistory.points(p, now, 10f, 5)
            assertTrue(p, pts.zipWithNext().all { (a, b) -> b.t > a.t })
        }
    }

    @Test
    fun `point count within chart cap`() {
        for (p in listOf("hour", "day", "week", "month", "year")) {
            val n = DemoHistory.points(p, now, 10f, 5).size
            assertTrue("$p size=$n", n in 24..500)
        }
    }

    @Test
    fun `same seed gives same curve, different seed differs`() {
        val a = DemoHistory.points("week", now, 21f, 7)
        val a2 = DemoHistory.points("week", now, 21f, 7)
        val b = DemoHistory.points("week", now, 21f, 8)
        assertEquals(a, a2)
        assertTrue(a != b)
    }

    @Test
    fun `values stay near base within reasonable amplitude`() {
        val pts = DemoHistory.points("day", now, 21.5f, 2)
        assertTrue(pts.all { it.v in 19f..24f })
    }

    @Test
    fun `first point also lands on base - seamless both ends`() {
        val pts = DemoHistory.points("day", now, 21.5f, 4)
        assertEquals(21.5f, pts.first().v, 0.01f)
    }

    @Test
    fun `unknown period falls back to day`() {
        val day = DemoHistory.points("day", now, 21f, 1)
        val unknown = DemoHistory.points("сутки", now, 21f, 1)
        assertEquals(day, unknown)
    }
}
