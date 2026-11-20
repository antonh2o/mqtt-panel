package ru.mqtt.panel.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Вертикальный слайдер (управление, а не индикация — в отличие от
 * [VerticalProgressView]): дорожка, заполнение снизу вверх и круглый ползунок.
 *
 * Почему свой View: Material Slider 1.12 не поддерживает вертикальную
 * ориентацию, а поворот горизонтального на 270° давал толстый трек, растянутый
 * ползунок и «бусины» тиков — на карточке это выглядело как поломка.
 *
 * Значение растёт ВВЕРХ (максимум у верхней кромки) — привычная логика
 * вертикального регулятора.
 */
class VerticalSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var valueFrom: Float = 0f
    var valueTo: Float = 100f
    /** шаг: 0 — непрерывно */
    var step: Float = 0f

    /** текущее значение; всегда приводится к сетке шага и диапазону */
    var value: Float = 0f
        set(v) {
            field = snap(v)
            invalidate()
        }

    /** вызывается при каждом изменении значения пальцем/тапом */
    var onValueChanged: ((Float) -> Unit)? = null
    /** вызывается, когда палец отпущен (публикация в брокер) */
    var onValueCommitted: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33888888
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4CAF50")
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4CAF50")
    }

    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33000000
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    private val density = resources.displayMetrics.density
    private val trackWidth = 8f * density
    private val thumbRadius = 11f * density
    /** поля, чтобы ползунок не обрезался у кромок карточки */
    private val inset = thumbRadius + 2f * density

    /** диапазон дорожки с учётом полей под ползунок */
    private val trackTop: Float get() = inset
    private val trackBottom: Float get() = height - inset

    private fun snap(raw: Float): Float {
        val lo = minOf(valueFrom, valueTo)
        val hi = maxOf(valueFrom, valueTo)
        val clamped = raw.coerceIn(lo, hi)
        if (step <= 0f) return clamped
        val steps = ((clamped - valueFrom) / step).roundToInt()
        return (valueFrom + steps * step).coerceIn(lo, hi)
    }

    /** доля заполнения 0..1 (0 — минимум внизу, 1 — максимум вверху) */
    private fun fraction(): Float {
        val span = valueTo - valueFrom
        if (span == 0f) return 0f
        return ((value - valueFrom) / span).coerceIn(0f, 1f)
    }

    private fun valueAtY(y: Float): Float {
        val top = trackTop
        val bottom = trackBottom
        if (bottom <= top) return valueFrom
        val f = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        return valueFrom + f * (valueTo - valueFrom)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val v = snap(valueAtY(event.y))
                if (v != value) {
                    value = v
                    onValueChanged?.invoke(v)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onValueCommitted?.invoke(value)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val top = trackTop
        val bottom = trackBottom
        if (bottom <= top) return

        val trackLeft = cx - trackWidth / 2f
        val trackRight = cx + trackWidth / 2f
        val radius = trackWidth / 2f
        canvas.drawRoundRect(trackLeft, top, trackRight, bottom, radius, radius, trackPaint)

        val f = fraction()
        val thumbY = bottom - f * (bottom - top)
        // заполнение: от ползунка вниз до минимума
        if (thumbY < bottom) {
            canvas.drawRoundRect(trackLeft, thumbY, trackRight, bottom, radius, radius, fillPaint)
        }

        thumbPaint.color = fillPaint.color
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbPaint)
        canvas.drawCircle(cx, thumbY, thumbRadius, thumbRingPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // ширина — как просит карточка, высота фиксированная (см. layout)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = max(MeasureSpec.getSize(heightMeasureSpec), (inset * 2 + 24f * density).toInt())
        setMeasuredDimension(w, h)
    }
}
