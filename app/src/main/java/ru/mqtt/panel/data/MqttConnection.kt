package ru.mqtt.panel.data

import java.io.Serializable

/**
 * Модель подключения к MQTT-брокеру.
 * Аналог конфигурации соединения в IoT MQTT Panel.
 */
data class MqttConnection(
    val id: Long = 0,
    var name: String = "",
    var host: String = "broker.emqx.io",
    var port: Int = 1883,
    var username: String = "",
    var password: String = "",
    var ssl: Boolean = false,
    var websocket: Boolean = false,
    var keepAlive: Int = 60,
    var cleanSession: Boolean = true,
    var clientId: String = "",
    // true — «подключение» это аккаунт Народного Мониторинга: поля host/port и
    // MQTT-настройки не используются, дашборд опрашивает api.narodmon.ru
    var narodmon: Boolean = false,
    // личный API-ключ narodmon.ru («Мои приложения»); у каждого пользователя свой,
    // лимит платформы считается на ключ и IP — поэтому в код приложения не зашит
    var apiKey: String = "",
    // --- История из Grafana (только для MQTT-подключений): живые точки
    // остаются из MQTT, история чипов 1d/1w/1m/1y тянется из InfluxDB
    // через POST {ghUrl}/api/ds/query. Включено = ghUrl + ghDsUid +
    // ghMeasurement непустые. Настраивается ОДИН раз на подключение и
    // действует на все графики; значение тега = тема подписки/серии.
    var ghUrl: String? = null,
    var ghUser: String? = null,
    var ghPass: String? = null,
    var ghDsUid: String? = null,
    var ghMeasurement: String? = null,
    var ghField: String? = null,
    var ghTagKey: String? = null
) : Serializable {

    constructor() : this(0)

    /** Grafana включена для этого подключения */
    fun grafanaEnabled(): Boolean =
        !ghUrl.isNullOrBlank() && !ghDsUid.isNullOrBlank() && !ghMeasurement.isNullOrBlank()

    /** Заголовок типа на главном экране: MQTT / MQTT + Grafana / Narodmon */
    fun typeLabel(): String = when {
        narodmon -> "Narodmon"
        grafanaEnabled() -> "MQTT + Grafana"
        else -> "MQTT"
    }

    fun getScheme(): String {
        return when {
            websocket && ssl -> "wss"
            websocket -> "ws"
            ssl -> "ssl"
            else -> "tcp"
        }
    }

    fun isValid(): Boolean {
        return name.isNotBlank() && host.isNotBlank()
    }
}
