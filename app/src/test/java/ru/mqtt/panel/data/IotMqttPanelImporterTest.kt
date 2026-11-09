package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Прогон реального экспорта IoT MQTT Panel через конвертер.
 * Ловит главные регрессии: привязку панелей к подключениям и склейку тем с префиксом.
 * Тест не зависит от имён подключений — пользователь может их переименовывать.
 */
class IotMqttPanelImporterTest {

    private val report: IotMqttPanelImporter.ImportReport by lazy {
        val json = javaClass.classLoader!!
            .getResourceAsStream("iot-export.json")!!
            .readBytes().decodeToString()
        IotMqttPanelImporter.importDetailed(json).getOrThrow()
    }

    /** Экспорт dataVersion 214: dashboards/panels — массивы верхнего уровня */
    private val report214: IotMqttPanelImporter.ImportReport by lazy {
        val json = javaClass.classLoader!!
            .getResourceAsStream("iot-export-v214.json")!!
            .readBytes().decodeToString()
        IotMqttPanelImporter.importDetailed(json).getOrThrow()
    }

    @Test
    fun `новый формат v214 — импортирует подключения и панели`() {
        assertEquals(2, report214.config.connections.size)
        assertTrue("панелей должно быть больше нуля", report214.config.panels.isNotEmpty())
        assertTrue("не должно быть пропущенных типов: ${report214.skipped}", report214.skipped.isEmpty())
    }

    @Test
    fun `новый формат v214 — панели привязаны по connectionId`() {
        val connIds = report214.config.connections.map { it.id }.toSet()
        val counts = report214.config.panels.groupingBy { it.connectionId }.eachCount()
        assertTrue("все панели привязаны", counts.keys.all { it in connIds })
        // В файле: connection_13 (tuwa3) и connection_14 (ESP32sat), у обоих есть панели
        assertEquals("у обоих подключений есть панели", 2, counts.size)
    }

    @Test
    fun `новый формат v214 — темы склеены с префиксом дашборда`() {
        val tuwa = report214.config.connections.first { it.name.contains("Tuwa3") }
        val panels = report214.config.panels.filter { it.connectionId == tuwa.id }
        val banya = panels.first { it.name.startsWith("Баня ds18b") }
        assertEquals("antonh2o/tuwa3/dsw1", banya.topicSubscribe)
        // progress импортируется прогрессом по ориентации (horizontal)
        assertEquals(WidgetType.PROGRESS_H, banya.type)
    }

    @Test
    fun `новый формат v214 — combo-график стал мультиграфиком с сериями`() {
        val multis = report214.config.panels.filter { it.type == WidgetType.MULTI_CHART }
        assertEquals("combo-графиков в файле 4", 4, multis.size)
        // panel_28 «Температура»: 3 линии (Баня, Дом Xiaomi, Улица)
        val temperature = multis.first { it.name == "Температура" }
        assertEquals(3, temperature.series.size)
        assertEquals("antonh2o/tuwa3/dsw1", temperature.series[0].topic)
        assertEquals("Баня", temperature.series[0].label)
        assertEquals("#fb2d05", temperature.series[0].color)
        assertTrue(
            "все темы серий с префиксом",
            temperature.series.all { it.topic.startsWith("antonh2o/") }
        )
        assertEquals("maxPersistence → точки графика", 5, temperature.chartPoints)
    }

    @Test
    fun `новый формат v214 — панели ESP32sat получили свой префикс`() {
        val sat = report214.config.connections.first { it.name.contains("Esp32sat") }
        val panels = report214.config.panels.filter { it.connectionId == sat.id }
        assertTrue("панели есть", panels.isNotEmpty())
        panels.forEach {
            assertTrue("тема '${it.topicSubscribe}' вне префикса ESP32sat", it.topicSubscribe.startsWith("antonh2o/ESP32sat/"))
        }
    }

    @Test
    fun `импортирует все подключения и панели`() {
        assertEquals(6, report.config.connections.size)
        // 87 исходных панелей: 15 combo-графиков больше не разворачиваются по линиям
        assertEquals(87, report.config.panels.size)
        assertTrue("не должно быть пропущенных типов: ${report.skipped}", report.skipped.isEmpty())
    }

    @Test
    fun `id подключений уникальны и непустые`() {
        val ids = report.config.connections.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("id начинаются с 1", ids.all { it >= 1 })
    }

    @Test
    fun `каждое подключение получает свои панели`() {
        val counts = report.config.panels.groupingBy { it.connectionId }.eachCount()
        val connIds = report.config.connections.map { it.id }.toSet()

        assertTrue(
            "все панели привязаны к существующим подключениям",
            counts.keys.all { it in connIds }
        )
        report.config.connections.forEach { c ->
            assertTrue("у '${c.name}' нет панелей (id=${c.id})", (counts[c.id] ?: 0) > 0)
        }
        // Sonoff: 9 исходных панелей, из них 1 combo-график → 1 мультиграфик, итого 9
        assertEquals(9, counts[report.config.connections[0].id])
    }

    @Test
    fun `темы склеены с префиксом дашборда`() {
        // Первое подключение в файле — prefix '/user/f60413/sonoff1/', панель 'Котел' = dsw1
        val first = report.config.connections[0]
        val panels = report.config.panels.filter { it.connectionId == first.id }

        val kotел = panels.first { it.name.startsWith("Котел") }
        assertEquals("/user/f60413/sonoff1/dsw1", kotел.topicSubscribe)
        assertEquals("vertical-meter импортируется вертикальным прогрессом",
            WidgetType.PROGRESS_V, kotел.type)

        val slider = panels.first { it.type == WidgetType.SLIDER }
        assertTrue(
            "тема слайдера (${slider.topicSubscribe}) должна содержать префикс",
            slider.topicSubscribe.startsWith("/user/f60413/sonoff1/")
        )
    }

    @Test
    fun `графики стали мультиграфиками с сериями`() {
        val multis = report.config.panels.filter { it.type == WidgetType.MULTI_CHART }
        assertEquals("combo-графиков должно быть 15", 15, multis.size)
        assertEquals("всего линий в сериях — 38", 38, multis.sumOf { it.series.size })
        multis.forEach { m ->
            assertTrue("у мультиграфика '${m.name}' нет серий", m.series.isNotEmpty())
            m.series.forEach { s ->
                assertTrue("у серии мультиграфика '${m.name}' пустая тема", s.topic.isNotBlank())
                assertTrue(
                    "тема серии '${s.topic}' без префикса дашборда",
                    s.topic.count { ch -> ch == '/' } >= 2
                )
            }
        }
    }

    @Test
    fun `vertical-meter импортируются вертикальным прогрессом (v54)`() {
        val progressV = report.config.panels.filter { it.type == WidgetType.PROGRESS_V }
        assertEquals(49, progressV.size)
        assertTrue(report.config.panels.none { it.type == WidgetType.PROGRESS_H })
    }

    @Test
    fun `прогрессы v214 раскладываются по ориентации`() {
        assertEquals(20, report214.config.panels.count { it.type == WidgetType.PROGRESS_V })
        assertEquals(3, report214.config.panels.count { it.type == WidgetType.PROGRESS_H })
    }

    @Test
    fun `clientId уникальны и не берутся из исходного файла`() {
        val ids = report.config.connections.map { it.clientId }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("все clientId с нашим префиксом", ids.all { it.startsWith("mqttpanel-") })
    }

    @Test
    fun `учётные данные сохранены`() {
        report.config.connections.forEach { c ->
            assertTrue("хост пуст у '${c.name}'", c.host.isNotBlank())
            assertTrue("имя пустое", c.name.isNotBlank())
        }
        val withUser = report.config.connections.filter { it.username.isNotBlank() }
        assertEquals("во всех подключениях файла задан логин", 6, withUser.size)
    }

    @Test
    fun `публикующие панели получили тему публикации`() {
        // switch у Sonoff имеет mqttType=pubsub => topicPublish совпадает с topicSubscribe
        val first = report.config.connections[0]
        val switches = report.config.panels
            .filter { it.connectionId == first.id && it.type == WidgetType.SWITCH }
        assertTrue("переключателей хотя бы один", switches.isNotEmpty())
        switches.forEach {
            assertEquals(
                "у переключаемой панели тема публикации = тема подписки",
                it.topicSubscribe, it.topicPublish
            )
        }
    }
}
