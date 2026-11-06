package ru.mqtt.panel.data

/**
 * Тип виджета-панели. Соответствует типам из IoT MQTT Panel.
 */
enum class WidgetType(val label: String) {
    VALUE("Значение"),
    SWITCH("Переключатель"),
    BUTTON("Кнопка"),
    SLIDER("Слайдер"),
    LED("Светодиод"),
    TEXT("Текст"),
    CHART("График"),
    MULTI_CHART("Мультиграфик");

    companion object {
        fun fromLabel(label: String): WidgetType =
            entries.find { it.label == label } ?: VALUE
    }
}

/**
 * Ширина карточки в 6-колоночной сетке (как в Bootstrap grid).
 */
enum class Span(val label: String, val spanCount: Int) {
    FULL("На всю ширину", 6),
    HALF("1/2 ширины", 3),
    THIRD("1/3 ширины", 2);

    companion object {
        fun fromSpanCount(count: Int): Span =
            entries.find { it.spanCount == count } ?: THIRD
    }
}
