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
import kotlin.math.min

/**
 * Простой линейный график (замена AndroidPlot из narmon-client).
 * Хранит последние N точек, рисует линию с заливкой и опциональную
 * вертикальную шкалу «min / max» — без неё график невозможно прочитать.
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val points = ArrayDeque<Float>()
    var maxPoints: Int = 50
        set(value) {
            field = value.coerceIn(5, 500)
            trim()
            invalidate()
        }

    /** Цвет линии задаётся панелью (как topic color в IoT MQTT Panel) */
    var lineColor: Int = DEFAULT_COLOR
        set(value) {
            field = value
            linePaint.color = value
            fillPaint.color = withAlpha(value, FILL_ALPHA)
            gridPaint.color = withAlpha(value, GRID_ALPHA)
            scalePaint.color = withAlpha(value, TEXT_ALPHA)
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

    fun addValue(value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        points.addLast(value)
        trim()
        invalidate()
    }

    fun clear() {
        points.clear()
        invalidate()
    }

    private fun trim() {
        while (points.size > maxPoints) points.removeFirst()
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
        if (points.size < 2) return

        var minV = points.min()
        var maxV = points.max()
        if (abs(maxV - minV) < 1e-6f) {
            minV -= 1f
            maxV += 1f
        }
        val range = maxV - minV
        val step = w / (points.size - 1)
        val topPad = if (showScale) 10f * density else 4f
        val usableH = h - topPad - 4f * density

        path.reset()
        fillPath.reset()
        var firstX = gutter
        points.forEachIndexed { i, v ->
            val x = gutter + i * step
            val y = h - (v - minV) / range * usableH - 4f * density
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
                firstX = x
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(gutter + (points.size - 1) * step, h)
        fillPath.lineTo(firstX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

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
    }
}
