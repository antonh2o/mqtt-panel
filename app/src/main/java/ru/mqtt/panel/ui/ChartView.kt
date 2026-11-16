package ru.mqtt.panel.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Линейный график (замена AndroidPlot из narmon-client).
 * Точки хранятся с временем передачи: линия рисуется по шкале времени,
 * каждая точка измерения — кружок, снизу подписи времени.
 *
 * Два режима:
 *  - однолинейный (addValue/lineColor) — как раньше;
 *  - многолинейный (setMultiSeries/addMultiTimePoint) — серии MULTI_CHART-панели,
 *    масштаб общий по всем линиям, заливка не рисуется.
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Точка измерения: время передачи (мс) и значение */
    data class TimePoint(val t: Long, val v: Float)

    /** Линия графика: свой цвет и свой буфер точек */
    private class Series(val color: Int) {
        val points = ArrayDeque<TimePoint>()
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

    /**
     * Динамическая раскраска линии по значению (диапазон мин→середина→макс
     * панели): каждый сегмент и каждая точка красятся цветом своего значения.
     * null — линия рисуется одним цветом (lineColor / цвет серии).
     */
    var valueColorizer: ((Float) -> Int)? = null

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = DEFAULT_COLOR
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DEFAULT_COLOR
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = withAlpha(DEFAULT_COLOR, GRID_ALPHA)
    }

    /** Пунктир для сетки (как в референсе Народмон): короткое тире + пробел */
    private val dash = android.graphics.DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = withAlpha(DEFAULT_COLOR, FILL_ALPHA)
    }

    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = withAlpha(DEFAULT_COLOR, TEXT_ALPHA)
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * density
        color = withAlpha(NEUTRAL_COLOR, 100)
        textAlign = Paint.Align.CENTER
    }

    private val path = Path()
    private val fillPath = Path()

    // --- подсказка под пальцем (крестовина) ---
    private var touchT: Long? = null
    private var layoutGutter = 0f
    private var layoutW = 0f
    private var layoutH = 0f
    private var layoutTMin = 0L
    private var layoutSpan = 1L

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = withAlpha(NEUTRAL_COLOR, 70)
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6212124.toInt()
    }

    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        color = Color.WHITE
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /** Сбросить крестовину (например, при смене периода или уходе с графика) */
    fun clearTouchHighlight() {
        if (touchT != null) {
            touchT = null
            invalidate()
        }
    }

    /**
     * Горизонтальный свайп по графику: -1 — предыдущий период, +1 — следующий.
     * Живёт здесь, а не в OnTouchListener снаружи: касание по графику должно
     * одновременно показывать значение под пальцем и не мешать вертикальной
     * прокрутке списка (её забирает RecyclerView через ACTION_CANCEL).
     */
    var onPeriodSwipe: ((Int) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (series.none { it.points.size >= 2 } || layoutSpan <= 0L || layoutW <= 0f) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                showAt(event.x)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                // Вертикальное движение — это прокрутка списка: отдаём жест
                // родителю (он пришлёт CANCEL, крестовина уберётся). Иначе
                // держим жест сами, чтобы крестовина не мигала.
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dy > 12f * density && dy > dx * 1.5f) {
                    touchT = null
                    invalidate()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                showAt(event.x)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                touchT = null
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                val minDx = 60f * density
                if (onPeriodSwipe != null && abs(dx) > minDx && abs(dx) > abs(dy) * 1.5f) {
                    onPeriodSwipe?.invoke(if (dx < 0) 1 else -1)
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                touchT = null
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun showAt(px: Float) {
        val clamped = px.coerceIn(layoutGutter, layoutGutter + layoutW)
        touchT = layoutTMin + ((clamped - layoutGutter) / layoutW * layoutSpan).toLong()
        invalidate()
    }

    // --- однолинейный режим ---

    fun addValue(value: Float) {
        addTimePoint(System.currentTimeMillis(), value)
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
        addMultiTimePoint(index, System.currentTimeMillis(), value)
    }

    /** Точка с явным временем передачи (для истории из кэша и платформенных time) */
    /** Атомарно заменить все точки первой серии (полноэкранный просмотр) */
    fun setPoints(points: List<TimePoint>) {
        if (series.size != 1) {
            series.clear()
            series.add(Series(lineColor))
        }
        series[0].points.clear()
        points.forEach { p ->
            if (!p.v.isNaN() && !p.v.isInfinite()) series[0].points.addLast(p)
        }
        trim(series[0])
        invalidate()
    }

    /** Атомарно заменить точки одной серии мультиграфика */
    fun setMultiPoints(index: Int, points: List<TimePoint>) {
        if (index !in series.indices) return
        series[index].points.clear()
        points.forEach { p ->
            if (!p.v.isNaN() && !p.v.isInfinite()) series[index].points.addLast(p)
        }
        trim(series[index])
        invalidate()
    }

    fun addTimePoint(t: Long, value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        if (series.size != 1) {
            series.clear()
            series.add(Series(lineColor))
        }
        addPoint(series[0], TimePoint(t, value))
    }

    fun addMultiTimePoint(index: Int, t: Long, value: Float) {
        if (value.isNaN() || value.isInfinite()) return
        if (index !in series.indices) return
        addPoint(series[index], TimePoint(t, value))
    }

    private fun addPoint(s: Series, point: TimePoint) {
        s.points.addLast(point)
        trim(s)
        invalidate()
    }

    private fun trim(s: Series) {
        while (s.points.size > maxPoints) s.points.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gutter = if (showScale) scalePaint.measureText("-0000.00") + 4f * density else 0f
        val timeLabelsH = 17f * density
        val w = width - gutter
        val h = height - timeLabelsH
        if (w <= 0f) return

        val drawable = series.filter { it.points.size >= 2 }
        if (drawable.isEmpty()) {
            // без данных — каркас из пунктирных линий: карточка не «пустеет»
            gridPaint.pathEffect = dash
            for (i in 1..3) {
                val gy = h * i / 4f
                canvas.drawLine(gutter, gy, gutter + w, gy, gridPaint)
            }
            for (i in 1..3) {
                val gx = gutter + w * i / 4f
                canvas.drawLine(gx, 0f, gx, h, gridPaint)
            }
            gridPaint.pathEffect = null
            return
        }

        // общий масштаб по значениям и по времени — линии не разъезжаются
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var tMin = Long.MAX_VALUE
        var tMax = Long.MIN_VALUE
        drawable.forEach { s -> s.points.forEach { p ->
            if (p.v < minV) minV = p.v
            if (p.v > maxV) maxV = p.v
            if (p.t < tMin) tMin = p.t
            if (p.t > tMax) tMax = p.t
        } }
        if (abs(maxV - minV) < 1e-6f) {
            minV -= 1f
            maxV += 1f
        }
        if (tMax == tMin) tMax = tMin + 1

        // «Красивые» границы шкалы: шаг 1/2/5 × 10^n — сетка и подписи
        // попадают на ровные значения (как в референсе Народмон: 2/0/-2/-4)
        val rawStep = (maxV - minV) / 3f
        val mag = 10.0.pow(floor(ln(rawStep) / ln(10.0)))
        val norm = rawStep / mag
        val niceMult = when {
            norm <= 1.0 -> 1.0
            norm <= 2.0 -> 2.0
            norm <= 5.0 -> 5.0
            else -> 10.0
        }
        val step = (niceMult * mag).toFloat()
        val niceMin = floor(minV / step) * step
        val niceMax = ceil(maxV / step) * step
        val range = niceMax - niceMin
        val topPad = if (showScale) 10f * density else 4f
        val usableH = h - topPad - 4f * density

        fun x(t: Long) = gutter + (t - tMin).toFloat() / (tMax - tMin) * w
        fun y(v: Float) = h - (v - niceMin) / range * usableH - 4f * density

        // Сетка: горизонтальные пунктирные линии по «красивым» уровням шкалы
        // с подписью значения прямо на линии + вертикальные по отметкам времени.
        gridPaint.pathEffect = dash
        var level = niceMin
        while (level <= niceMax + step / 2) {
            val gy = y(level)
            if (gy in 0f..h) {
                canvas.drawLine(gutter, gy, gutter + w, gy, gridPaint)
                scalePaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(format(level), gutter - 3f * density, gy + 3.5f * density, scalePaint)
            }
            level += step
        }

        // вертикальные линии — в тех же позициях, что подписи времени снизу.
        // Число меток ограничено шириной подписи: «14:21:27» при шаге на глаз
        // наезжала на соседнюю, подписи шли внахлёст.
        val span = tMax - tMin
        val labelW = timePaint.measureText(ChartTimeFormat.label(span, tMax))
        val minStepPx = labelW + 8f * density
        val vSteps = if (span > 60_000L && minStepPx > 0f) {
            maxOf(0, (w / minStepPx).toInt() - 1).coerceAtMost(12)
        } else 0
        // Промежуточные метки: без повторов подписи (на неделе соседние метки
        // дают одно и то же «15.11») и без наезда на уже поставленную.
        val midTicks = ArrayList<Pair<Float, Long>>()
        var lastText = ChartTimeFormat.label(span, tMin)
        var lastRight = gutter + labelW + 4f * density
        for (i in 1..vSteps) {
            val gx = gutter + i * w / (vSteps + 1)
            val t = tMin + i * span / (vSteps + 1)
            val text = ChartTimeFormat.label(span, t)
            if (text == lastText) continue
            if (gx - labelW / 2 < lastRight) continue
            midTicks += gx to t
            lastText = text
            lastRight = gx + labelW / 2 + 4f * density
        }
        // правая крайняя подпись приоритетнее последней промежуточной:
        // снимаем её, если она повторяет текст правой или наезжает на неё
        val rightText = ChartTimeFormat.label(span, tMax)
        while (midTicks.isNotEmpty()) {
            val (gx, t) = midTicks.last()
            val clash = gutter + w - labelW < gx + labelW / 2 + 4f * density ||
                ChartTimeFormat.label(span, t) == rightText
            if (!clash) break
            midTicks.removeAt(midTicks.lastIndex)
        }
        midTicks.forEach { (gx, _) -> canvas.drawLine(gx, 0f, gx, h, gridPaint) }
        gridPaint.pathEffect = null
        scalePaint.textAlign = Paint.Align.LEFT
        drawable.forEach { s ->
            // Линия по сегментам: с динамической раскраской каждый сегмент и каждая
            // точка красятся цветом своего значения; иначе — одним цветом серии.
            if (valueColorizer != null) {
                var prev = s.points.first()
                for (i in 1 until s.points.size) {
                    val cur = s.points[i]
                    val color = valueColorizer!!((prev.v + cur.v) / 2)
                    linePaint.color = color
                    path.reset()
                    path.moveTo(x(prev.t), y(prev.v))
                    path.lineTo(x(cur.t), y(cur.v))
                    canvas.drawPath(path, linePaint)
                    // заливка только у одиночной линии
                    if (series.size == 1) {
                        fillPath.reset()
                        fillPath.moveTo(x(prev.t), y(prev.v))
                        fillPath.lineTo(x(cur.t), y(cur.v))
                        fillPath.lineTo(x(cur.t), h)
                        fillPath.lineTo(x(prev.t), h)
                        fillPath.close()
                        fillPaint.color = withAlpha(color, FILL_ALPHA)
                        canvas.drawPath(fillPath, fillPaint)
                    }
                    prev = cur
                }
            } else {
                path.reset()
                var firstX = 0f
                var lastX = 0f
                s.points.forEachIndexed { i, p ->
                    val px = x(p.t)
                    val py = y(p.v)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    if (i == 0) firstX = px
                    lastX = px
                }
                linePaint.color = s.color
                // заливка только у одиночной линии: у нескольких она мутит график
                if (series.size == 1) {
                    fillPath.reset()
                    fillPath.moveTo(firstX, h)
                    s.points.forEach { p -> fillPath.lineTo(x(p.t), y(p.v)) }
                    fillPath.lineTo(lastX, h)
                    fillPath.close()
                    fillPaint.color = withAlpha(s.color, FILL_ALPHA)
                    canvas.drawPath(fillPath, fillPaint)
                }
                canvas.drawPath(path, linePaint)
            }
            // точки измерений — цветом своего значения
            s.points.forEach { p ->
                dotPaint.color = valueColorizer?.invoke(p.v) ?: s.color
                canvas.drawCircle(x(p.t), y(p.v), 2.2f * density, dotPaint)
            }
        }

        // шкала времени: крайние метки + промежуточные с тем же шагом, что у сетки
        val labelY = h + timeLabelsH - 3f * density
        timePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(ChartTimeFormat.label(span, tMin), gutter, labelY, timePaint)
        timePaint.textAlign = Paint.Align.CENTER
        midTicks.forEach { (gx, t) ->
            canvas.drawText(ChartTimeFormat.label(span, t), gx, labelY, timePaint)
        }
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(ChartTimeFormat.label(span, tMax), gutter + w, labelY, timePaint)

        // запоминаем геометрию: по ней переводим касание в момент времени
        layoutGutter = gutter
        layoutW = w
        layoutH = h
        layoutTMin = tMin
        layoutSpan = span

        drawTouchHighlight(canvas, gutter, w, h, tMin, span, ::x, ::y)
    }

    /**
     * Крестовина под пальцем: вертикаль по времени, точки серий на этом моменте
     * и пузырь «время + значения». Значения интерполируются по соседним точкам,
     * поэтому подсказка плавно едет за пальцем, а не прыгает по измерениям.
     */
    private fun drawTouchHighlight(
        canvas: Canvas,
        gutter: Float,
        w: Float,
        h: Float,
        tMin: Long,
        span: Long,
        x: (Long) -> Float,
        y: (Float) -> Float
    ) {
        val t = touchT ?: return
        if (span <= 0L || w <= 0f) return
        val px = x(t)
        if (px < gutter - 1f || px > gutter + w + 1f) return
        crossPaint.color = withAlpha(NEUTRAL_COLOR, 85)
        canvas.drawLine(px, 0f, px, h, crossPaint)

        val values = ArrayList<Pair<Int, Float>>() // цвет, значение
        series.forEach { s ->
            val v = interpolateAt(s.points, t) ?: return@forEach
            val color = valueColorizer?.invoke(v) ?: s.color
            values += color to v
            val py = y(v)
            markerPaint.color = 0xFF000000.toInt()
            canvas.drawCircle(px, py, 5.2f * density, markerPaint)
            markerPaint.color = color
            canvas.drawCircle(px, py, 4f * density, markerPaint)
            markerPaint.color = Color.WHITE
            canvas.drawCircle(px, py, 1.6f * density, markerPaint)
        }
        if (values.isEmpty()) return

        // подпись: время + значения (у мультиграфика — по строке на серию)
        val timeText = ChartTimeFormat.tooltipLabel(t)
        val lines = ArrayList<String>()
        lines += timeText
        values.forEachIndexed { i, (_, v) ->
            val prefix = if (values.size > 1) "${i + 1}. " else ""
            lines += prefix + formatTooltip(v)
        }
        val padH = 5f * density
        val padV = 4f * density
        val lineH = bubbleTextPaint.textSize + 2f * density
        val boxW = lines.maxOf { bubbleTextPaint.measureText(it) } + padH * 2
        val boxH = lineH * lines.size + padV * 2
        // пузырь уезжает от края: слева от пальца, если справа места нет
        var bx = px + 8f * density
        if (bx + boxW > gutter + w) bx = px - 8f * density - boxW
        bx = bx.coerceIn(gutter, max(gutter, gutter + w - boxW))
        val by = 2f * density
        val r = 4f * density
        canvas.drawRoundRect(bx, by, bx + boxW, by + boxH, r, r, bubblePaint)
        bubbleTextPaint.textAlign = Paint.Align.LEFT
        lines.forEachIndexed { i, text ->
            canvas.drawText(text, bx + padH, by + padV + lineH * (i + 1) - 3f * density, bubbleTextPaint)
        }
    }

    /** Значение серии в момент t: линейная интерполяция между соседними точками */
    private fun interpolateAt(points: ArrayDeque<TimePoint>, t: Long): Float? {
        if (points.isEmpty()) return null
        var prev: TimePoint? = null
        for (p in points) {
            if (p.t == t) return p.v
            if (p.t > t) {
                val a = prev ?: return p.v
                val dt = (p.t - a.t).toFloat()
                if (dt <= 0f) return p.v
                val k = (t - a.t) / dt
                return a.v + (p.v - a.v) * k
            }
            prev = p
        }
        return prev?.v
    }

    private fun formatTooltip(v: Float): String = when {
        abs(v) >= 1000f -> String.format(java.util.Locale.getDefault(), "%.1f", v)
        abs(v) >= 100f -> String.format(java.util.Locale.getDefault(), "%.1f", v)
        abs(v) >= 1f -> String.format(java.util.Locale.getDefault(), "%.2f", v)
        else -> String.format(java.util.Locale.getDefault(), "%.3f", v)
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
