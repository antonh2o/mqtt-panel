package ru.mqtt.panel.data

/**
 * Разбор MQTT-фильтров подписок. Нужен сканеру топиков, чтобы понять,
 * какая панель уже «покрывает» найденную тему — иначе сканер предложил бы
 * создавать виджеты для топиков, которые и так подписаны через wildcard.
 */
object MqttTopicFilter {

    /** Валидность фильтра подписки: запрещены посимвольно `#` внутри и `+` внутри сегмента */
    fun isValidFilter(filter: String): Boolean {
        if (filter.isEmpty()) return false
        val parts = filter.split("/")
        parts.forEachIndexed { index, part ->
            if (part == "#" && index != parts.lastIndex) return false
            if (part.contains("#") && part != "#") return false
            if (part.contains("+") && part != "+") return false
        }
        return true
    }

    /** Совпадает ли тема с фильтром подписки (с учётом `+` и `#`) */
    fun matches(filter: String, topic: String): Boolean {
        if (filter == topic) return true
        // По MQTT-спецификации фильтр не матчит темы, начинающиеся с $, если сам не начинается с $
        if (topic.startsWith("$") && !filter.startsWith("$")) return false
        val f = filter.split("/")
        val t = topic.split("/")
        var i = 0
        while (i < f.size) {
            val part = f[i]
            if (part == "#") {
                // Многосегментный wildcard матчит всё оставшееся, но требует наличия сегмента
                return i == f.lastIndex && (t.size >= i + 1 || i == t.size)
            }
            if (i >= t.size) return false
            if (part != "+" && part != t[i]) return false
            i++
        }
        return i == t.size
    }

    /** Покрывает ли хоть одна из подписок эту тему */
    fun coveredByAny(filters: Collection<String>, topic: String): Boolean =
        filters.any { it.isNotBlank() && matches(it, topic) }
}
