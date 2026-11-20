package ru.mqtt.panel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Круговой прогресс: дуга от 12 часов по часовой стрелке.
 * Свой View вместо framework-ProgressBar — нужен толстый трек и
 * динамический цвет заливки (как «Круговой» прогресс в IoT MQTT Panel).
 */
class CircularProgressView @JvmOverloads constructor(
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

    /** цвет заполненной дуги (динамический цвет по значению) */
    var fillColor: Int = Color.parseColor("#4CAF50")
        set(value) {
            field = value
            fillPaint.color = value
            invalidate()
        }

    /**
     * Значение ниже Мин диапазона: вместо полной дуги рисуется короткая
     * «бубышка» цветом минимума — виден и факт «почти ноль», и цвет
     * «ниже диапазона». Полная дуга читалась бы как «много».
     */
    var belowRange: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33888888
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = fillColor
        strokeCap = Paint.Cap.ROUND
    }

    private val density = resources.displayMetrics.density
    private val stroke = 12f * density
    private val rect = RectF()

    /**
     * Круг всегда квадратный и sized по карточке: высота = ширина, но не больше
     * maxSize. Раньше размер был жёстко 130dp в layout — в карточке 1/3 ширины
     * (~99dp) круг вылезал за границы карточки.
     */
    private val maxSize = 130f * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val side = min(w.toFloat(), maxSize).toInt().coerceAtLeast((40 * density).toInt())
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        trackPaint.strokeWidth = stroke
        fillPaint.strokeWidth = stroke
        val pad = stroke / 2f + 2f * density
        val size = min(w, h) - pad * 2
        if (size <= 0f) return
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        rect.set(left, top, left + size, top + size)
        canvas.drawArc(rect, -90f, 360f, false, trackPaint)
        if (belowRange) {
            // значение ниже Мин — короткая дуга цветом минимума
            val sweep = (360f * (stroke / size)).coerceAtLeast(6f)
            canvas.drawArc(rect, -90f, sweep, false, fillPaint)
            return
        }
        val sweep = 360f * fraction.coerceIn(0f, 1f)
        if (sweep > 0f) {
            // даже при доле ~0% рисуем заметную «бубышку» — иначе маленькие
            // значения выглядят как пустое кольцо
            val drawn = sweep.coerceAtLeast(360f * (stroke / size) * 0.9f)
            canvas.drawArc(rect, -90f, drawn, false, fillPaint)
        }
    }
}
