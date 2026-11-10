package ru.mqtt.panel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Горизонтальная полоса прогресса: заливка слева направо по доле значения.
 * Свой View вместо framework-ProgressBar — системный drawable не растягивается
 * по высоте и полоса выглядит тонкой.
 */
class HorizontalProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** доля заполнения 0..1 */
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** цвет заполненной части (динамический цвет по значению) */
    var fillColor: Int = Color.parseColor("#4CAF50")
        set(value) {
            field = value
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33888888
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val density = resources.displayMetrics.density
    private val corner = 5f * density
    private val inset = 4f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, corner, corner, trackPaint)
        val fillW = (w - inset * 2) * max(fraction, 0f)
        if (fillW > 0f) {
            // даже при доле ~0% рисуем «бубышку» шириной с высоту полосы —
            // иначе маленькие значения выглядят как пустая полоса
            val drawn = max(fillW, h - inset * 2)
            canvas.drawRoundRect(inset, inset, inset + drawn, h - inset, corner, corner, fillPaint)
        }
    }
}
