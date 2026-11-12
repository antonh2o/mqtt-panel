package ru.mqtt.panel.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import ru.mqtt.panel.data.Panel
import ru.mqtt.panel.data.WidgetType

/**
 * Полноэкранный просмотр графика/мультиграфика — открывается двойным тапом
 * по карточке. Чипы периода 1d/1w/1m/1y показываются, если история уже
 * загружена; переключение периода возвращает данные через DashboardActivity.
 */
class FullscreenChartDialog(
    context: Context,
    private val panel: Panel,
    private val hasHistory: Boolean,
    single: List<ChartView.TimePoint>,
    private val multi: Map<Int, List<ChartView.TimePoint>>,
    private val onPeriodSelected: (String) -> Unit
) : Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val chart = ChartView(context)
    private val tvValue = TextView(context)
    private val tvTime = TextView(context)
    private val chipsRow = LinearLayout(context)
    private val legendRow = LinearLayout(context)
    private var currentPeriod: String = panel.historyPeriod ?: "day"
    private var lastSeriesIdx: Int? = null

    /** Карточка, для которой открыт диалог — по ней фильтруются live-обновления */
    val panelId: Long get() = panel.id

    private val periods = listOf("day", "week", "month", "year")
    private val labels = listOf("1d", "1w", "1m", "1y")
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
        buildChips()
        applyData(single, multi)
    }

    private fun buildView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(14))
        }
        root.addView(TextView(context).apply {
            text = panel.name
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        tvValue.apply {
            textSize = 36f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
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
        lastSeriesIdx?.let { this.lastSeriesIdx = it }
        applyData(single, multi)
    }

    /** Подсветка активного периода после переключения */
    fun setPeriod(period: String) {
        currentPeriod = period
        highlightChips()
    }

    private fun applyData(single: List<ChartView.TimePoint>, multi: Map<Int, List<ChartView.TimePoint>>) {
        chart.maxPoints = maxOf(
            panel.chartPoints,
            if (isMulti) (multi.values.maxOfOrNull { it.size } ?: 0) else single.size,
            200
        )
        chart.showScale = panel.chartShowScale
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
        // большое значение: цвет последней серии у мультиграфика, цвет линии у графика
        val valueColor = if (isMulti) {
            lastSeriesIdx?.let { i ->
                panel.series.getOrNull(i)?.color?.takeIf { it.isNotBlank() }
            }?.let { parseColor(it, Color.WHITE) } ?: Color.WHITE
        } else {
            parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
        }
        tvValue.setTextColor(valueColor)
        tvValue.text = if (panel.lastValue.isBlank()) "—" else panel.lastValue + panel.unitSuffix
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
                text = s.label.ifBlank { s.topic.substringAfterLast('/').ifBlank { s.topic } }
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
                text = label
                textSize = 14f
                setPadding(dp(14), dp(5), dp(14), dp(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(0x339AA0A6)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
                setOnClickListener {
                    periods.getOrNull(i)?.let { period ->
                        if (period != currentPeriod) onPeriodSelected(period)
                    }
                }
            }
            chipsRow.addView(chip)
        }
        highlightChips()
    }

    private fun highlightChips() {
        for (i in 0 until chipsRow.childCount) {
            val chip = chipsRow.getChildAt(i) as TextView
            val active = currentPeriod == periods[i]
            chip.alpha = if (active) 1f else 0.45f
            chip.setTypeface(chip.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
