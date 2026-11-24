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
    /** Мультиграфик: общий блок «легенда + время» (одна плашка).
     *  Одиночный график не используется — там время своей плашкой. */
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

    /** Тёмный ли полноэкранный: следует теме приложения (светлая тема —
     *  светлый фон и тёмные тексты, тёмная — как было) */
    private val darkTheme = ru.mqtt.panel.ui.ThemePrefs.isDark(context)

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    /** Основной и вторичный цвета ТЕКСТА полноэкранного экрана. Диалог создан
     *  на теме Theme_Black_NoTitleBar_Fullscreen (тёмный фон → светлый текст по
     *  умолчанию), а фон мы перекрашиваем под тему приложения: при светлой теме
     *  легенда и время рисовались белым по белому и выглядели «бледными». */
    private val textPrimary = if (darkTheme) 0xFFEAF2E6.toInt() else 0xFF171F17.toInt()
    private val textSecondary = if (darkTheme) 0xFFC3D1BE.toInt() else 0xFF33412F.toInt()

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildView())
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        window?.setBackgroundDrawable(
            ColorDrawable(if (darkTheme) 0xFF101216.toInt() else 0xFFF7F9F5.toInt())
        )
        // Базовый кегль подсказки вырос до 13dp (на карточке читался мелко),
        // поэтому множитель полного экрана снижен 3 → 2.2: итоговый размер
        // остался прежним (~29dp) и не съедает кривую
        chart.tooltipScale = 2.2f
        chart.tooltipDark = darkTheme
        // анатомия классического графика: подписи осей X/Y только на полном
        // экране — на карточках каждая точка высоты на счету
        chart.axisLabels = true
        chart.xLabel = context.getString(R.string.axis_time_label)
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
            textSize = 22f
            setTextColor(textPrimary)
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
        // Накладки ПОВЕРХ графика в верхней части (как legend box в
        // matplotlib): отдельные строки под графиком убраны — место отдаётся
        // кривым. Жесты графика накладки не перехватывают (view-ы не clickable).
        // МУЛЬТИГРАФИК: строки серий и время последней передачи — ОДНА общая
        // плашка (фон и отступы на контейнере), время — её нижняя строка,
        // отдельной плашки времени нет.
        // ОДИНОЧНЫЙ ГРАФИК: легенды нет, поэтому время — своя плашка справа.
        val chartWrap = android.widget.FrameLayout(context)
        chart.layoutParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
        chartWrap.addView(chart)
        val overlayBg = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (darkTheme) 0xD9101216.toInt() else 0xD9F7F9F5.toInt())
            cornerRadius = dp(8).toFloat()
        }
        tvTime.textSize = 13f
        tvTime.setTextColor(textSecondary)
        if (!isMulti) {
            // одиночный график: легенды нет — время отдельной плашкой справа.
            // Мультиграфик: время — нижняя строка канвенной плашки легенды
            // см. buildLegend/applyData)
            tvTime.setPadding(dp(8), dp(2), dp(9), dp(2))
            tvTime.background = overlayBg
            tvTime.layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(10), dp(10), 0) }
            chartWrap.addView(tvTime)
        }
        tvTime.visibility = View.GONE
        root.addView(chartWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        chipsRow.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10), 0, dp(6)) }
        root.addView(chipsRow)
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
        chart.yLabel = panel.unitSuffix
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
        // ФОЛБЭК первого открытия: live-обновлений ещё не было (lastSeriesIdx
        // пуст) — показываем серию с САМОЙ СВЕЖЕЙ точкой, иначе значение и
        // заголовок падали в белый запасной цвет и были не видны на светлой теме
        val effectiveIdx = displayIdx
            ?: panel.series.indices.filter { !multi[it].isNullOrEmpty() }
                .maxByOrNull { multi[it]!!.last().t }
        val lastV: Float? = if (isMulti) {
            effectiveIdx?.let { multi[it]?.lastOrNull()?.v }
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
            effectiveIdx?.let { i ->
                panel.series.getOrNull(i)?.color?.takeIf { it.isNotBlank() }
            }?.let { parseColor(it, Color.WHITE) }
                ?: if (darkTheme) Color.WHITE else 0xFF1B261D.toInt()
        } else {
            dynamic ?: parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
        }
        tvValue.setTextColor(valueColor)
        // подпись НАД значением: у мультиграфика — НОМЕР + последняя серия
        // (нумерация совпадает с подсказкой крестовины и легендой), у
        // одиночного — имя панели; значение — чистое «21.2 °C» крупным кеглем
        val manySeries = isMulti &&
            panel.series.count { it.topic.isNotBlank() || it.label.isNotBlank() } > 1
        val seriesLabel = if (isMulti) effectiveIdx
            ?.let { i ->
                panel.series.getOrNull(i)?.let {
                    (if (manySeries) "${i + 1}. " else "") +
                        it.label.ifBlank { it.topic.substringAfterLast('/') }
                }
            }
        else panel.name.substringAfterLast('·').trim()
        tvTitle.text = seriesLabel ?: panel.name
        // заголовок крупный и ЦВЕТОМ: у мультиграфика — цвет показанной
        // серии, у одиночного — цвет линии графика
        tvTitle.setTextColor(valueColor)
        tvValue.text = if (isMulti) {
            effectiveIdx?.let { i -> multi[i]?.lastOrNull()?.v }
                ?.let { fmtValue(it) + panel.unitSuffix }
                ?: panel.lastValue.takeIf { it.isNotBlank() }?.plus(panel.unitSuffix)
                ?: "—"
        } else if (panel.lastValue.isBlank()) "—" else panel.lastValue + panel.unitSuffix
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val lastT = if (isMulti)
            multi.values.maxOfOrNull { it.lastOrNull()?.t ?: 0L } ?: panel.lastTime
        else single.lastOrNull()?.t ?: panel.lastTime
        val timeText = if (lastT > 0) fmt.format(java.util.Date(lastT)) else ""
        tvTime.text = timeText
        // пустая плашка времени не рисуется
        tvTime.visibility = if (lastT > 0) View.VISIBLE else View.GONE
    }

    private fun buildLegend() {
        // Легенда — КАНВЕННАЯ плашка ChartView в левом верхнем углу поля:
        // рисуется ДО перекрестия, поэтому маркер/линия/пузырь всегда поверх
        // неё. На полном экране значения серий в легенде НЕ дублируются —
        // их показывает крупное число сверху и крестовина. Время последней
        // передачи — нижней строкой той же плашки
        val rows = ArrayList<ChartView.OverlayRow>()
        val shown = panel.series.count { it.topic.isNotBlank() || it.label.isNotBlank() }
        panel.series.forEachIndexed { i, s ->
            if (s.topic.isBlank() && s.label.isBlank()) return@forEachIndexed
            val numPrefix = if (shown > 1) "${i + 1}. " else ""
            val label = numPrefix + s.label.ifBlank { s.topic.substringAfterLast('/').ifBlank { s.topic } }
            rows += ChartView.OverlayRow(parseColor(s.color, ChartView.DEFAULT_COLOR), label, "")
        }
        chart.overlayRows = rows
        chart.overlayTextSize = 16f
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
            setTextColor(textPrimary)
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
        // Активный чип — сплошная заливка акцентом (в светлой теме тёмно-
        // зелёная с белым текстом, в тёмной — светло-зелёная с тёмным):
        // только жирность на бледных плашках читалась плохо.
        val onBg = if (darkTheme) 0xFF9DD697.toInt() else 0xFF386A36.toInt()
        val onFg = if (darkTheme) 0xFF003900.toInt() else 0xFFFFFFFF.toInt()
        val offBg = if (darkTheme) 0x40BBCBB3 else 0x2E54634F
        val offFg = if (darkTheme) 0xFFC3D1BE.toInt() else 0xFF3A4A36.toInt()
        for (i in 0 until n) {
            val chip = chipsRow.getChildAt(i) as TextView
            val active = currentPeriod == periods[i]
            chip.alpha = 1f
            chip.setTypeface(chip.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            chip.setTextColor(if (active) onFg else offFg)
            (chip.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(if (active) onBg else offBg)
        }
    }
}
