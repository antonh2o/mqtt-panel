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

/**
 * Скалярный «лист» JSON-дерева для сканера датчиков: путь в нотации extractJsonPath
 * (например «$.light.value.value») и его значение как строка (без кавычек).
 * null — значение не выставляется: JSON null, объект, массив, пустой объект/массив,
 * пустая строка. Порядок: в Android-версии org.json ключи объекта отсортированы
 * (детерминировано, не в порядке источника); массивы — по индексу.
 */
data class JsonLeaf(val path: String, val value: String)

/**
 * Перечислить все скалярные листья payload'а — для сканера: JSON-тема
 * (например village/meteo/meteostation) раскрывается в отдельные датчики
 * с готовыми jsonPath. Порядок детерминированный: ключи объекта — в порядке
 * org.json (в Android — отсортированно), массив — по индексу. Ограничение:
 * maxLeaves листьев и maxDepth уровней вложенности (большие деревья не
 * раздувают список). Пустой список — payload не JSON (объект/массив на корне).
 */
fun extractJsonLeaves(payload: String, maxLeaves: Int = 32, maxDepth: Int = 8): List<JsonLeaf> {
    val root: Any = try {
        try {
            JSONObject(payload)
        } catch (e: Exception) {
            JSONArray(payload)
        }
    } catch (e: Exception) {
        return emptyList()
    }
    val out = ArrayList<JsonLeaf>()
    walkLeaves(root, "$", out, maxLeaves, maxDepth, 0)
    return out
}

private fun walkLeaves(node: Any, path: String, out: MutableList<JsonLeaf>, maxLeaves: Int, maxDepth: Int, depth: Int) {
    if (depth >= maxDepth) return
    when (node) {
        is JSONObject -> {
            // порядок ключей — как их отдаёт org.json (в Android — отсортирован)
            val keys = node.keys().asSequence().toList()
            for (key in keys) {
                if (out.size >= maxLeaves) return
                walkLeaves(node.get(key), "$path.$key", out, maxLeaves, maxDepth, depth + 1)
            }
        }
        is JSONArray -> {
            for (i in 0 until node.length()) {
                if (out.size >= maxLeaves) return
                walkLeaves(node.get(i), "$path.$i", out, maxLeaves, maxDepth, depth + 1)
            }
        }
        JSONObject.NULL -> Unit // null — не датчик
        is String -> if (node.isNotEmpty()) out.add(JsonLeaf(path, node))
        is Number -> out.add(JsonLeaf(path, node.toString()))
        is Boolean -> out.add(JsonLeaf(path, node.toString()))
        // остальное (теоретически не бывает у org.json) — пропускаем
        else -> Unit
    }
}
