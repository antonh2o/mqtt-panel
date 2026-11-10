package ru.mqtt.panel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Вертикальная полоса прогресса: заливка снизу вверх по доле значения.
 * Свой View вместо framework-ProgressBar — кастомный progressDrawable
 * в вертикальной ориентации ведёт себя непредсказуемо (indeterminate-крутилка).
 */
class VerticalProgressView @JvmOverloads constructor(
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
            fillPaint.color = value
            invalidate()
        }

    /**
     * Значение ниже Мин диапазона: вместо полного заполнения рисуется
     * «бубышка» цветом минимума — виден и факт «почти ноль», и цвет
     * «ниже диапазона». Полная полоса здесь читалась бы как «много».
     */
    var belowRange: Boolean = false
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
    private val inset = 5f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, corner, corner, trackPaint)
        if (belowRange) {
            // значение ниже Мин — «бубышка» цветом минимума
            val nub = w - inset * 2
            canvas.drawRoundRect(inset, h - inset - nub, w - inset, h - inset, corner, corner, fillPaint)
            return
        }
        val fillH = (h - inset * 2) * max(fraction, 0f)
        if (fillH > 0f) {
            // даже при доле ~0% рисуем «бубышку» высотой с ширину полосы —
            // иначе маленькие значения выглядят как пустая полоса
            val drawn = max(fillH, w - inset * 2)
            canvas.drawRoundRect(inset, h - inset - drawn, w - inset, h - inset, corner, corner, fillPaint)
        }
    }
}
