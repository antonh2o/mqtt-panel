package ru.mqtt.panel.data

/**
 * Реестр observed-топиков — аналог TopicsCollector + getUnusedTopics из
 * linear-mqtt-dashboard. Сервис записывает сюда каждую пришедшую тему,
 * сканер показывает список, а пользователь выбирает, из каких топиков
 * сделать виджеты.
 *
 * Потокобезопасен: запись идёт из callback-потока Paho, чтение — из UI.
 */
class TopicRegistry(private val maxEntries: Int = 5000) {

    data class Observed(
        val topic: String,
        val lastPayload: String,
        val lastSeenAt: Long,
        val count: Int,
        val connectionId: Long = -1L
    )

    private val map = java.util.concurrent.ConcurrentHashMap<String, Observed>()

    val size: Int get() = map.size

    /** Ограничение на размер payload: журналы и JSON-файлы не должны раздувать память */
    fun record(
        topic: String,
        payload: ByteArray,
        timestamp: Long = System.currentTimeMillis(),
        connectionId: Long = -1L
    ) {
        if (topic.isBlank()) return
        val text = payloadToString(payload)
        map[topic] = Observed(topic, text, timestamp, (map[topic]?.count ?: 0) + 1, connectionId)
        if (map.size > maxEntries) pruneOldest()
    }

    private fun payloadToString(payload: ByteArray): String {
        // JSON-сообщения (сканер раскрывает их в датчики) хранятся до 16 КБ —
        // метеостанция может прислать многокилобайтный payload; обычный текст —
        // по-прежнему коротко, чтобы 5000 тем не раздували память
        val first = payload.firstOrNull()?.toChar() ?: ' '
        val limit = if (first == '{' || first == '[') MAX_JSON_PAYLOAD_CHARS else MAX_PAYLOAD_CHARS
        if (payload.size > limit) {
            return String(payload, 0, limit, Charsets.UTF_8) + "…"
        }
        return String(payload, Charsets.UTF_8).replace("\r", " ").replace("\n", " ").trim()
    }

    /** Сбрасываем самые старые по времени последнего сообщения */
    private fun pruneOldest() {
        val overflow = map.size - maxEntries
        if (overflow <= 0) return
        map.values.sortedBy { it.lastSeenAt }.take(overflow).forEach { map.remove(it.topic, it) }
    }

    /**
     * Снимок тем. `connectionId` >= 0 оставляет только темы этого подключения —
     * иначе сканер одного брокера показывал бы топики другого.
     */
    fun snapshot(connectionId: Long = -1L): List<Observed> =
        map.values
            .filter { connectionId < 0 || it.connectionId == connectionId }
            .sortedWith(compareBy({ TopicSuggestions.depth(it.topic) }, { it.topic }))

    /** Темы этого подключения, на которые не подписана ни одна панель */
    fun uncovered(subscribedFilters: Collection<String>, connectionId: Long = -1L): List<Observed> =
        snapshot(connectionId).filterNot { MqttTopicFilter.coveredByAny(subscribedFilters, it.topic) }

    fun clear(connectionId: Long = -1L) {
        if (connectionId < 0) map.clear()
        else map.values.filter { it.connectionId == connectionId }
            .forEach { map.remove(it.topic, it) }
    }

    private companion object {
        const val MAX_PAYLOAD_CHARS = 200
        const val MAX_JSON_PAYLOAD_CHARS = 16 * 1024
    }
}
