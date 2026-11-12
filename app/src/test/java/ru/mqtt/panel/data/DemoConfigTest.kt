package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Демо-конфигурация первого запуска (broker.emqx.io): структура конфига,
 * префиксы тем и генератор показаний.
 */
class DemoConfigTest {

    @Test
    fun `build - connection and panels structure`() {
        val config = DemoConfig.build("abcd1234")
        assertEquals(1, config.connections.size)
        val conn = config.connections[0]
        assertTrue(conn.demo)
        assertTrue(!conn.narodmon)
        assertEquals(DemoConfig.DEMO_HOST, conn.host)
        assertTrue(config.panels.size >= 6)
        // все панели принадлежат одному подключению (fixIds переприсвоит id)
        assertTrue(config.panels.all { it.connectionId == 1L })
    }

    @Test
    fun `build - темы панелей лежат под префиксом демо`() {
        val token = "abcd1234"
        val prefix = DemoConfig.prefix(token)
        val config = DemoConfig.build(token)
        val topics = config.panels.flatMap { p ->
            if (p.type == WidgetType.MULTI_CHART) p.series.map { it.topic }
            else listOf(p.topicSubscribe)
        }
        assertTrue(topics.isNotEmpty())
        assertTrue(topics.all { it.startsWith("$prefix/") })
        // у свитча publish = subscribe
        val sw = config.panels.first { it.type == WidgetType.SWITCH }
        assertEquals(sw.topicSubscribe, sw.topicPublish)
    }

    @Test
    fun `prefixOfTopic - распознаёт демо-префикс`() {
        assertEquals(
            "mqttpanel/dabcd1234",
            DemoConfig.prefixOfTopic("mqttpanel/dabcd1234/home/temperature")
        )
        assertEquals(null, DemoConfig.prefixOfTopic("home/temperature"))
        assertEquals(null, DemoConfig.prefixOfTopic("mqttpanel/home/temperature"))
        assertEquals(null, DemoConfig.prefixOfTopic("mqttpanel/dabc/home/temperature"))
    }

    @Test
    fun `sensors - значения в границах и парсятся`() {
        val sensors = DemoConfig.DemoSensors(Random(42))
        var prev: Map<String, String> = emptyMap()
        repeat(500) { _ ->
            val tick = sensors.tick().toMap()
            val t = tick.getValue(DemoConfig.S_HOME_T).toDouble()
            val h = tick.getValue(DemoConfig.S_HOME_H).toDouble()
            val co2 = tick.getValue(DemoConfig.S_CO2).toDouble()
            val street = tick.getValue(DemoConfig.S_STREET_T).toDouble()
            assertTrue("homeT=$t", t in 19.0..24.5)
            assertTrue("homeH=$h", h in 34.0..61.0)
            assertTrue("co2=$co2", co2 in 449.0..1001.0)
            assertTrue("street=$street", street in -3.5..9.5)
            assertEquals("1", tick.getValue(DemoConfig.S_ONLINE))
            // числа без запятой-разделителя и суффиксов — ValueParse их съест
            prev = tick
        }
        assertNotNull(prev)
    }

    @Test
    fun `sensors - показания меняются между тиками`() {
        val sensors = DemoConfig.DemoSensors(Random(7))
        val first = sensors.tick().toMap()
        repeat(10) { sensors.tick() }
        val later = sensors.tick().toMap()
        assertTrue(first.getValue(DemoConfig.S_CO2) != later.getValue(DemoConfig.S_CO2))
    }
}
