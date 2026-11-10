package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrafanaApiTest {

    private val H = 3600_000L

    @Test
    fun queryHasMeanFromWhereGroupBy() {
        val q = GrafanaApi.influxQuery(
            "mqtt_consumer", "value", "topic", "antonh2o/tuwa3/mb0101",
            1000L, 2000L, 500
        )
        assertTrue(q.contains("SELECT mean(\"value\") AS \"v\" FROM \"mqtt_consumer\""))
        assertTrue(q.contains("\"topic\" = 'antonh2o/tuwa3/mb0101'"))
        assertTrue(q.contains("time > 1000ms AND time <= 2000ms"))
        assertTrue(q.contains("GROUP BY time("))
        assertTrue(q.contains("fill(previous)"))
    }

    @Test
    fun queryWithoutTag() {
        val q = GrafanaApi.influxQuery("m", "f", "", "", 0L, 600_000L, 60)
        assertTrue(!q.contains(" AND \""))
    }

    @Test
    fun bucketScalesWithSpan() {
        // сутки на 500 точек — шаг ~2.9 мин → 5m
        assertEquals("5m", GrafanaApi.bucketFor(24 * H, 500))
        // неделя на 500 точек — шаг ~20 мин → 30m
        assertEquals("30m", GrafanaApi.bucketFor(7L * 24 * H, 500))
        // час на 500 точек — шаг 7.2 с → 10s
        assertEquals("10s", GrafanaApi.bucketFor(1 * H, 500))
        // год на 500 точек — шаг ~17.5 ч → 1d
        assertEquals("1d", GrafanaApi.bucketFor(365L * 24 * H, 500))
    }

    @Test
    fun parseFramesMsFloats() {
        val json = """{"results":{"A":{"frames":[{"schema":{"name":"m"},
            "data":{"values":[[1699999999999.0,1700000059999.0],[21.5,null]]}}]}}}"""
        val pts = GrafanaApi.parseFrames(json)
        assertEquals(1, pts.size)
        assertEquals(1699999999999L, pts[0].t)
        assertEquals(21.5f, pts[0].v)
    }

    @Test
    fun parseFramesSecondsConverted() {
        val json = """{"results":{"A":{"frames":[{"data":{"values":[[100.5,101.0],[7.0,8.0]]}}]}}}"""
        val pts = GrafanaApi.parseFrames(json)
        assertEquals(2, pts.size)
        assertEquals(100500L, pts[0].t)
        assertEquals(101000L, pts[1].t)
    }

    @Test
    fun parseFramesSkipsNullsAndSorts() {
        val json = """{"results":{"A":{"frames":[{"data":{"values":[
            [1700003000000.0,1700002000000.0,1700001000000.0],[1.0,null,3.0]]}}]}}}"""
        val pts = GrafanaApi.parseFrames(json)
        assertEquals(listOf(1700001000000L, 1700003000000L), pts.map { it.t })
        assertEquals(listOf(3.0f, 1.0f), pts.map { it.v })
    }

    @Test
    fun parseFramesGarbageIsEmpty() {
        assertEquals(emptyList<NarodmonApi.NmPoint>(), GrafanaApi.parseFrames("not json"))
        assertEquals(emptyList<NarodmonApi.NmPoint>(), GrafanaApi.parseFrames("{}"))
    }

    @Test
    fun periodSpanDefaults() {
        assertEquals(24L * H, GrafanaApi.periodSpan("day"))
        assertEquals(7L * 24 * H, GrafanaApi.periodSpan("week"))
        assertEquals(30L * 24 * H, GrafanaApi.periodSpan("month"))
        assertEquals(365L * 24 * H, GrafanaApi.periodSpan("year"))
        assertEquals(24L * H, GrafanaApi.periodSpan("мусор"))
    }
}
