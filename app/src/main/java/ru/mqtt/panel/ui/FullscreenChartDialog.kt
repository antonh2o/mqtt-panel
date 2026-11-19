package ru.mqtt.panel.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import ru.mqtt.panel.R
import ru.mqtt.panel.data.ColorRange
import ru.mqtt.panel.data.Panel
import ru.mqtt.panel.data.WidgetType

/**
 * Полноэкранный просмотр графика/мультиграфика — открывается двойным тапом
 * по карточке. Чипы периода 1d/1w/1m/1y показываются, если история уже
 * загружена; переключение периода возвращает данные через DashboardActivity.
 */
class FullscreenChartDialog(
    context: Context,
    initialPanel: Panel,
    private val hasHistory: Boolean,
    single: List<ChartView.TimePoint>,
    private val multi: Map<Int, List<ChartView.TimePoint>>,
    private val onPeriodSelected: (String) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private var panel: Panel = initialPanel
    private val chart = ChartView(context)
    private val tvTitle = TextView(context)
    private val tvValue = TextView(context)
    private val tvTime = TextView(context)
    private val chipsRow = LinearLayout(context)
    private val legendRow = LinearLayout(context)
    private var currentPeriod: String = initialPanel.historyPeriod ?: "day"
    private var lastSeriesIdx: Int? = null
    private var resetChip: TextView? = null

    /** Серия, показанная крупным значением, и момент последнего переключения:
     *  live-обновления приходят на каждую серию подряд — без троттлинга
     *  подпись/значение/цвет мигали несколько раз в секунду */
    private var shownSeriesIdx: Int? = null
    private var seriesSwitchAt: Long = 0L

    /** Карточка, для которой открыт диалог — по ней фильтруются live-обновления */
    val panelId: Long get() = panel.id

    private val periods = listOf("hour", "day", "week", "month", "year")
    private val labels = listOf(
        R.string.chip_hour, R.string.chip_day, R.string.chip_week,
        R.string.chip_month, R.string.chip_year
    )
    private val isMulti = panel.type == WidgetType.MULTI_CHART

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildView())
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawable(ColorDrawable(0xFF101216.toInt()))
        // ×4 оказалась слишком крупной — подсказка перекрывала график;
        // ×3 читается и не съедает кривую (маркеры растут как корень масштаба)
        chart.tooltipScale = 3f
        chart.zoomEnabled = true
        chart.onZoomChanged = { zoomed ->
            resetChip?.visibility = if (zoomed) View.VISIBLE else View.GONE
        }
        buildChips()
        setupSwipe()
        applyData(single, multi)
    }

    /**
     * Свайп по графику влево/вправо = следующий/предыдущий период — то же,
     * что тап по чипу. Обрабатывается внутри ChartView, где одновременно
     * работает показ значения под пальцем.
     */
    private fun setupSwipe() {
        chart.onPeriodSwipe = { step ->
            if (hasHistory) {
                val idx = periods.indexOf(currentPeriod)
                periods.getOrNull(idx + step)?.let { onPeriodSelected(it) }
            }
        }
    }

    private fun buildView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(14))
        }
        root.addView(tvTitle.apply {
            // подпись над значением: у мультиграфика — последняя серия,
            // у одиночного — имя панели; крупное значение ниже — чистое число
            textSize = 16f
            setTextColor(0xFFB0BEC5.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        tvValue.apply {
            // В 4 раза крупнее прежних 36sp: на большом экране значение — главный
            // элемент. Длинные значения («750.41») автоуменьшаются до 72sp,
            // чтобы не обрезаться по ширине.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 144f)
            maxLines = 1
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
            // у мультиграфика потолок ниже (120sp): над значением живёт
            // строка-подпись «2. Улица», композиция читается компактнее
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 72, if (isMulti) 120 else 144, 4, TypedValue.COMPLEX_UNIT_SP
            )
        }
        root.addView(tvValue)
        chart.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        root.addView(chart)
        root.addView(legendRow)
        chipsRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10), 0, dp(6)) }
        root.addView(chipsRow)
        tvTime.apply {
            textSize = 12f
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(tvTime)
        return root
    }

    /** Обновление точек и значений (вызывает DashboardActivity после загрузки/транслирования) */
    fun updateData(
        panel: Panel,
        lastSeriesIdx: Int?,
        single: List<ChartView.TimePoint>,
        multi: Map<Int, List<ChartView.TimePoint>>
    ) {
        this.panel = panel
        lastSeriesIdx?.let { this.lastSeriesIdx = it }
        applyData(single, multi)
    }

    /** Подсветка активного периода после переключения */
    fun setPeriod(period: String) {
        currentPeriod = period
        // новый период — новый диапазон: прежний пинч-масштаб не имеет смысла,
        // а крестовина от старого диапазона показывала бы чужое время
        chart.resetZoom()
        chart.clearTouchHighlight()
        highlightChips()
    }

    private fun applyData(single: List<ChartView.TimePoint>, multi: Map<Int, List<ChartView.TimePoint>>) {
        chart.maxPoints = maxOf(
            panel.chartPoints,
            if (isMulti) (multi.values.maxOfOrNull { it.size } ?: 0) else single.size,
            200
        )
        chart.showScale = panel.chartShowScale
        chart.unitSuffix = panel.unitSuffix
        // Динамический цвет линии по значению — только у ОДИНОЧНОГО графика.
        // У мультиграфика линии всегда цветами своих серий (персональные цвета
        // из редактора; динамическая раскраска здесь не применяется)
        if (!isMulti && panel.colorMin.isNotBlank() && panel.colorMid.isNotBlank() && panel.colorMax.isNotBlank()) {
            chart.valueColorizer = { v ->
                ColorRange.resolve(
                    panel.colorMin, panel.colorMid, panel.colorMax,
                    panel.sliderMin, panel.sliderMax, v.toDouble()
                ) ?: parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
            }
        } else {
            chart.valueColorizer = null
        }
        if (isMulti) {
            // Series в ChartView создаются только setMultiSeries — без неё
            // setMultiPoints молча отбрасывал все точки (индекс вне пустого
            // списка) и полноэкранный график был ПУСТЫМ.
            chart.setMultiSeries(panel.series.map { parseColor(it.color, ChartView.DEFAULT_COLOR) })
            // атомарная замена точек — без промежуточной очистки (иначе «скачет»)
            panel.series.indices.forEach { i ->
                chart.setMultiPoints(i, multi[i].orEmpty())
            }
            buildLegend()
        } else {
            chart.lineColor = parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
            chart.setPoints(single)
        }
        // большое значение: как на карточке — динамический цвет по диапазону
        // (min→середина→макс от последнего значения), иначе цвет последней
        // серии мультиграфика / цвет линии одиночного графика
        // Троттлинг переключения показанной серии: новая принимается не чаще
        // раза в 3 секунды, между сменами значение и подпись стабильны
        val now = System.currentTimeMillis()
        val displayIdx = if (shownSeriesIdx == null || shownSeriesIdx == lastSeriesIdx ||
            now - seriesSwitchAt >= 3000L) {
            if (shownSeriesIdx != lastSeriesIdx) {
                shownSeriesIdx = lastSeriesIdx
                seriesSwitchAt = now
            }
            lastSeriesIdx
        } else shownSeriesIdx
        val lastV: Float? = if (isMulti) {
            displayIdx?.let { multi[it]?.lastOrNull()?.v }
                ?: multi.values.lastOrNull()?.lastOrNull()?.v
        } else single.lastOrNull()?.v
        val dynamic = lastV?.let { v ->
            ColorRange.resolve(
                panel.colorMin, panel.colorMid, panel.colorMax,
                panel.sliderMin, panel.sliderMax, v.toDouble()
            )
        }
        val valueColor = if (isMulti) {
            // цвет показанной серии (динамическая раскраска — не для мультиграфика)
            displayIdx?.let { i ->
                panel.series.getOrNull(i)?.color?.takeIf { it.isNotBlank() }
            }?.let { parseColor(it, Color.WHITE) } ?: Color.WHITE
        } else {
            dynamic ?: parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
        }
        tvValue.setTextColor(valueColor)
        // подпись НАД значением: у мультиграфика — НОМЕР + последняя серия
        // (нумерация совпадает с подсказкой крестовины и легендой), у
        // одиночного — имя панели; значение — чистое «21.2 °C» крупным кеглем
        val manySeries = isMulti &&
            panel.series.count { it.topic.isNotBlank() || it.label.isNotBlank() } > 1
        val seriesLabel = if (isMulti) displayIdx
            ?.let { i ->
                panel.series.getOrNull(i)?.let {
                    (if (manySeries) "${i + 1}. " else "") +
                        it.label.ifBlank { it.topic.substringAfterLast('/') }
                }
            }
        else panel.name.substringAfterLast('·').trim()
        tvTitle.text = seriesLabel ?: panel.name
        tvValue.text = if (isMulti) {
            displayIdx?.let { i -> multi[i]?.lastOrNull()?.v }
                ?.let { fmtValue(it) + panel.unitSuffix }
                ?: panel.lastValue.takeIf { it.isNotBlank() }?.plus(panel.unitSuffix)
                ?: "—"
        } else if (panel.lastValue.isBlank()) "—" else panel.lastValue + panel.unitSuffix
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val lastT = if (isMulti)
            multi.values.maxOfOrNull { it.lastOrNull()?.t ?: 0L } ?: panel.lastTime
        else single.lastOrNull()?.t ?: panel.lastTime
        tvTime.text = if (lastT > 0) fmt.format(java.util.Date(lastT)) else ""
    }

    private fun buildLegend() {
        legendRow.removeAllViews()
        legendRow.orientation = LinearLayout.VERTICAL
        panel.series.forEachIndexed { i, s ->
            if (s.topic.isBlank() && s.label.isBlank()) return@forEachIndexed
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    marginEnd = dp(8)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(parseColor(s.color, ChartView.DEFAULT_COLOR))
                }
            }
            row.addView(dot)
            row.addView(TextView(context).apply {
                // номер серии — соответствие подсказке крестовины («1. …»)
                val many = panel.series.count { it.topic.isNotBlank() || it.label.isNotBlank() } > 1
                text = (if (many) "${i + 1}. " else "") +
                    s.label.ifBlank { s.topic.substringAfterLast('/').ifBlank { s.topic } }
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            multi[i]?.lastOrNull()?.let { p ->
                row.addView(TextView(context).apply {
                    text = "  " + fmtValue(p.v) + panel.unitSuffix
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(parseColor(s.color, ChartView.DEFAULT_COLOR))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(10) }
                })
            }
            legendRow.addView(row)
        }
    }

    private fun fmtValue(v: Float): String {
        val s = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s.endsWith(".")) s + "0" else s
    }

    private fun parseColor(hex: String, fallback: Int): Int = try {
        Color.parseColor(hex.trim())
    } catch (e: Exception) {
        fallback
    }

    private fun buildChips() {
        chipsRow.removeAllViews()
        chipsRow.visibility = if (hasHistory) View.VISIBLE else View.GONE
        labels.forEachIndexed { i, label ->
            val chip = TextView(context).apply {
                text = context.getString(label)
                textSize = 18f
                gravity = Gravity.CENTER
                maxLines = 1
                minHeight = dp(48)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(0x339AA0A6)
                }
                // равные доли ширины: крупные чипы удобно нажимать пальцем
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                setOnClickListener {
                    periods.getOrNull(i)?.let { period ->
                        if (period != currentPeriod) onPeriodSelected(period)
                    }
                }
            }
            chipsRow.addView(chip)
        }
        // Кнопка снятия пинч-масштаба: появляется только когда график приближен.
        // Растянуть обратно можно и пальцами, но кнопка делает это очевидным.
        resetChip = TextView(context).apply {
            text = context.getString(R.string.fullscreen_zoom_reset)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x44E0A030)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            setOnClickListener { chart.resetZoom() }
        }
        chipsRow.addView(resetChip)
        highlightChips()
    }

    private fun highlightChips() {
        // последний ребёнок — кнопка сброса, у неё нет «активного» периода
        val n = minOf(chipsRow.childCount, periods.size)
        for (i in 0 until n) {
            val chip = chipsRow.getChildAt(i) as TextView
            val active = currentPeriod == periods[i]
            chip.alpha = if (active) 1f else 0.45f
            chip.setTypeface(chip.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
