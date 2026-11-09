package ru.mqtt.panel.data

import java.io.Serializable

/**
 * Одна линия мультиграфика: тема + цвет + подпись в легенде.
 * У MULTI_CHART-панели темы подписки живут здесь, а не в topicSubscribe.
 */
data class ChartSeries(
    var topic: String = "",
    var color: String = "#4CAF50",
    var label: String = ""
) : Serializable

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
    // для MULTI_CHART: несколько тем на одном графике (легенда цветов)
    var series: List<ChartSeries> = emptyList(),
    // для LED
    var ledOnColor: String = "#4CAF50",
    var ledOffColor: String = "#F44336",
    // порядок карточки на экране (режим перемещения); 0 у всех — порядок вставки
    var sortOrder: Int = 0,
    // ширина карточки в 6-колоночной сетке: 6 = вся ширина, 3 = 1/2, 2 = 1/3
    var span: Int = 2,
    // единица измерения/суффикс к значению: "°C", "%", " dB" (как в narodmon)
    var unitSuffix: String = "",
    // источник данных: "mqtt" (по умолчанию) или "narodmon". У narodmon-панелей
    // topicSubscribe хранит ID датчика строкой — маршрутизация значений и графики
    // переиспользуются без изменений
    var source: String = "mqtt",
    // показывать на карточке время последнего обновления значения.
    // Boolean? — чтобы конфиги, созданные до появления поля (Gson не вызывает
    // конструктор), читались как «включено», а не как «выключено»
    var showTime: Boolean? = true,
    // динамический цвет значения: цвет минимума/середины/максимума диапазона
    // (слайдер Мин..Макс); промежуточные значения — плавный переход между ними.
    // Пустые строки = динамический цвет выключен
    var colorMin: String = "",
    var colorMid: String = "",
    var colorMax: String = "",
    // время последнего принятого значения (runtime: в конфиг не пишется,
    // хранится в runtime-<id>.json вместе с историей графиков)
    var lastTime: Long = 0,
    // последнее значение
    var lastValue: String = ""
) : Serializable
