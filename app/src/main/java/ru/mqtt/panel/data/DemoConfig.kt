package ru.mqtt.panel.data

import java.util.Locale
import java.util.Random
import java.util.UUID

/**
 * Демо-конфигурация для первого запуска: подключение к публичному брокеру
 * broker.emqx.io и дашборд с живыми значениями. Публичных лент на брокере нет
 * ($SYS и `#` анонимам закрыты), поэтому приложение само публикует
 * правдоподобные показания датчиков на СВОИ топики с уникальным префиксом
 * `mqttpanel/d<token>/...` (token на установку) и сразу их показывает —
 * демо не мешает другим установкам и не требует ключей.
 *
 * Народмон в демо не входит: API платформы требует личный API-ключ.
 */
object DemoConfig {

    const val DEMO_HOST = "broker.emqx.io"
    const val DEMO_PORT = 1883
    private const val ROOT = "mqttpanel"

    // суффиксы тем демо-датчиков (полную тему даёт prefix + "/" + суффикс)
    const val S_HOME_T = "home/temperature"
    const val S_HOME_H = "home/humidity"
    const val S_CO2 = "home/co2"
    const val S_STREET_T = "street/temperature"
    const val S_ONLINE = "system/online"
    /** свитч: публикует сам пользователь, демо-генератор его не трогает */
    const val S_LIGHT = "home/light"

    fun newToken(): String = UUID.randomUUID().toString().take(8)
    fun prefix(token: String): String = "$ROOT/d$token"

    /** Префикс демо-топиков из темы вида mqttpanel/dXXXXXXXX/… (null — не демо) */
    fun prefixOfTopic(topic: String): String? {
        val parts = topic.split('/')
        if (parts.size > 2 && parts[0] == ROOT &&
            parts[1].length == 9 && parts[1][0] == 'd'
        ) return "${parts[0]}/${parts[1]}"
        return null
    }

    fun build(token: String = newToken()): AppConfig {
        val p = prefix(token)
        val conn = MqttConnection(
            name = "Демо · $DEMO_HOST",
            host = DEMO_HOST,
            port = DEMO_PORT,
            demo = true
        )
        fun panel(
            name: String, type: WidgetType, topic: String,
            configure: Panel.() -> Unit = {}
        ) = Panel(
            connectionId = 1,
            name = name,
            type = type,
            topicSubscribe = topic,
            topicPublish = topic,
        ).apply(configure)

        val panels = listOf(
            panel("🌡 Дома", WidgetType.VALUE, "$p/$S_HOME_T") {
                unitSuffix = " °C"; span = 2
                sliderMin = 19.0; sliderMax = 24.0
                colorMin = "#42A5F5"; colorMid = "#66BB6A"; colorMax = "#EF5350"
            },
            panel("🌡 Улица", WidgetType.VALUE, "$p/$S_STREET_T") {
                unitSuffix = " °C"; span = 2
                sliderMin = -5.0; sliderMax = 10.0
                colorMin = "#42A5F5"; colorMid = "#66BB6A"; colorMax = "#EF5350"
            },
            panel("💧 Влажность", WidgetType.PROGRESS_H, "$p/$S_HOME_H") {
                unitSuffix = " %"; span = 3
                sliderMin = 0.0; sliderMax = 100.0
            },
            panel("🌫 CO₂", WidgetType.VALUE, "$p/$S_CO2") {
                unitSuffix = " ppm"; span = 3
                sliderMin = 400.0; sliderMax = 1000.0
                colorMin = "#66BB6A"; colorMid = "#FFA726"; colorMax = "#EF5350"
            },
            panel("💡 Свет", WidgetType.SWITCH, "$p/$S_LIGHT") {
                span = 2; onPayload = "ON"; offPayload = "OFF"
            },
            panel("🛡 Брокер", WidgetType.LED, "$p/$S_ONLINE") {
                span = 2
            },
            panel("🌡 Дома — график", WidgetType.CHART, "$p/$S_HOME_T") {
                span = 6; chartPoints = 120
                sliderMin = 19.0; sliderMax = 24.0
                colorMin = "#42A5F5"; colorMid = "#66BB6A"; colorMax = "#EF5350"
            },
            Panel(
                connectionId = 1,
                name = "🌡 Дома и улица",
                type = WidgetType.MULTI_CHART,
                chartPoints = 120,
                span = 6,
                series = listOf(
                    ChartSeries("$p/$S_HOME_T", "#43A047", "Дома"),
                    ChartSeries("$p/$S_STREET_T", "#1E88E5", "Улица")
                )
            )
        )
        return AppConfig(listOf(conn), panels)
    }

    /**
     * Генератор правдоподобных показаний: случайное блуждание в разумных
     * границах. Чистый класс — тестируется на JVM без Android.
     */
    class DemoSensors(private val rng: Random = Random()) {
        private var homeT = 21.4
        private var homeH = 47.0
        private var co2 = 620.0
        private var streetT = 3.8

        /** один шаг генератора: суффикс темы → payload */
        fun tick(): List<Pair<String, String>> {
            homeT = (homeT + next(-0.15, 0.15)).coerceIn(19.5, 23.5)
            homeH = (homeH + next(-0.8, 0.8)).coerceIn(35.0, 60.0)
            co2 = (co2 + next(-30.0, 30.0)).coerceIn(450.0, 1000.0)
            streetT = (streetT + next(-0.2, 0.2)).coerceIn(-3.0, 9.0)
            return listOf(
                S_HOME_T to f1(homeT),
                S_HOME_H to f1(homeH),
                S_CO2 to f0(co2),
                S_STREET_T to f1(streetT),
                S_ONLINE to "1"
            )
        }

        private fun next(from: Double, to: Double) = from + (to - from) * rng.nextDouble()
        private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
        private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    }
}
