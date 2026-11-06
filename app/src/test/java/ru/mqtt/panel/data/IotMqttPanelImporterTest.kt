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
        // progress — новый тип вместо vertical-meter
        assertEquals(WidgetType.VALUE, banya.type)
    }

    @Test
    fun `новый формат v214 — графики разложены из comboItemList`() {
        val charts = report214.config.panels.filter { it.type == WidgetType.CHART }
        assertTrue("графины есть", charts.isNotEmpty())
        charts.forEach {
            assertTrue("тема '${it.topicSubscribe}' должна содержать префикс", it.topicSubscribe.startsWith("antonh2o/"))
        }
        // panel_28: 3 линии (Баня, Дом Xiaomi, Улица)
        assertTrue("как минимум линии графикаTemperature", charts.any { it.name == "Баня" && it.topicSubscribe == "antonh2o/tuwa3/dsw1" })
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
        assertEquals(110, report.config.panels.size)
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
        // Sonoff: 9 исходных панелей, из них 1 line-graph с 2 линиями → всего 10
        assertEquals(10, counts[report.config.connections[0].id])
    }

    @Test
    fun `темы склеены с префиксом дашборда`() {
        // Первое подключение в файле — prefix '/user/f60413/sonoff1/', панель 'Котел' = dsw1
        val first = report.config.connections[0]
        val panels = report.config.panels.filter { it.connectionId == first.id }

        val kotел = panels.first { it.name.startsWith("Котел") }
        assertEquals("/user/f60413/sonoff1/dsw1", kotел.topicSubscribe)
        assertEquals(WidgetType.VALUE, kotел.type)

        val slider = panels.first { it.type == WidgetType.SLIDER }
        assertTrue(
            "тема слайдера (${slider.topicSubscribe}) должна содержать префикс",
            slider.topicSubscribe.startsWith("/user/f60413/sonoff1/")
        )
    }

    @Test
    fun `графовые линии разложены в отдельные панели с темами`() {
        val charts = report.config.panels.filter { it.type == WidgetType.CHART }
        assertEquals("линий графика должно быть 38", 38, charts.size)
        charts.forEach {
            assertTrue("у графика '${it.name}' пустая тема", it.topicSubscribe.isNotBlank())
            assertTrue(
                "тема графика '${it.topicSubscribe}' без префикса",
                it.topicSubscribe.count { ch -> ch == '/' } >= 2
            )
        }
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
