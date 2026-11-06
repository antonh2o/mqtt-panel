package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка разбора MQTT-фильтров — на них держится и определение
 * «тема уже покрыта виджетом» в сканере, и матчинг входящих сообщений.
 */
class MqttTopicFilterTest {

    @Test
    fun `exact match`() {
        assertTrue(MqttTopicFilter.matches("a/b/c", "a/b/c"))
        assertFalse(MqttTopicFilter.matches("a/b", "a/b/c"))
    }

    @Test
    fun `single level wildcard`() {
        assertTrue(MqttTopicFilter.matches("a/+/c", "a/b/c"))
        assertTrue(MqttTopicFilter.matches("a/+/c", "a/x/c"))
        assertFalse(MqttTopicFilter.matches("a/+/c", "a/b/d"))
        assertFalse(MqttTopicFilter.matches("a/+", "a/b/c"))
    }

    @Test
    fun `multi level wildcard`() {
        assertTrue(MqttTopicFilter.matches("#", "a"))
        assertTrue(MqttTopicFilter.matches("#", "a/b/c"))
        assertTrue(MqttTopicFilter.matches("a/#", "a/b"))
        assertTrue(MqttTopicFilter.matches("a/#", "a/b/c/d"))
        assertFalse(MqttTopicFilter.matches("a/#", "b/c"))
    }

    @Test
    fun `dollar topics require explicit filter`() {
        assertFalse(MqttTopicFilter.matches("#", "\$SYS/broker/clients"))
        assertTrue(MqttTopicFilter.matches("\$SYS/#", "\$SYS/broker/clients"))
    }

    @Test
    fun `invalid filters rejected`() {
        assertTrue(MqttTopicFilter.isValidFilter("a/+/b"))
        assertTrue(MqttTopicFilter.isValidFilter("a/#"))
        assertFalse(MqttTopicFilter.isValidFilter("a/#/b"))
        assertFalse(MqttTopicFilter.isValidFilter("a/b+"))
        assertFalse(MqttTopicFilter.isValidFilter(""))
    }

    @Test
    fun `coveredByAny`() {
        val filters = listOf("home/bed/#", "sensor/+/temp")
        assertTrue(MqttTopicFilter.coveredByAny(filters, "home/bed/light"))
        assertTrue(MqttTopicFilter.coveredByAny(filters, "sensor/kitchen/temp"))
        assertFalse(MqttTopicFilter.coveredByAny(filters, "garage/door"))
    }
}
