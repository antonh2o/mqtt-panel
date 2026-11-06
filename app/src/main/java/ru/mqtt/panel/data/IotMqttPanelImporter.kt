package ru.mqtt.panel.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * Парсер конфигурации из формата IoT MQTT Panel (экспорт JSON).
 *
 * Поддерживает два layout'а файла:
 *  - старый (dataVersion ~54): dashboards[] вложены в connections[],
 *    panels[] вложены в dashboards[];
 *  - новый (dataVersion ~214): connections[], dashboards[], panels[] —
 *    три массива верхнего уровня, связи через строковые id
 *    ("connection_13", "dashboard_3").
 *
 * Конвертирует в наш формат AppConfig.
 */
object IotMqttPanelImporter {
    private val gson = Gson()

    /** Подробный отчёт о конвертации для журнала отладки */
    data class ImportReport(
        val config: AppConfig,
        val skipped: List<String>,
        val details: List<String>
    )

    /** Дашборд: к какому нашему подключению привязан и префикс тем */
    private data class DashInfo(val connectionId: Long, val prefix: String, val name: String)

    fun importFromJson(json: String): Result<AppConfig> =
        importDetailed(json).map { it.config }

    fun importDetailed(json: String): Result<ImportReport> = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val skipped = mutableListOf<String>()
        val details = mutableListOf<String>()

        details.add("=== IoT MQTT Panel Importer ===")
        details.add("dataVersion: ${root.get("dataVersion")?.asInt ?: "?"}")
        details.add("Root keys: ${root.keySet().joinToString(", ") { "$it=${typeOf(root.get(it))}" }}")

        val connectionsArr = root.getAsJsonArray("connections")
            ?: throw IllegalStateException("No connections array")

        // --- 1. Подключения: id по позиции; запоминаем строковый connectionId ---
        val connections = mutableListOf<MqttConnection>()
        val connByStrId = mutableMapOf<String, Long>()
        var connIdx = 0L
        for (connObj in connectionsArr) {
            val co = connObj.asJsonObject
            connIdx += 1
            val c = MqttConnection(
                id = connIdx,
                name = co.get("connectionName")?.asString ?: "Безымянное",
                host = co.get("host")?.asString ?: "",
                port = co.get("port")?.asInt ?: 1883,
                username = co.get("username")?.asString?.takeIf { it.isNotBlank() } ?: "",
                password = co.get("password")?.asString?.takeIf { it.isNotBlank() } ?: "",
                keepAlive = co.get("keepAlive")?.asInt ?: 60,
                cleanSession = co.get("cleanSession")?.asBoolean ?: true,
                // Свой уникальный clientId: если IoT MQTT Panel живёт на том же
                // брокере с тем же clientId, они будут выбивать друг друга
                clientId = "mqttpanel-" + UUID.randomUUID().toString().take(8),
                ssl = false,
                websocket = false
            ).apply {
                // connectionType: tcp, ssl, websocket, ws
                when (co.get("connectionType")?.asString) {
                    "ssl" -> ssl = true
                    "websocket", "ws" -> websocket = true
                }
            }
            connections.add(c)
            co.get("connectionId")?.asString?.let { connByStrId[it] = c.id }
            details.add("  [${c.id}] ${c.name} (${c.host}:${c.port}, user=${c.username.ifBlank { "—" }}, pass=${if (c.password.isNotBlank()) "★" else "—"}, type=${c.getScheme()}, clientId=${c.clientId.take(4)}...)")
        }
        details.add("Connections total: ${connections.size}")

        // --- 2. Дашборды ---
        // В старом формате dashboardId у всех подключений одинаковый ("dashboard_0"),
        // поэтому вложенные дашборды не заносятся в общую таблицу — их привязка
        // к подключению и префикс берутся прямо из цикла по connections.
        // Таблица нужна только для плоского формата v214.
        val dashboards = mutableMapOf<String, DashInfo>()
        root.getAsJsonArray("dashboards")?.forEach { d ->
            val dash = d.asJsonObject
            val id = dash.get("dashboardId")?.asString ?: return@forEach
            val connStr = dash.get("connectionId")?.asString
            val cid = connByStrId[connStr]
                ?: connections.firstOrNull()?.id
                ?: 0L
            dashboards[id] = DashInfo(cid, prefixOf(dash), nameOf(dash))
        }
        dashboards.forEach { (id, d) ->
            details.add("  Dashboard '$id' → conn=${d.connectionId} prefix='${d.prefix}' name=${d.name}")
        }

        // --- 3. Панели ---
        val panels = mutableListOf<Panel>()
        var counter = 0L

        // 3a. Старый формат: panels[] вложены в dashboards[] каждой connection
        var idx = 0
        for (connObj in connectionsArr) {
            val co = connObj.asJsonObject
            val connId = connections[idx++].id
            co.getAsJsonArray("dashboards")?.forEach { d ->
                val dash = d.asJsonObject
                val prefix = prefixOf(dash)
                dash.getAsJsonArray("panels")?.forEach { p ->
                    counter = addPanel(p.asJsonObject, connId, prefix, panels, counter, skipped, details)
                }
            }
        }

        // 3b. Новый формат: panels[] верхним массивом с connectionId/dashboardId
        root.getAsJsonArray("panels")?.forEach { p ->
            val po = p.asJsonObject
            val dashStr = po.get("dashboardId")?.asString
            val connStr = po.get("connectionId")?.asString
            val dash = dashboards[dashStr]
            val connId = connByStrId[connStr] ?: dash?.connectionId
                ?: connections.firstOrNull()?.id ?: 0L
            val prefix = if (po.get("disableDashboardPrefix")?.asBoolean == true) ""
                else dash?.prefix ?: ""
            counter = addPanel(po, connId, prefix, panels, counter, skipped, details)
        }

        details.add("=== ИТОГО ===")
        details.add("Подключений: ${connections.size}")
        details.add("Панелей: ${panels.size}")
        if (skipped.isNotEmpty()) {
            details.add("Пропущено типов: ${skipped.distinct().joinToString(", ")}")
        }

        val config = AppConfig(connections, panels)
        ImportReport(config, skipped, details)
    }

    private fun addPanel(
        po: JsonObject,
        connectionId: Long,
        prefix: String,
        panels: MutableList<Panel>,
        counter: Long,
        skipped: MutableList<String>,
        details: MutableList<String>
    ): Long {
        val (panel, newCounter) = convertPanel(po, connectionId, panels, counter, prefix, skipped, details)
        panel?.let { panels.add(it) }
        return newCounter
    }

    private fun prefixOf(dash: JsonObject): String =
        dash.get("dashboardPrefixTopic")?.asString
            ?.takeIf { it.isNotBlank() && it != "null" } ?: ""

    private fun nameOf(dash: JsonObject): String =
        dash.get("dashboardName")?.asString ?: "безымянный"

    private fun typeOf(el: JsonElement?): String = when {
        el == null -> "missing"
        el.isJsonNull -> "null"
        el.isJsonArray -> "array(${el.asJsonArray.size()})"
        el.isJsonObject -> "object"
        else -> "scalar"
    }

    /** Склеивает префикс дашборда с относительной темой панели */
    private fun fullTopic(prefix: String, topic: String): String {
        if (topic.isBlank()) return topic
        if (prefix.isBlank()) return topic
        // Тема может быть абсолютной (начинается с /) — тогда префикс не нужен
        if (topic.startsWith("/")) return topic
        return prefix.trimEnd('/') + "/" + topic.trimStart('/')
    }

    private fun convertPanel(
        po: JsonObject,
        connectionId: Long,
        panels: MutableList<Panel>,
        counter: Long,
        prefix: String,
        skipped: MutableList<String>,
        details: MutableList<String>
    ): Pair<Panel?, Long> {
        val typeStr = po.get("type")?.asString ?: return Pair(null, counter)
        val mqttType = po.get("mqttType")?.asString ?: "sub"
        val panelName = po.get("panelName")?.asString ?: "Безымянная"
        val panelId = po.get("panelId")?.asString ?: "unknown"

        val rawTopic = po.get("topic")?.asString?.takeIf { it.isNotBlank() && it != "None" } ?: ""
        val subscribe = fullTopic(prefix, rawTopic)
        val publish = when (mqttType) {
            "pub", "pubsub" -> subscribe
            else -> ""
        }

        details.add("    Panel: id=$panelId type=$typeStr name='$panelName' topic='$subscribe' publish='$publish'")

        var pid = counter.toLong()

        // line-graph с comboItemList — это мультиграфик: все линии в одной панели
        if (typeStr == "line-graph") {
            val combo = po.getAsJsonArray("comboItemList")
            if (combo != null && combo.size() > 0) {
                val seriesList = combo.mapNotNull { item ->
                    val itemObj = item.asJsonObject
                    val itemTopic = itemObj.get("topic")?.asString?.takeIf { it.isNotBlank() } ?: rawTopic
                    val topic = fullTopic(prefix, itemTopic)
                    if (topic.isBlank()) return@mapNotNull null
                    ChartSeries(
                        topic = topic,
                        color = itemObj.get("color")?.asString ?: "#4CAF50",
                        label = itemObj.get("label")?.asString ?: topic.substringAfterLast('/')
                    )
                }
                val p = Panel(
                    connectionId = connectionId,
                    name = panelName,
                    type = WidgetType.MULTI_CHART,
                    topicSubscribe = "",
                    topicPublish = "",
                    qos = po.get("qos")?.asInt ?: 0,
                    chartPoints = po.get("maxPersistence")?.asInt ?: 50,
                    series = seriesList,
                    id = ++pid
                )
                panels.add(p)
                details.add("      → мультиграфик: ${seriesList.size} линий (${seriesList.joinToString(", ") { "${it.label}=${it.topic}" }})")
                return Pair(null, pid)
            }
        }

        val widgetType = mapPanelType(typeStr)
        if (widgetType == null) {
            skipped.add(typeStr)
            return Pair(null, counter)
        }

        val panel = Panel(
            connectionId = connectionId,
            name = panelName,
            type = widgetType,
            topicSubscribe = subscribe,
            topicPublish = publish,
            qos = po.get("qos")?.asInt ?: 0,
            sliderMin = po.get("payloadMin")?.asDouble ?: 0.0,
            sliderMax = po.get("payloadMax")?.asDouble ?: 100.0,
            chartPoints = po.get("maxPersistence")?.asInt ?: 50,
            id = ++pid
        )

        // Специфичные параметры
        when (typeStr) {
            "switch" -> {
                panel.onPayload = po.get("payloadOn")?.asString ?: "1"
                panel.offPayload = po.get("payloadOff")?.asString ?: "0"
                panel.retained = po.get("retain")?.asBoolean ?: false
                details.add("      switch: on='${panel.onPayload}' off='${panel.offPayload}' retained=${panel.retained}")
            }
            "vertical-meter", "progress" -> {
                val unit = po.get("unit")?.asString
                if (unit != null) {
                    panel.name = "${panel.name} $unit"
                }
            }
            "log" -> {
                panel.type = WidgetType.TEXT
                val logLen = po.get("logLength")?.asInt
                if (logLen != null) panel.chartPoints = logLen
            }
            "node-status" -> {
                panel.type = WidgetType.LED
                panel.onPayload = "1"
                panel.offPayload = "0"
            }
        }

        return Pair(panel, pid)
    }

    private fun mapPanelType(typeStr: String): WidgetType? = when (typeStr) {
        "vertical-meter", "meter", "progress" -> WidgetType.VALUE
        "slider" -> WidgetType.SLIDER
        "switch" -> WidgetType.SWITCH
        "button" -> WidgetType.BUTTON
        "line-graph", "graph" -> WidgetType.CHART
        "log", "text" -> WidgetType.TEXT
        "led", "node-status" -> WidgetType.LED
        "value" -> WidgetType.VALUE
        else -> null
    }
}
