package ru.mqtt.panel.data

import java.io.Serializable

/**
 * Модель панели-виджета.
 * Каждая панель привязана к connectionId и имеет тему подписки/публикации.
 */
data class Panel(
    val id: Long = 0,
    val connectionId: Long = 0,
    var name: String = "",
    var type: WidgetType = WidgetType.VALUE,
    var topicSubscribe: String = "",
    var topicPublish: String = "",
    var qos: Int = 0,
    var retained: Boolean = false,
    // для SWITCH
    var onPayload: String = "1",
    var offPayload: String = "0",
    // для SLIDER
    var sliderMin: Double = 0.0,
    var sliderMax: Double = 100.0,
    // для BUTTON
    var buttonPayload: String = "ON",
    // для CHART
    var chartPoints: Int = 50,
    var chartColor: String = "#4CAF50",
    var chartShowScale: Boolean = true,
    // для LED
    var ledOnColor: String = "#4CAF50",
    var ledOffColor: String = "#F44336",
    // порядок карточки на экране (режим перемещения); 0 у всех — порядок вставки
    var sortOrder: Int = 0,
    // ширина карточки в 6-колоночной сетке: 6 = вся ширина, 3 = 1/2, 2 = 1/3
    var span: Int = 2,
    // единица измерения/суффикс к значению: "°C", "%", " dB" (как в narodmon)
    var unitSuffix: String = "",
    // последнее значение
    var lastValue: String = ""
) : Serializable
