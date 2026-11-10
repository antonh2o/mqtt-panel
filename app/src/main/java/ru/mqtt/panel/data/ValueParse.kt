package ru.mqtt.panel.data

/**
 * Извлечь число из «сырого» значения топика/датчика: «0,4» (запятая),
 * «0.4 A» (суффикс единицы), «t=+21.5». Раньше строгий toFloatOrNull()
 * не разбирал такие строки — прогресс и динамический цвет молча
 * оставались пустыми. null — числа в строке нет.
 * Чистый JVM (без Android) для юнит-тестов.
 */
fun parseNumber(raw: String?): Double? {
    if (raw == null) return null
    val s = raw.trim().replace(',', '.')
    val m = Regex("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?").find(s)
        ?: return null
    return m.value.toDoubleOrNull()
}
