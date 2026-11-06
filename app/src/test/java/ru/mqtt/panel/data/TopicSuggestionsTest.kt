package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Автоподбор имени и типа виджета по содержимому топика */
class TopicSuggestionsTest {

    @Test
    fun `name taken from last segment`() {
        assertEquals("temp", TopicSuggestions.nameFromTopic("home/living/temp"))
        // числовой последний сегмент сам по себе бессмысленен — берём два
        assertEquals("btval/01", TopicSuggestions.nameFromTopic("antonh2o/tuwa3/btval/01"))
    }

    @Test
    fun `numeric payload becomes value widget`() {
        val panel = TopicSuggestions.suggest("home/temp", "21.5")
        assertEquals(WidgetType.VALUE, panel.type)
        assertEquals("temp", panel.name)
        assertEquals("home/temp", panel.topicSubscribe)
    }

    @Test
    fun `zero and one become switch publishing same notation`() {
        val one = TopicSuggestions.suggest("dev/set", "1")
        assertEquals(WidgetType.SWITCH, one.type)
        assertEquals("1", one.onPayload)
        assertEquals("0", one.offPayload)

        val zero = TopicSuggestions.suggest("dev/set", "0")
        assertEquals(WidgetType.SWITCH, zero.type)
        assertEquals("1", zero.onPayload)
        assertEquals("0", zero.offPayload)
    }

    @Test
    fun `boolean payloads become switch with matching notation`() {
        val onPanel = TopicSuggestions.suggest("d/relay", "ON")
        assertEquals(WidgetType.SWITCH, onPanel.type)
        assertEquals("ON", onPanel.onPayload)
        assertEquals("OFF", onPanel.offPayload)

        val offPanel = TopicSuggestions.suggest("d/relay", "off")
        assertEquals("on", offPanel.onPayload)
        assertEquals("off", offPanel.offPayload)
    }

    @Test
    fun `structured or unknown payload stays text`() {
        assertEquals(WidgetType.TEXT, TopicSuggestions.suggest("d/state", "{\"a\":1}").type)
        assertEquals(WidgetType.TEXT, TopicSuggestions.suggest("d/ip", "192.168.1.5").type)
        assertEquals(WidgetType.TEXT, TopicSuggestions.suggest("d/empty", "").type)
    }

    @Test
    fun `depth for sorting`() {
        assertEquals(0, TopicSuggestions.depth("abc"))
        assertEquals(2, TopicSuggestions.depth("a/b/c"))
    }
}
