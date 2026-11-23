package ru.mqtt.panel.data

/**
 * Синтетическая история демо-графиков: чипы периодов (час/день/нед/мес/год)
 * работают на демо-дашборде без Народмона и Grafana. Генератор детерминированный
 * (одинаковый seed → одинаковая кривая), последняя точка всегда = now со
 * значением base — живые точки продолжают кривую без скачка.
 * Чистые функции — без Android-зависимостей, покрываются JVM-тестами.
 */
object DemoHistory {

    data class Pt(val t: Long, val v: Float)

    /** Параметры кривой на период: шаг точек, размах, число волн */
    private data class Shape(val stepMs: Long, val amp: Float, val waves: Double)

    private fun shape(period: String): Shape = when (period) {
        "hour" -> Shape(60_000L, 0.35f, 3.0)          // 60 точек, минута шаг
        "month" -> Shape(3 * 3600_000L, 3.0f, 5.0)     // ~240 точек, 3 часа
        "year" -> Shape(24 * 3600_000L, 8.0f, 6.0)     // 365 точек, сутки
        "week" -> Shape(30 * 60_000L, 2.0f, 7.0)       // ~336 точек, 30 минут
        else -> Shape(10 * 60_000L, 1.2f, 2.0)         // day: 144 точки, 10 минут
    }

    /**
     * Точки истории за период, заканчивающиеся моментом nowMs значением base.
     * noise — детерминированная рябь по индексу и seed (0..amp*0.18).
     */
    fun points(period: String, nowMs: Long, base: Float, seed: Int): List<Pt> {
        val (stepMs, amp, waves) = shape(period)
        val spanMs = when (period) {
            "hour" -> 3600_000L
            "week" -> 7L * 24 * 3600_000L
            "month" -> 30L * 24 * 3600_000L
            "year" -> 365L * 24 * 3600_000L
            else -> 24 * 3600_000L
        }
        val n = (spanMs / stepMs).toInt().coerceIn(24, 500)
        val phase = (seed % 17) * 0.37
        val t0 = nowMs - spanMs
        return (0..n).map { i ->
            val k = i.toDouble() / n
            val t = t0 + (spanMs * i / n)
            // огибающая sin(π·k): на обоих концах кривая точно в base —
            // прошлая точка начинается от прошлого значения, последняя
            // бесшовно продолжается живыми точками издателя
            val env = Math.sin(Math.PI * k)
            val wave = amp * Math.sin(2 * Math.PI * waves * k + phase)
            val ripple = amp * 0.18 * Math.sin(i * 1.7 + seed * 0.9)
            Pt(t, (base + (wave + ripple) * env).toFloat())
        }
    }
}
