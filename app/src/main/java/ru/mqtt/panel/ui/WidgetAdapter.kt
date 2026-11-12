package ru.mqtt.panel.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import ru.mqtt.panel.R
import ru.mqtt.panel.data.AppRepository
import ru.mqtt.panel.data.ColorRange
import ru.mqtt.panel.data.Panel
import ru.mqtt.panel.data.WidgetType
import ru.mqtt.panel.data.parseNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Адаптер виджетов на экране дашборда.
 * Каждый тип панели рендерится своим layout (как панели в IoT MQTT Panel).
 */
class WidgetAdapter(
    private val onEdit: (Panel) -> Unit,
    private val onDelete: (Panel) -> Unit,
    private val onDuplicate: (Panel) -> Unit,
    private val onPublish: (Panel, String) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit = {},
    /** смена периода истории на narodmon-карточке графика (чипы 1d/1w/1m/1y) */
    var onChartPeriodSelected: ((Panel, String) -> Unit)? = null,
    /** Подключения с включённой Grafana — у их графиков видны чипы периодов */
    var grafanaConnectionIds: Set<Long> = emptySet(),
    /** Двойной тап по карточке графика/мультиграфика — полноэкранный просмотр */
    var onChartDoubleClick: ((Panel) -> Unit)? = null
) : RecyclerView.Adapter<WidgetAdapter.BaseHolder>() {

    private val items = mutableListOf<Panel>()

    /** panelId -> индекс серии, чьё значение пришло последним (цвет большого значения) */
    private val multiLastSeries = mutableMapOf<Long, Int>()

    /** Резервные цвета кружков легенды, если у серии нечитаемый hex */
    private val legendPalette = listOf(
        "#4CAF50", "#2196F3", "#FF9800", "#F44336", "#9C27B0", "#00BCD4",
        "#FFEB3B", "#8BC34A", "#E91E63", "#3F51B5", "#795548", "#607D8B"
    )

    /** Режим перемещения: долгое нажатие начинает перетаскивание, виджеты неактивны */
    var moveMode = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
    /** История значений для графиков: panelId → точки (время, значение) */
    private val chartHistory = HashMap<Long, MutableList<ChartView.TimePoint>>()

    /** История серий мультиграфиков («panelId:индексСерии» → точки) */
    private val chartMultiHistory = HashMap<String, MutableList<ChartView.TimePoint>>()

    /** Время последнего обновления в строку: сегодня — только время, иначе с датой */
    private fun formatTime(t: Long): String =
        (if (Date().time - t < 24 * 3600_000L) timeFormat else dateTimeFormat).format(Date(t))

    fun submit(list: List<Panel>) {
        items.clear()
        items.addAll(list)
        val ids = list.map { it.id }.toSet()
        chartHistory.keys.retainAll(ids)
        chartMultiHistory.keys.retainAll { it.substringBefore(':').toLongOrNull() in ids }
        multiLastSeries.keys.retainAll(ids)
        notifyDataSetChanged()
    }

    /** Перезагрузка состава панелей с сохранением уже полученных значений */
    fun submitKeepingValues(list: List<Panel>) {
        val values = items.associate { it.id to it.lastValue }
        submit(list.map { it.copy(lastValue = values[it.id] ?: "") })
    }

    /** Загрузка панелей другого подключения — старые значения не относятся к ним */
    fun submitFresh(list: List<Panel>) = submit(list.map { it.copy(lastValue = "") })

    /** Перемещение карточки в режиме перемещения (drag & drop) */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    /** Текущий порядок — для сохранения sortOrder */
    fun snapshotOrder(): List<Panel> = items.toList()

    fun panelAt(position: Int): Panel? = items.getOrNull(position)

    fun updateValue(panelId: Long, value: String, time: Long = System.currentTimeMillis()) {
        val index = items.indexOfFirst { it.id == panelId }
        if (index < 0) return
        applyValue(index, value, time)
        notifyItemChanged(index)
    }

    /** Обновить все панели, подписанные на тему (с учётом MQTT-масок +/#) */
    fun updateValueByTopic(topic: String, value: String, time: Long = System.currentTimeMillis()) {
        var changed = false
        items.forEachIndexed { index, panel ->
            if (panel.type == WidgetType.MULTI_CHART) {
                // значение уходит в те серии, чья тема совпала
                panel.series.forEachIndexed { i, s ->
                    if (s.topic.isNotBlank() && topicMatches(s.topic, topic)) {
                        applyMultiValue(panel, i, value, time)
                        changed = true
                    }
                }
            } else if (panel.topicSubscribe.isNotBlank() && topicMatches(panel.topicSubscribe, topic)) {
                applyValue(index, value, time)
                changed = true
            }
        }
        if (changed) notifyDataSetChanged()
    }

    private fun applyValue(index: Int, value: String, time: Long = System.currentTimeMillis()) {
        val panel = items[index]
        panel.lastValue = value
        if (time > panel.lastTime) panel.lastTime = time
        if (panel.type == WidgetType.CHART) {
            parseNumber(value)?.let { d ->
                val v = d.toFloat()
                val history = chartHistory.getOrPut(panel.id) { mutableListOf() }
                history.add(ChartView.TimePoint(time, v))
                // История из Grafana/Narodmon (до 500 точек) не должна вытесняться
                // живыми точками: потолок — большее из chartPoints и текущего размера
                while (history.size > maxOf(panel.chartPoints, minOf(history.size, 500))) {
                    history.removeFirst()
                }
            }
        }
    }

    /** Значение для серии мультиграфика (panel.lastValue — последнее из любых её тем) */
    private fun applyMultiValue(panel: Panel, seriesIndex: Int, value: String, time: Long = System.currentTimeMillis()) {
        panel.lastValue = value
        multiLastSeries[panel.id] = seriesIndex
        if (time > panel.lastTime) panel.lastTime = time
        parseNumber(value)?.let { d ->
            val v = d.toFloat()
            val history = chartMultiHistory.getOrPut("${panel.id}:$seriesIndex") { mutableListOf() }
            history.add(ChartView.TimePoint(time, v))
            while (history.size > maxOf(panel.chartPoints, minOf(history.size, 500))) {
                history.removeAt(0)
            }
        }
    }

    /** Очистить точки графика (на время загрузки нового периода истории) */
    fun resetChartHistory(panelId: Long) {
        chartHistory[panelId]?.clear()
        val idx = items.indexOfFirst { it.id == panelId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** Дописать историю из Народмона ПЕРЕД live-точками (без дублей по времени) */
    fun prependHistory(panelId: Long, pts: List<ChartView.TimePoint>, cap: Int = 500) {
        if (pts.isEmpty()) return
        val merged = (pts + (chartHistory[panelId] ?: mutableListOf()))
            .distinctBy { it.t }
            .sortedBy { it.t }
            .toMutableList()
        while (merged.size > cap) merged.removeFirst()
        chartHistory[panelId] = merged
        val idx = items.indexOfFirst { it.id == panelId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** Точки одиночного графика (копия) для полноэкранного просмотра */
    fun chartPointsOf(panelId: Long): List<ChartView.TimePoint> =
        chartHistory[panelId]?.toList().orEmpty()

    /** Точки серий мультиграфика (копия): индекс серии -> точки */
    fun multiPointsOf(panelId: Long): Map<Int, List<ChartView.TimePoint>> =
        chartMultiHistory.keys
            .filter { it.substringBefore(':').toLongOrNull() == panelId }
            .associate { key ->
                key.substringAfter(':').toInt() to (chartMultiHistory[key]?.toList().orEmpty())
            }

    /** Данные карточки по id (для полноэкранного просмотра) */
    fun panelById(panelId: Long): Panel? = items.firstOrNull { it.id == panelId }

    /** id карточки, чья тема (или тема серии) совпала с topic — для live-обновления диалога */
    fun panelIdForTopic(topic: String): Long? = items.firstOrNull { p ->
        if (p.type == WidgetType.MULTI_CHART) {
            p.series.any { it.topic.isNotBlank() && topicMatches(it.topic, topic) }
        } else {
            p.topicSubscribe.isNotBlank() && topicMatches(p.topicSubscribe, topic)
        }
    }?.id

    /** Есть ли уже загруженная история у карточки */
    fun hasHistory(panelId: Long, isMulti: Boolean): Boolean =
        if (isMulti) chartMultiHistory.keys.any {
            it.substringBefore(':').toLongOrNull() == panelId && !chartMultiHistory[it].isNullOrEmpty()
        } else !chartHistory[panelId].isNullOrEmpty()

    /** Индекс последней обновившейся серии мультиграфика (для цвета значения) */
    fun lastSeriesIndexOf(panelId: Long): Int? = multiLastSeries[panelId]

    /** Очистить кэш истории графика — смена периода карточки (1d↔1w↔1m↔1y) */
    fun clearHistory(panelId: Long) {
        chartHistory.remove(panelId)
        val idx = items.indexOfFirst { it.id == panelId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** Очистить кэш истории всех серий мультиграфика (смена периода) */
    fun clearMultiHistory(panelId: Long) {
        chartMultiHistory.keys.removeAll { it.substringBefore(':').toLongOrNull() == panelId }
        val idx = items.indexOfFirst { it.id == panelId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** Дописать историю серии мультиграфика из Grafana ПЕРЕД live-точками */
    fun prependMultiHistory(
        panelId: Long, seriesIndex: Int, pts: List<ChartView.TimePoint>, cap: Int = 500
    ) {
        if (pts.isEmpty()) return
        val key = "$panelId:$seriesIndex"
        val merged = (pts + (chartMultiHistory[key] ?: mutableListOf()))
            .distinctBy { it.t }
            .sortedBy { it.t }
            .toMutableList()
        while (merged.size > cap) merged.removeFirst()
        chartMultiHistory[key] = merged
        val idx = items.indexOfFirst { it.id == panelId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    /** Обновить метаданные панели (период и настройки) с сохранением значений */
    fun updatePanelMeta(updated: Panel) {
        val idx = items.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            items[idx] = updated.copy(lastValue = items[idx].lastValue, lastTime = items[idx].lastTime)
            notifyItemChanged(idx)
        }
    }

    /**
     * Снимок последних значений и историй графиков — для сохранения в
     * runtime-файл (мгновенный показ при следующем открытии дашборда).
     */
    fun snapshotState(): Map<String, AppRepository.RuntimeValue> {
        val result = HashMap<String, AppRepository.RuntimeValue>()
        items.forEach { panel ->
            result["${panel.id}"] = AppRepository.RuntimeValue(
                last = panel.lastValue,
                time = panel.lastTime,
                history = chartHistory[panel.id]?.map { listOf(it.t.toDouble(), it.v.toDouble()) } ?: emptyList()
            )
            if (panel.type == WidgetType.MULTI_CHART) {
                panel.series.indices.forEach { i ->
                    chartMultiHistory["${panel.id}:$i"]?.let { pts ->
                        result["${panel.id}:$i"] = AppRepository.RuntimeValue(
                            last = pts.lastOrNull()?.v?.toString() ?: "",
                            time = pts.lastOrNull()?.t ?: 0L,
                            history = pts.map { listOf(it.t.toDouble(), it.v.toDouble()) }
                        )
                    }
                }
            }
        }
        return result
    }

    /** Восстановление кэша: мгновенный показ последних значений и графиков */
    fun applyCached(cached: Map<String, AppRepository.RuntimeValue>) {
        if (cached.isEmpty()) return
        items.forEach { panel ->
            cached["${panel.id}"]?.let { rt ->
                if (rt.last.isNotBlank()) panel.lastValue = rt.last
                if (rt.time > panel.lastTime) panel.lastTime = rt.time
                if (rt.history.isNotEmpty()) {
                    chartHistory[panel.id] = rt.history
                        .map { ChartView.TimePoint(it[0].toLong(), it[1].toFloat()) }
                        .toMutableList()
                }
            }
            if (panel.type == WidgetType.MULTI_CHART) {
                panel.series.indices.forEach { i ->
                    cached["${panel.id}:$i"]?.let { rt ->
                        if (rt.history.isNotEmpty()) {
                            chartMultiHistory["${panel.id}:$i"] = rt.history
                                .map { ChartView.TimePoint(it[0].toLong(), it[1].toFloat()) }
                                .toMutableList()
                        }
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    /** Проверка совпадения темы с учётом подстановочных масок + и # */
    private fun topicMatches(filter: String, topic: String): Boolean {
        if (filter == topic) return true
        val fParts = filter.split('/')
        val tParts = topic.split('/')
        for ((i, f) in fParts.withIndex()) {
            if (f == "#") return true
            if (i >= tParts.size) return false
            if (f != "+" && f != tParts[i]) return false
        }
        return fParts.size == tParts.size
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = items[position].type.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseHolder {
        val inflater = LayoutInflater.from(parent.context)
        val layout = when (WidgetType.entries[viewType]) {
            WidgetType.VALUE -> R.layout.item_widget_value
            WidgetType.SWITCH -> R.layout.item_widget_switch
            WidgetType.BUTTON -> R.layout.item_widget_button
            WidgetType.SLIDER -> R.layout.item_widget_slider
            WidgetType.LED -> R.layout.item_widget_led
            WidgetType.TEXT -> R.layout.item_widget_text
            WidgetType.CHART -> R.layout.item_widget_chart
            WidgetType.MULTI_CHART -> R.layout.item_widget_multichart
            WidgetType.PROGRESS_V -> R.layout.item_widget_progress_v
            WidgetType.PROGRESS_H -> R.layout.item_widget_progress_h
        }
        return BaseHolder(inflater.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: BaseHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class BaseHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val btnMenu: View? = view.findViewById(R.id.btn_menu)
        private val tvTopic: TextView? = view.findViewById(R.id.tv_topic)
        private val tvValue: TextView? = view.findViewById(R.id.tv_value)
        private val tvTime: TextView? = view.findViewById(R.id.tv_time)
        private val switch: MaterialSwitch? = view.findViewById(R.id.switch_widget)
        private val slider: Slider? = view.findViewById(R.id.slider_widget)
        private val button: MaterialButton? = view.findViewById(R.id.btn_press)
        private val ledDot: View? = view.findViewById(R.id.led_dot)
        private val chart: ChartView? = view.findViewById(R.id.chart_view)
        private val legend: android.widget.LinearLayout? = view.findViewById(R.id.chart_legend)
        private val periodChips: android.widget.LinearLayout? = view.findViewById(R.id.period_chips)
        private val verticalProgress: VerticalProgressView? = view.findViewById(R.id.progress_vertical)
        private val horizontalProgress: HorizontalProgressView? = view.findViewById(R.id.progress_horizontal)

        fun bind(panel: Panel) {
            tvName.text = panel.name
            tvTopic?.text = panel.topicSubscribe.ifBlank {
                panel.topicPublish.ifBlank { itemView.context.getString(R.string.no_topic) }
            }
            // время последней передачи данных (если включено и данные приходили)
            if (tvTime != null) {
                if (panel.showTime != false && panel.lastTime > 0) {
                    tvTime.visibility = View.VISIBLE
                    tvTime.text = formatTime(panel.lastTime)
                } else {
                    tvTime.visibility = View.GONE
                }
            }

            when (panel.type) {
                WidgetType.VALUE -> {
                    tvValue?.text = withUnit(panel, panel.lastValue)
                    applyDynamicColor(tvValue, panel)
                }
                WidgetType.TEXT -> {
                    tvValue?.text = withUnit(panel, panel.lastValue)
                    applyDynamicColor(tvValue, panel)
                }
                WidgetType.SWITCH -> bindSwitch(panel)
                WidgetType.BUTTON -> bindButton(panel)
                WidgetType.SLIDER -> bindSlider(panel)
                WidgetType.LED -> bindLed(panel)
                WidgetType.CHART -> bindChart(panel)
                WidgetType.MULTI_CHART -> bindMultiChart(panel)
                WidgetType.PROGRESS_V -> bindProgress(null, verticalProgress, null, panel)
                WidgetType.PROGRESS_H -> bindProgress(null, null, horizontalProgress, panel)
            }

            // Долгое нажатие: в режиме перемещения — старт drag & drop,
            // иначе — контекстное меню панели (редактировать/удалить)
            itemView.setOnLongClickListener {
                if (moveMode) onDragStart(this) else showMenu(it, panel)
                true
            }

            // Двойной тап по графику/мультиграфику — полноэкранный просмотр;
            // onTouch возвращает false, чтобы клики и долгие нажатия работали как раньше
            if (panel.type == WidgetType.CHART || panel.type == WidgetType.MULTI_CHART) {
                val doubleTap = android.view.GestureDetector(
                    itemView.context,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                            if (!moveMode) onChartDoubleClick?.invoke(panel)
                            return true
                        }
                    }
                )
                itemView.setOnTouchListener { _, event ->
                    doubleTap.onTouchEvent(event)
                    false
                }
            }

            // Троеточие в углу плашки — меню редактировать/удалить
            btnMenu?.setOnClickListener { anchor -> showMenu(anchor, panel) }

            // В режиме перемещения органы управления карточки неактивны,
            // чтобы случайный тап не опубликовал команду
            switch?.isEnabled = !moveMode
            button?.isEnabled = !moveMode
            slider?.isEnabled = !moveMode
            itemView.alpha = if (moveMode) 0.85f else 1f
        }

        /** Меню панели: редактировать / клонировать / удалить */
        private fun showMenu(anchor: View, panel: Panel) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(anchor.context)
                .setTitle(panel.name)
                .setItems(
                    arrayOf(
                        anchor.context.getString(R.string.edit_panel),
                        anchor.context.getString(R.string.duplicate_panel),
                        anchor.context.getString(R.string.delete_panel)
                    )
                ) { _, which ->
                    when (which) {
                        0 -> onEdit(panel)
                        1 -> onDuplicate(panel)
                        2 -> onDelete(panel)
                    }
                }
                .show()
        }

    /** Значение с единицей измерения: «24.2 °C», «53%» (как в narodmon) */
    private fun withUnit(panel: Panel, value: String): String {
        if (value.isBlank()) return "—"
        val unit = panel.unitSuffix
        if (unit.isBlank()) return value
        // °C и % пишутся впритык к числу, остальные единицы — через пробел
        val sep = if (unit.startsWith("°") || unit.startsWith("%")) "" else " "
        return value + sep + unit
    }

    private var defaultValueColor: Int? = null

    /**
     * Динамический цвет значения по диапазону Мин/Макс (ColorRange):
     * минимум → цвет минимума, середина → средний, максимум → максимальный,
     * промежуточные значения — плавный переход. Цвета не заданы — обычный.
     */
    private fun applyDynamicColor(tv: TextView?, panel: Panel) {
        if (tv == null) return
        if (defaultValueColor == null) defaultValueColor = tv.currentTextColor
        val v = parseNumber(panel.lastValue)
        val color = if (v != null) ColorRange.resolve(
            panel.colorMin, panel.colorMid, panel.colorMax,
            panel.sliderMin, panel.sliderMax, v
        ) else null
        tv.setTextColor(color ?: defaultValueColor!!)
    }

        /** Полоса прогресса (горизонтальная или вертикальная): доля значения в
         *  диапазоне Мин/Макс панели, динамическая раскраска по значению. */
        private fun bindProgress(
            bar: android.widget.ProgressBar?,
            vertical: VerticalProgressView?,
            horizontal: HorizontalProgressView?,
            panel: Panel
        ) {
            if (bar == null && vertical == null && horizontal == null) return
            val min = panel.sliderMin
            val max = if (panel.sliderMax > min) panel.sliderMax else min + 100
            val v = parseNumber(panel.lastValue)
            bar?.max = 10000
            if (v == null) {
                bar?.progress = 0
                vertical?.belowRange = false
                horizontal?.belowRange = false
            } else {
                // ниже Мин — «бубышка» цветом минимума (полная полоса
                // читалась бы как «много»); выше Макс — полная в цвете
                // максимума; в диапазоне — доля значения, цвет по значению
                val below = v < min
                val f = if (below) 0.0 else ((v - min) / (max - min)).coerceIn(0.0, 1.0)
                bar?.progress = (f * 10000).toInt()
                vertical?.fraction = f.toFloat()
                vertical?.belowRange = below
                horizontal?.fraction = f.toFloat()
                horizontal?.belowRange = below
                ColorRange.resolve(
                    panel.colorMin, panel.colorMid, panel.colorMax,
                    min, max, if (below) min else v
                )?.let { c ->
                    bar?.progressTintList = android.content.res.ColorStateList.valueOf(c)
                    vertical?.fillColor = c
                    horizontal?.fillColor = c
                }
            }
            tvValue?.text = withUnit(panel, panel.lastValue)
        }

        private fun bindSwitch(panel: Panel) {
            val sw = switch ?: return
            val on = panel.lastValue.equals(panel.onPayload, ignoreCase = true) ||
                panel.lastValue.equals("true", ignoreCase = true) || panel.lastValue == "1"
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = on
            sw.setOnCheckedChangeListener { _, checked ->
                val payload = if (checked) panel.onPayload else panel.offPayload
                if (panel.topicPublish.isNotBlank()) onPublish(panel, payload)
            }
        }

        private fun bindButton(panel: Panel) {
            val btn = button ?: return
            btn.text = panel.buttonPayload.ifBlank { itemView.context.getString(R.string.widget_button) }
            btn.setOnClickListener {
                if (panel.topicPublish.isNotBlank()) onPublish(panel, panel.buttonPayload)
            }
        }

        private fun bindSlider(panel: Panel) {
            val sl = slider ?: return
            sl.valueFrom = panel.sliderMin.toFloat()
            sl.valueTo = max(panel.sliderMin.toFloat() + 1f, panel.sliderMax.toFloat())
            sl.stepSize = 0f
            val current = parseNumber(panel.lastValue)?.toFloat()?.coerceIn(sl.valueFrom, sl.valueTo) ?: sl.valueFrom
            sl.clearOnChangeListeners()
            sl.clearOnSliderTouchListeners()
            sl.value = current
            tvValue?.text = withUnit(panel, formatValue(current))
            var userChanged = false
            sl.addOnChangeListener { _, value, fromUser ->
                tvValue?.text = withUnit(panel, formatValue(value))
                if (fromUser) userChanged = true
            }
            sl.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    if (userChanged && panel.topicPublish.isNotBlank()) {
                        onPublish(panel, formatValue(slider.value))
                    }
                    userChanged = false
                }
            })
        }

        private fun bindLed(panel: Panel) {
            val dot = ledDot ?: return
            val value = panel.lastValue
            val isOn = value.equals(panel.onPayload, true) || value.equals("1") ||
                value.equals("on", true) || value.equals("true", true)
            val isOff = value.isNotBlank() && !isOn
            val color = try {
                if (isOn) Color.parseColor(panel.ledOnColor)
                else if (isOff) Color.parseColor(panel.ledOffColor)
                else Color.parseColor("#9E9E9E")
            } catch (e: IllegalArgumentException) {
                Color.parseColor("#9E9E9E")
            }
            val bg = dot.background.mutate()
            if (bg is android.graphics.drawable.GradientDrawable) bg.setColor(color)
            tvValue?.text = value.ifBlank { "—" }
        }

        private fun bindChart(panel: Panel) {
            val cv = chart ?: return
            val history = chartHistory[panel.id]
            // график Народмона: история за сутки длиннее лимита точек — не обрезать
            cv.maxPoints = max(panel.chartPoints, history?.size ?: 0)
            cv.lineColor = parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
            cv.showScale = panel.chartShowScale
            // динамическая раскраска линии по значению (мин→середина→макс),
            // если у панели заданы все три цвета диапазона
            cv.valueColorizer =
                if (panel.colorMin.isNotBlank() && panel.colorMid.isNotBlank() && panel.colorMax.isNotBlank())
                    { v ->
                        ColorRange.resolve(
                            panel.colorMin, panel.colorMid, panel.colorMax,
                            panel.sliderMin, panel.sliderMax, v.toDouble()
                        ) ?: parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
                    }
                else null
            cv.clear()
            history?.forEach { cv.addTimePoint(it.t, it.v) }
            if (history.isNullOrEmpty()) {
                parseNumber(panel.lastValue)?.let { d ->
                    cv.addTimePoint(max(panel.lastTime, 0L).takeIf { it > 0 } ?: System.currentTimeMillis(), d.toFloat())
                }
            }
            tvValue?.text = withUnit(panel, panel.lastValue)
            bindPeriodChips(panel)
        }

        /** Чипы периода «1d 1w 1m 1y» — narodmon-графики и MQTT-графики с историей из Grafana */
        private fun bindPeriodChips(panel: Panel) {
            val row = periodChips ?: return
            val grafanaHistory = panel.connectionId in grafanaConnectionIds
            if (panel.source != "narodmon" && !grafanaHistory) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            val periods = listOf("day", "week", "month", "year")
            if (row.childCount == 0) {
                val d = row.context.resources.displayMetrics.density
                listOf("1d", "1w", "1m", "1y").forEach { label ->
                    val chip = android.widget.TextView(row.context).apply {
                        text = label
                        textSize = 13f
                        setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 14 * d
                            setColor(0x22888888)
                        }
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = (4 * d).toInt() }
                    }
                    row.addView(chip)
                }
            }
            // Слушатель — на КАЖДОМ бинде: ViewHolder рециклится, и чип,
            // созданный один раз, навсегда запомнил бы ПЕРВУЮ панель — тапы
            // меняли период чужой карточки, а текущая не реагировала.
            for (i in 0 until row.childCount) {
                val chip = row.getChildAt(i) as android.widget.TextView
                chip.setOnClickListener {
                    periods.getOrNull(i)?.let { period ->
                        onChartPeriodSelected?.invoke(panel, period)
                    }
                }
                val active = panel.historyPeriod == periods[i]
                chip.alpha = if (active) 1f else 0.45f
                chip.setTypeface(chip.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }

        /** Мультиграфик: линии по темам серий + легенда «цвет — подпись».
         *  Если у панели заданы цвета диапазона (мин/середина/макс) — все линии
         *  красятся по значению вместо цветов серий. */
        private fun bindMultiChart(panel: Panel) {
            val cv = chart ?: return
            cv.maxPoints = panel.chartPoints
            cv.setMultiSeries(panel.series.map { parseColor(it.color, ChartView.DEFAULT_COLOR) })
            cv.showScale = panel.chartShowScale
            cv.valueColorizer =
                if (panel.colorMin.isNotBlank() && panel.colorMid.isNotBlank() && panel.colorMax.isNotBlank())
                    { v ->
                        ColorRange.resolve(
                            panel.colorMin, panel.colorMid, panel.colorMax,
                            panel.sliderMin, panel.sliderMax, v.toDouble()
                        ) ?: parseColor(panel.chartColor, ChartView.DEFAULT_COLOR)
                    }
                else null
            cv.clear()
            panel.series.indices.forEach { i ->
                chartMultiHistory["${panel.id}:$i"]?.forEach { p -> cv.addMultiTimePoint(i, p.t, p.v) }
            }
            tvValue?.text = withUnit(panel, panel.lastValue)
            // большое значение красится цветом той серии, чьё значение пришло последним
            tvValue?.let { tv ->
                val idx = multiLastSeries[panel.id]
                val seriesColor = idx?.let { i ->
                    panel.series.getOrNull(i)?.color?.takeIf { it.isNotBlank() }
                }
                tv.setTextColor(
                    seriesColor?.let { parseColor(it, ChartView.DEFAULT_COLOR) }
                        ?:ChartView.DEFAULT_COLOR
                )
            }
            // Тема карточки не одна — список тем виден в легенде
            tvTopic?.visibility = View.GONE
            buildLegend(panel)
            bindPeriodChips(panel)
        }

        /** Значение для легенды: float-хвосты (242.89999…) округляются до 2 знаков */
        private fun fmtLegend(v: Float): String {
            val s = String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
            return if (s.isEmpty() || s.endsWith(".")) s + "0" else s
        }

        private fun buildLegend(panel: Panel) {
            val lg = legend ?: return
            val ctx = lg.context
            val d = ctx.resources.displayMetrics.density
            lg.removeAllViews()
            panel.series.forEachIndexed { i, s ->
                if (s.topic.isBlank() && s.label.isBlank()) return@forEachIndexed
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val dotSize = (12 * d).toInt()
                val dot = View(ctx)
                dot.layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (6 * d).toInt()
                }
                val fallback = parseColor(legendPalette[i % legendPalette.size], ChartView.DEFAULT_COLOR)
                dot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(parseColor(s.color, fallback))
                }
                row.addView(dot)
                val label = android.widget.TextView(ctx).apply {
                    text = s.label.ifBlank { s.topic.substringAfterLast('/').ifBlank { s.topic } }
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(label)
                // значение серии — цветом топика, рядом с подписью
                chartMultiHistory["${panel.id}:$i"]?.lastOrNull()?.let { p ->
                    val tvVal = android.widget.TextView(ctx).apply {
                        text = "  " + fmtLegend(p.v) + panel.unitSuffix
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(parseColor(s.color, fallback))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = (8 * d).toInt() }
                    }
                    row.addView(tvVal)
                }
                lg.addView(row)
            }
        }

        private fun parseColor(hex: String, fallback: Int): Int = try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            fallback
        }

        private fun formatValue(v: Float): String {
            return if (v == v.toLong().toFloat()) v.toLong().toString()
            else String.format(Locale.US, "%.2f", v)
        }
    }
}
