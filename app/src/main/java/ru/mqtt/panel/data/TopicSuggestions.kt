package ru.mqtt.panel.data

/**
 * Автоподбор параметров виджета по содержимому топика — как «auto create
 * widgets» в linear-mqtt-dashboard, но с определением типа по последнему
 * сообщению: число → Значение, ON/OFF/true/false → Переключатель, иначе Текст.
 */
object TopicSuggestions {

    private val ON_VALUES = setOf("on", "1", "true", "open", "вкл", "истина", "high")
    private val OFF_VALUES = setOf("off", "0", "false", "close", "выкл", "ложь", "low")

    /** Название виджета — последний осмысленный сегмент темы */
    fun nameFromTopic(topic: String): String {
        val segments = topic.split("/").filter { it.isNotBlank() && it != "+" && it != "#" }
        val last = segments.lastOrNull() ?: return topic
        return if (last.all { it.isDigit() } && segments.size > 1) {
            segments.takeLast(2).joinToString("/")
        } else {
            last
        }
    }

    /** Глубина темы — используется для сортировки: сначала короткие ветки */
    fun depth(topic: String): Int = topic.count { it == '/' }

    /** Число ли payload (целое/дробное, возможно с единицами через пробел) */
    fun isNumeric(payload: String): Boolean {
        val token = payload.trim().substringBefore(' ').replace(",", ".")
        return token.toDoubleOrNull() != null
    }

    /** Парные значения в одной нотации: пришедшее ON → публикуем OFF и т.п. */
    private val BOOL_PAIRS = mapOf(
        "1" to "0", "0" to "1",
        "on" to "off", "off" to "on",
        "true" to "false", "false" to "true",
        "open" to "close", "close" to "open",
        "high" to "low", "low" to "high",
        "вкл" to "выкл", "выкл" to "вкл",
        "истина" to "ложь", "ложь" to "истина"
    )

    /**
     * Если payload — булево представление, возвращает пару on/off в тех же
     * обозначениях, что использует брокер ("1"/"0", "ON"/"OFF", "true"/"false"),
     * чтобы переключатель публиковал совместимое значение.
     */
    private fun boolPair(payload: String): Pair<String, String>? {
        val value = payload.trim()
        val normalized = value.lowercase()
        val counterpartRaw = BOOL_PAIRS[normalized] ?: return null
        val counterpart = if (value.any { it.isLetter() } && value == value.uppercase())
            counterpartRaw.uppercase() else counterpartRaw
        return if (normalized in ON_VALUES) value to counterpart else counterpart to value
    }

    /** JSON-подобный payload — такие темы бессмысленно показывать одной строкой */
    fun looksStructured(payload: String): Boolean {
        val t = payload.trim()
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
    }

    /**
     * Подбор типа и значений по последнему сообщению.
     * 0/1 тоже попадают в boolPair → SWITCH (публикует те же 1/0) — это
     * типичный случай реле/лампочек; числовые 21.5 → VALUE, остальное → TEXT.
     * Возвращает готовый к сохранению Panel (id/connectionId проставляет вызывающий).
     */
    fun suggest(topic: String, payload: String): Panel {
        val base = Panel(
            name = nameFromTopic(topic),
            topicSubscribe = topic,
            type = WidgetType.TEXT
        )
        if (payload.isBlank()) return base
        if (looksStructured(payload)) return base
        boolPair(payload)?.let { (on, off) ->
            return base.copy(type = WidgetType.SWITCH, onPayload = on, offPayload = off)
        }
        if (isNumeric(payload)) return base.copy(type = WidgetType.VALUE)
        return base
    }
}
