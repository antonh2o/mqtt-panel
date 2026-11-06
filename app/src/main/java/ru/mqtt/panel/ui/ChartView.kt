package ru.mqtt.panel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Линейный график (замена AndroidPlot из narmon-client).
 * Хранит последние N точек, рисует линию с заливкой и опциональную
 * вертикальную шкалу «min / max» — без неё график невозможно прочитать.
 *
 * Два режима:
 *  - однолинейный (addValue/lineColor) — как раньше;
 *  - многолинейный (setMultiSeries/addMultiValue) — серии MULTI_CHART-панели,
 *    масштаб общий по всем линиям, заливка не рисуется.
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Линия графика: свой цвет и свой буфер точек */
    private class Series(val color: Int) {
        val points = ArrayDeque<Float>()
    }

    private val series = mutableListOf<Series>()

    var maxPoints: Int = 50
        set(value) {
            field = value.coerceIn(5, 500)
            series.forEach { trim(it) }
            invalidate()
        }

    /** Цвет линии однолинейного режима (как topic color в IoT MQTT Panel) */
    var lineColor: Int = DEFAULT_COLOR
        set(value) {
            field = value
            linePaint.color = value
            fillPaint.color = withAlpha(value, FILL_ALPHA)
            gridPaint.color = withAlpha(value, GRID_ALPHA)
            scalePaint.color = withAlpha(value, TEXT_ALPHA)
            if (series.size == 1) {
                val old = series[0]
                series[0] = Series(value).also { it.points.addAll(old.points) }
            }
            invalidate()
        }

    /** Показывать подписи min/max по левой оси */
    var showScale: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = DEFAULT_COLOR
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = withAlpha(DEFAULT_COLOR, GRID_ALPHA)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = withAlpha(DEFAULT_COLOR, FILL_ALPHA)
    }

    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        color = withAlpha(DEFAULT_COLOR, TEXT_ALPHA)
    }

    private val path = Path()
    private val fillPath = Path()

    // --- однолинейный режим ---

    fun addValue(value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        if (series.size != 1) {
            series.clear()
            series.add(Series(lineColor))
        }
        addPoint(series[0], value)
    }

    fun clear() {
        series.forEach { it.points.clear() }
        invalidate()
    }

    // --- многолинейный режим ---

    /** Переключение на N серий с заданными цветами; пустой список = однолинейный режим */
    fun setMultiSeries(colors: List<Int>) {
        series.clear()
        if (colors.isEmpty()) {
            series.add(Series(lineColor))
        } else {
            colors.forEach { series.add(Series(it)) }
            // сетка и шкала нейтральные: цвет одной линии ничего не должен выделять
            gridPaint.color = withAlpha(NEUTRAL_COLOR, GRID_ALPHA)
            scalePaint.color = withAlpha(NEUTRAL_COLOR, TEXT_ALPHA)
        }
        invalidate()
    }

    fun addMultiValue(index: Int, value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        if (index !in series.indices) return
        addPoint(series[index], value)
    }

    private fun addPoint(s: Series, value: Float) {
        s.points.addLast(value)
        trim(s)
        invalidate()
    }

    private fun trim(s: Series) {
        while (s.points.size > maxPoints) s.points.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gutter = if (showScale) scalePaint.measureText("000000") + 4f * density else 0f
        val w = width - gutter
        val h = height.toFloat()
        if (w <= 0f) return

        // горизонтальная сетка
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(gutter, y, gutter + w, y, gridPaint)
        }

        val drawable = series.filter { it.points.size >= 2 }
        if (drawable.isEmpty()) return

        // общий масштаб по всем линиям — иначе линии с разными диапазонами не сравнить
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        drawable.forEach { s -> s.points.forEach { v ->
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        } }
        if (abs(maxV - minV) < 1e-6f) {
            minV -= 1f
            maxV += 1f
        }
        val range = maxV - minV
        val topPad = if (showScale) 10f * density else 4f
        val usableH = h - topPad - 4f * density

        drawable.forEach { s ->
            val step = w / (s.points.size - 1)
            path.reset()
            var firstX = gutter
            var lastX = gutter
            s.points.forEachIndexed { i, v ->
                val x = gutter + i * step
                val y = h - (v - minV) / range * usableH - 4f * density
                if (i == 0) {
                    path.moveTo(x, y)
                    firstX = x
                } else {
                    path.lineTo(x, y)
                }
                lastX = x
            }
            linePaint.color = s.color
            // заливка только у одиночной линии: у нескольких она мутит график
            if (series.size == 1) {
                fillPath.reset()
                fillPath.moveTo(firstX, h)
                s.points.forEachIndexed { i, v ->
                    val x = gutter + i * step
                    val y = h - (v - minV) / range * usableH - 4f * density
                    fillPath.lineTo(x, y)
                }
                fillPath.lineTo(lastX, h)
                fillPath.close()
                fillPaint.color = withAlpha(s.color, FILL_ALPHA)
                canvas.drawPath(fillPath, fillPaint)
            }
            canvas.drawPath(path, linePaint)
        }

        if (showScale) {
            canvas.drawText(format(maxV), 0f, topPad, scalePaint)
            canvas.drawText(format(minV), 0f, h - 2f * density, scalePaint)
        }
    }

    /** Компактная подпись оси: целые без дробной части, крупные — через k */
    private fun format(v: Float): String = when {
        abs(v) >= 10000f -> String.format("%.1fk", v / 1000f)
        abs(v) >= 100f -> String.format("%.0f", v)
        abs(v - v.toLong()) < 0.01f -> v.toLong().toString()
        else -> String.format("%.2f", v)
    }

    private fun withAlpha(color: Int, alphaPercent: Int): Int =
        Color.argb(alphaPercent * 255 / 100, Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        const val DEFAULT_COLOR = 0xFF4CAF50.toInt()
        private const val FILL_ALPHA = 18
        private const val GRID_ALPHA = 28
        private const val TEXT_ALPHA = 75
        private const val NEUTRAL_COLOR = 0xFF9E9E9E.toInt()
    }
}
