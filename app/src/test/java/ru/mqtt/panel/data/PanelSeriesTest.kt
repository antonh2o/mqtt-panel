package ru.mqtt.panel.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Серии мультиграфика: Gson-кругооборот. Enum сериализуется по имени,
 * series — массив {topic, color, label}; конфиги до 0.2.9 поля не содержат.
 */
class PanelSeriesTest {

    @Test
    fun `серии сериализуются и читаются обратно`() {
        val panel = Panel(
            id = 7,
            connectionId = 1,
            name = "Температура",
            type = WidgetType.MULTI_CHART,
            chartPoints = 20,
            series = listOf(
                ChartSeries(topic = "home/temp", color = "#FF0000", label = "Температура"),
                ChartSeries(topic = "home/hum", color = "#00FF00", label = "Влажность")
            )
        )
        val json = Gson().toJson(panel)
        val back = Gson().fromJson(json, Panel::class.java)
        assertEquals(WidgetType.MULTI_CHART, back.type)
        assertEquals(2, back.series.size)
        assertEquals("home/temp", back.series[0].topic)
        assertEquals("#FF0000", back.series[0].color)
        assertEquals("Температура", back.series[0].label)
    }

    @Test
    fun `панель из старого конфига без серий парсится`() {
        // В конфигах до 0.2.9 поля series нет; Gson не вызывает конструктор,
        // поэтому поле остаётся физически null — AppRepository.fixIds()
        // подставляет пустой список. Здесь проверяем, что JSON парсится.
        val json = """{"id":1,"name":"x","type":"CHART","chartPoints":50}"""
        val panel = Gson().fromJson(json, Panel::class.java)
        assertEquals(WidgetType.CHART, panel.type)
        assertEquals(50, panel.chartPoints)
    }
}
