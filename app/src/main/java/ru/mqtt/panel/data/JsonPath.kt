package ru.mqtt.panel.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Извлечь значение по JSON-пути из payload топика: «$.light.value.value»,
 * «$.sensors.0.temperature». Путь начинается с «$» (корень); секции
 * через точку, числовая секция — индекс массива.
 *
 * Возвращает строковое представление:
 *  - объект/массив — как есть (минифицированный JSON),
 *  - строка — БЕЗ кавычек («560», «3.1 м/с\nЗ»),
 *  - число/boolean — текст,
 *  - null (поле с JSON null) — "null".
 * null — payload не JSON или путь не найден (в т.ч. пустой/некорректный путь
 * — в этом случае значение не извлекается и показывается сырой payload).
 *
 * Чистый JVM (org.json входит в Android и в java8-вариант для юнит-тестов
 * через org.json:json), тестируется без эмулятора.
 */
fun extractJsonPath(payload: String, path: String): String? {
    val p = path.trim()
    if (p.isEmpty()) return null
    if (!p.startsWith("$")) return null
    val json = try {
        // JSONObject/JSONArray — payload обязан быть объектом или массивом на корне
        if (p == "$") {
            return null // сам корень без секций показывать не будем
        }
        // пробуем как объект, затем как массив
        try {
            JSONObject(payload)
        } catch (e: Exception) {
            JSONArray(payload)
        }
    } catch (e: Exception) {
        return null
    }

    // «$.a.b» → секции [a, b]; отбрасываем «$.» и пустые секции
    val parts = p.substring(1).removePrefix(".").split('.')
    var node: Any = json
    for (part in parts) {
        if (part.isEmpty()) return null
        node = when (node) {
            is JSONObject -> node.opt(part) ?: return null
            is JSONArray -> {
                val idx = part.toIntOrNull() ?: return null
                if (idx < 0 || idx >= node.length()) return null
                node.opt(idx)
            }
            else -> return null
        }
    }
    return nodeToString(node)
}

/** nodeToString: JSONObject.NULL — "null", строка — без кавычек, остальное — как есть */
private fun nodeToString(node: Any): String {
    if (node == JSONObject.NULL) return "null"
    return node.toString()
}
