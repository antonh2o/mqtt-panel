package ru.mqtt.panel.data

import java.io.Serializable

/**
 * Полная конфигурация приложения для экспорта/импорта.
 */
data class AppConfig(
    val connections: List<MqttConnection> = emptyList(),
    val panels: List<Panel> = emptyList()
) : Serializable
