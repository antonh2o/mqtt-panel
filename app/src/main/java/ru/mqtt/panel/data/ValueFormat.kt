package ru.mqtt.panel.data

import java.util.Locale

/**
 * Показ числового значения с множителем и десятичной точностью
 * (аналог «Фактор»/«Точность» в IoT MQTT Panel).
 *
 * Применяется только к числам: нечисловые payload (ON/OFF, текст, JSON-хвосты)
 * проходят как есть — иначе кнопки и статусы теряли бы своё значение.
 * Чистый JVM-код, чтобы покрывать тестами без Android.
 */
object ValueFormat {

    /** Максимум знаков после запятой, который имеет смысл показывать */
    const val MAX_DECIMALS = 6

    /**
     * @param value сырое значение из MQTT (может быть «0,4 A», «22.4567»)
     * @param factor множитель; 1.0 (или 0.0 из старых конфигов) — не применять
     * @param decimals сколько знаков после запятой оставить; null — как есть
     * @return отформатированное значение; исходная строка, если это не число
     */
    fun apply(value: String, factor: Double, decimals: Int?): String {
        val mult = if (factor == 0.0) 1.0 else factor
        val hasFactor = mult != 1.0
        if (!hasFactor && decimals == null) return value
        val num = parseNumber(value) ?: return value
        val result = num * mult
        return if (decimals != null) {
            String.format(Locale.US, "%.${decimals.coerceIn(0, MAX_DECIMALS)}f", result)
        } else {
            // без явной точности: целые без хвоста, дробные — два знака
            // (как formatValue слайдера в WidgetAdapter)
            if (result == result.toLong().toDouble()) result.toLong().toString()
            else String.format(Locale.US, "%.2f", result)
        }
    }

    /**
     * Числовое значение с применённым множителем — для расчётов, а не показа:
     * прогресс, точки графиков и шкала должны жить в тех же единицах, что и
     * подпись значения, иначе «Фактор» рассогласует карточку (значение «1»,
     * а полоса — от 0.1).
     *
     * @return null, если значение не число
     */
    fun scaled(value: String, factor: Double): Double? {
        val mult = if (factor == 0.0) 1.0 else factor
        return parseNumber(value)?.let { it * mult }
    }
}
