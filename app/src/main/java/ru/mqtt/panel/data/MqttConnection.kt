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
    var apiKey: String = ""
) : Serializable {

    constructor() : this(0)

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
