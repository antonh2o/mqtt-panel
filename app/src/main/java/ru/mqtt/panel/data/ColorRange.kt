package ru.mqtt.panel.data

/**
 * Динамический цвет значения по диапазону: три опорных цвета (минимум,
 * середина, максимум диапазона), промежуточные значения — плавный переход
 * между соседними цветами (линейная интерполяция RGB), как «Color range»
 * в IoT MQTT Panel.
 *
 * Цвета задаются hex-строками («#RRGGBB» / «#AARRGGBB» / «#RGB»).
 * Динамический цвет активен, только если заданы все три цвета.
 */
object ColorRange {

    /** Разобрать hex в ARGB int; null при мусоре */
    fun parse(hex: String): Int? {
        val s = hex.trim()
        if (s.isEmpty()) return null
        val digits = if (s.startsWith("#")) s.substring(1) else s
        return when (digits.length) {
            3 -> {
                val v = digits.toIntOrNull(16) ?: return null
                val r = (v shr 8 and 0xF) * 17
                val g = (v shr 4 and 0xF) * 17
                val b = (v and 0xF) * 17
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> {
                val v = digits.toIntOrNull(16) ?: return null
                (0xFF shl 24) or (v and 0xFFFFFF)
            }
            8 -> digits.toIntOrNull(16)
            else -> null
        }
    }

    /**
     * Цвет для значения value в диапазоне [rangeMin, rangeMax].
     * Половина диапазона — переход мин→средний, вторая — средний→макс.
     * null — динамический цвет не настроен (не все три цвета заданы)
     * или диапазон вырожден (min >= max).
     */
    fun resolve(
        colorMin: String, colorMid: String, colorMax: String,
        rangeMin: Double, rangeMax: Double, value: Double
    ): Int? {
        if (colorMin.isBlank() || colorMid.isBlank() || colorMax.isBlank()) return null
        if (rangeMax <= rangeMin) return null
        val cMin = parse(colorMin) ?: return null
        val cMid = parse(colorMid) ?: return null
        val cMax = parse(colorMax) ?: return null
        val f = ((value - rangeMin) / (rangeMax - rangeMin)).coerceIn(0.0, 1.0)
        return if (f <= 0.5) lerp(cMin, cMid, f * 2) else lerp(cMid, cMax, (f - 0.5) * 2)
    }

    /** Посимвольная интерполяция RGB (альфа берётся у a) */
    fun lerp(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val ca = a shr shift and 0xFF
            val cb = b shr shift and 0xFF
            return (ca + (cb - ca) * k).toInt()
        }
        return (a shr 24 and 0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
