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
import ru.mqtt.panel.data.extractJsonPath
import ru.mqtt.panel.data.WidgetType
import ru.mqtt.panel.data.parseNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

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
    var grafanaConnectionIds: Set<Long> = emptySet()
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

    /** Режим блокировки: только данные — без меню карточек, ручек и времени.
     *  Защищает раскладку от случайных правок (перемещение выключено) */
    var locked = false
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
        val times = items.associate { it.id to it.lastTime }
        submit(list.map {
            it.copy(lastValue = values[it.id] ?: "", lastTime = times[it.id] ?: it.lastTime)
        })
    }

    /** Загрузка панелей другого подключения — старые значения не относятся к ним.
     *  lastTime тоже чистим: иначе сохранённое в конфиге «будущее» время
     *  заблокировало бы приём свежих сообщений (защита от откатов в applyValue) */
    fun submitFresh(list: List<Panel>) = submit(list.map { it.copy(lastValue = "", lastTime = 0) })

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
        // Точечные уведомления вместо notifyDataSetChanged: сообщения приходят
        // несколько раз в секунду, и полный ребинд ВСЕХ карточек давал мигание
        // и срывал жесты (RecyclerView перекладывал элементы под пальцем)
        val changed = LinkedHashSet<Int>()
        items.forEachIndexed { index, panel ->
            if (panel.type == WidgetType.MULTI_CHART) {
                // значение уходит в те серии, чья тема совпала
                panel.series.forEachIndexed { i, s ->
                    if (s.topic.isNotBlank() && topicMatches(s.topic, topic)) {
                        applyMultiValue(panel, i, value, time)
                        changed.add(index)
                    }
                }
            } else if (panel.topicSubscribe.isNotBlank() && topicMatches(panel.topicSubscribe, topic)) {
                applyValue(index, value, time)
                changed.add(index)
            }
        }
        changed.forEach { notifyItemChanged(it) }
    }

    private fun applyValue(index: Int, raw: String, time: Long = System.currentTimeMillis()) {
        val panel = items[index]
        // JSON-payload: извлекаем значение по пути, иначе показываем весь
        // payload (число вытаскивается parseNumber ниже). Извлечение не
        // удалось (payload не JSON / пути нет) — значение не обновляем,
        // чтобы не записать в панель «сырой JSON» вместо последнего числа
        val value = if (panel.jsonPath.isNotBlank()) {
            extractJsonPath(raw, panel.jsonPath) ?: return
        } else raw
        // Устаревшее сообщение не должно перетирать свежее: replay сервиса и
        // runtime-кэш приходят пачкой и раньше откатывали значения назад
        // (карточка показывала данные двухдневной давности при живом статусе).
        // Допускаем равное время — у MQTT время приёма может совпасть.
        if (panel.lastTime > 0 && time < panel.lastTime) return
        panel.lastValue = value
        if (time > panel.lastTime) panel.lastTime = time
        if (panel.type == WidgetType.CHART) {
            // множитель применяется и к точке: шкала/линия должны быть в тех же
            // единицах, что и подпись значения (см. ValueFormat.scaled)
            ru.mqtt.panel.data.ValueFormat.scaled(value, panel.factor)?.let { d ->
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
    /** Сколько мс после прихода показания держать легенду мультиграфика видимой */
    private val LEGEND_AFTER_READING_MS = 3000L

    /** panelId → до какого момента легенда мультиграфика показывается
     *  (последнее показание + 3 с; видимость применяется в бинде) */
    private val multiLegendUntil = mutableMapOf<Long, Long>()

    private fun applyMultiValue(panel: Panel, seriesIndex: Int, raw: String, time: Long = System.currentTimeMillis()) {
        // у серии свой jsonPath (JSON-темы мультиграфика)
        val series = panel.series.getOrNull(seriesIndex)
        val path = series?.jsonPath?.trim().orEmpty()
        val value = if (path.isNotEmpty()) {
            extractJsonPath(raw, path) ?: return
        } else raw
        // У серии свой хронометраж: общий panel.lastTime заблокировал бы
        // вторую линию после прихода свежей точки первой. Сравниваем с
        // последней точкой ИМЕННО этой серии (replay не откатывает график)
        val seriesKey = "${panel.id}:$seriesIndex"
        val lastT = chartMultiHistory[seriesKey]?.lastOrNull()?.t ?: 0L
        if (lastT > 0 && time < lastT) return
        panel.lastValue = value
        multiLastSeries[panel.id] = seriesIndex
        if (time > panel.lastTime) panel.lastTime = time
        // пришло показание: легенду поверх графика держим включённой ещё 3 с
        multiLegendUntil[panel.id] = System.currentTimeMillis() + LEGEND_AFTER_READING_MS
        ru.mqtt.panel.data.ValueFormat.scaled(value, panel.factor)?.let { d ->
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

    /**
     * ЗАМЕСТИТЬ историю одиночного графика (смена периода 1d↔1w↔1m↔1y).
     * prependHistory здесь не годится: старая история забита под потолок (cap),
     * и после слияния с sort+trim вытесняются как раз старые точки нового
     * периода — недельная кривая «сливается» с суточной и визуально ничего
     * не меняется (регрессия после отказа от предочистки в 0.2.58).
     */
    fun replaceHistory(panelId: Long, pts: List<ChartView.TimePoint>, cap: Int = 500) {
        if (pts.isEmpty()) return
        val merged = pts.distinctBy { it.t }.sortedBy { it.t }.toMutableList()
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
    fun panelIdForTopic(topic: String): Long? = panelIdsForTopic(topic).firstOrNull()

    /** ВСЕ карточки, подписанные на тему: одна тема может быть и у VALUE-карточки,
     *  и серией мультиграфика — открытый полноэкранный график должен обновляться
     *  и тогда, когда первым в списке стоит другая карточка */
    fun panelIdsForTopic(topic: String): List<Long> = items.filter { p ->
        if (p.type == WidgetType.MULTI_CHART) {
            p.series.any { it.topic.isNotBlank() && topicMatches(it.topic, topic) }
        } else {
            p.topicSubscribe.isNotBlank() && topicMatches(p.topicSubscribe, topic)
        }
    }.map { it.id }

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
                // Кэш — только заполнение пустого: если значение уже пришло
                // живым сообщением, оно свежее и перетирать его нельзя
                if (panel.lastValue.isBlank() && rt.last.isNotBlank()) panel.lastValue = rt.last
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
            WidgetType.PROGRESS_C -> R.layout.item_widget_progress_c
        }
        return BaseHolder(inflater.inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: BaseHolder, position: Int) {
        // Блокировка = плотная раскладка: внешние поля карточек (4dp) убираются
        // в угоду данным, при разблокировке возвращаются. Поля КРАЙНИЕ у
        // RecyclerView (8dp) остаются — карточки не приклеиваются к экрану
        val d = holder.itemView.resources.displayMetrics.density
        val m = if (locked) 0 else (4 * d).toInt()
        (holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            if (lp.leftMargin != m || lp.topMargin != m) {
                lp.setMargins(m, m, m, m)
                holder.itemView.layoutParams = lp
            }
        }
        // Артефакты скруглений: при нулевых полях стыки соседних карточек
        // дают «ромбики» из фона между четырьмя скруглёнными углами —
        // в блокировке радиус обнуляется и карточки сливаются в ровное
        // полотно. Внутренний отступ 8dp тоже ужмётся до 4dp
        (holder.itemView as? com.google.android.material.card.MaterialCardView)?.let { card ->
            card.radius = if (locked) 0f else 12f * d
            (card.getChildAt(0) as? ViewGroup)?.let { inner ->
                val p = if (locked) (4 * d).toInt() else (8 * d).toInt()
                if (inner.paddingLeft != p) inner.setPadding(p, p, p, p)
            }
        }
        holder.bind(items[position])
    }

    inner class BaseHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tv_name)
        private val dragHandle: View? = view.findViewById(R.id.drag_handle)
        private val btnMenu: View? = view.findViewById(R.id.btn_menu)
        private val tvTopic: TextView? = view.findViewById(R.id.tv_topic)
        private val tvValue: TextView? = view.findViewById(R.id.tv_value)
        private val tvTime: TextView? = view.findViewById(R.id.tv_time)
        private val switch: MaterialSwitch? = view.findViewById(R.id.switch_widget)
        private val slider: Slider? = view.findViewById(R.id.slider_widget)
        private val sliderVerticalView: VerticalSliderView? = view.findViewById(R.id.slider_vertical)
        private val button: MaterialButton? = view.findViewById(R.id.btn_press)
        private val ledDot: View? = view.findViewById(R.id.led_dot)
        private val chart: ChartView? = view.findViewById(R.id.chart_view)
        private val legend: android.widget.LinearLayout? = view.findViewById(R.id.chart_legend)
        /** Отложенное скрытие легенды мультиграфика (3 с после показания) */
        private var legendHideRun: Runnable? = null
        private val periodChips: android.widget.LinearLayout? = view.findViewById(R.id.period_chips)
        private val verticalProgress: VerticalProgressView? = view.findViewById(R.id.progress_vertical)
        private val horizontalProgress: HorizontalProgressView? = view.findViewById(R.id.progress_horizontal)
        private val circularProgress: CircularProgressView? = view.findViewById(R.id.progress_circular)

        /** Повтор публикации кнопки: живёт на holder'е, чтобы ребинд/уход
         *  карточки с экрана гарантированно остановили таймер */
        private var repeatHandler: android.os.Handler? = null
        private var repeatRunnable: Runnable? = null

        private fun stopRepeat() {
            repeatRunnable?.let { repeatHandler?.removeCallbacks(it) }
            repeatHandler = null
            repeatRunnable = null
        }

        fun bind(panel: Panel) {
            // карточка перебиндивается (RecyclerView) — старый повтор не должен жить
            stopRepeat()
            tvName.text = panel.name
            // подписи топиков скрыты — информация дублируется в заголовке/легенде
            tvTopic?.visibility = View.GONE
            tvTopic?.text = panel.topicSubscribe.ifBlank {
                panel.topicPublish.ifBlank { itemView.context.getString(R.string.no_topic) }
            }
            // время последней передачи данных (если включено и данные приходили);
            // в режиме блокировки — только данные, время скрыто
            if (tvTime != null) {
                if (!locked && panel.showTime != false && panel.lastTime > 0) {
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
                WidgetType.PROGRESS_C -> bindProgress(null, null, null, panel, circularProgress)
            }

            // Долгое нажатие: в режиме перемещения — старт drag & drop,
            // иначе — контекстное меню панели (редактировать/удалить).
            // В режиме блокировки меню недоступно: раскладка защищена от правок
            itemView.setOnLongClickListener {
                if (locked) return@setOnLongClickListener false
                if (moveMode) onDragStart(this) else showMenu(it, panel)
                true
            }

            // Троеточие в углу плашки — меню редактировать/удалить.
            // В режиме блокировки кнопки нет вовсе — карточка отдаёт место данным
            btnMenu?.visibility = if (locked) View.GONE else View.VISIBLE
            btnMenu?.setOnClickListener { anchor -> showMenu(anchor, panel) }

            // В режиме перемещения органы управления карточки неактивны,
            // чтобы случайный тап не опубликовал команду. Само состояние режима
            // показано явно: ручка-«ёжик» слева, отступ заголовка, акцентная
            // обводка и лёгкая прозрачность карточки.
            switch?.isEnabled = !moveMode
            button?.isEnabled = !moveMode
            slider?.isEnabled = !moveMode
            applyMoveModeChrome()
        }

        /** Внешние признаки режима перемещения на этой карточке */
        private fun applyMoveModeChrome() {
            val ctx = itemView.context
            dragHandle?.visibility = if (moveMode) View.VISIBLE else View.GONE
            val pad = if (moveMode) (26 * ctx.resources.displayMetrics.density).toInt() else 0
            tvName.setPaddingRelative(pad, tvName.paddingTop, tvName.paddingEnd, tvName.paddingBottom)
            itemView.alpha = if (moveMode) 0.92f else 1f
            (itemView as? com.google.android.material.card.MaterialCardView)?.let { card ->
                if (moveMode) {
                    card.strokeWidth = 2
                    card.strokeColor = android.graphics.Color.parseColor("#4CAF50")
                } else {
                    card.strokeWidth = 0
                    card.strokeColor = android.graphics.Color.TRANSPARENT
                }
            }
        }

        private fun accentColor(ctx: android.content.Context): Int {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)
            return tv.data
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
        val shown = applyFactorPrecision(panel, value)
        val unit = panel.unitSuffix
        if (unit.isBlank()) return shown
        // °C и % пишутся впритык к числу, остальные единицы — через пробел
        val sep = if (unit.startsWith("°") || unit.startsWith("%")) "" else " "
        return shown + sep + unit
    }

    /**
     * Множитель и десятичная точность (как «Фактор»/«Точность» в IoT MQTT Panel).
     * Логика — в [ru.mqtt.panel.data.ValueFormat] (покрыта JVM-тестами).
     */
    private fun applyFactorPrecision(panel: Panel, value: String): String =
        ru.mqtt.panel.data.ValueFormat.apply(value, panel.factor, panel.decimals)

    private var defaultValueColor: Int? = null

    /**
     * Динамический цвет значения по диапазону Мин/Макс (ColorRange):
     * минимум → цвет минимума, середина → средний, максимум → максимальный,
     * промежуточные значения — плавный переход. Цвета не заданы — обычный.
     */
    private fun applyDynamicColor(tv: TextView?, panel: Panel) {
        if (tv == null) return
        if (defaultValueColor == null) defaultValueColor = tv.currentTextColor
        val v = ru.mqtt.panel.data.ValueFormat.scaled(panel.lastValue, panel.factor)
        val color = if (v != null) ColorRange.resolve(
            panel.colorMin, panel.colorMid, panel.colorMax,
            panel.sliderMin, panel.sliderMax, v
        ) else null
        tv.setTextColor(color ?: defaultValueColor!!)
    }

        /** Полоса прогресса (горизонтальная, вертикальная или круговая): доля
         *  значения в диапазоне Мин/Макс панели, динамическая раскраска. */
        private fun bindProgress(
            bar: android.widget.ProgressBar?,
            vertical: VerticalProgressView?,
            horizontal: HorizontalProgressView?,
            panel: Panel,
            circular: CircularProgressView? = null
        ) {
            if (bar == null && vertical == null && horizontal == null && circular == null) return
            val min = panel.sliderMin
            val max = if (panel.sliderMax > min) panel.sliderMax else min + 100
            // множитель: полоса считается в тех же единицах, что подпись
            val v = ru.mqtt.panel.data.ValueFormat.scaled(panel.lastValue, panel.factor)
            bar?.max = 10000
            if (v == null) {
                bar?.progress = 0
                vertical?.belowRange = false
                horizontal?.belowRange = false
                circular?.belowRange = false
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
                circular?.fraction = f.toFloat()
                circular?.belowRange = below
                ColorRange.resolve(
                    panel.colorMin, panel.colorMid, panel.colorMax,
                    min, max, if (below) min else v
                )?.let { c ->
                    bar?.progressTintList = android.content.res.ColorStateList.valueOf(c)
                    vertical?.fillColor = c
                    horizontal?.fillColor = c
                    circular?.fillColor = c
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
            stopRepeat()
            btn.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (panel.topicPublish.isNotBlank()) onPublish(panel, panel.buttonPayload)
                        // повтор публикации, пока кнопка нажата (0 — без повтора)
                        if (panel.repeatIntervalMs > 0 && panel.topicPublish.isNotBlank()) {
                            val h = android.os.Handler(android.os.Looper.getMainLooper())
                            val r = object : Runnable {
                                override fun run() {
                                    onPublish(panel, panel.buttonPayload)
                                    h.postDelayed(this, panel.repeatIntervalMs.toLong())
                                }
                            }
                            repeatHandler = h
                            repeatRunnable = r
                            h.postDelayed(r, panel.repeatIntervalMs.toLong())
                        }
                        v.isPressed = true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        stopRepeat()
                        v.isPressed = false
                        // второй payload — только при настоящем отпускании
                        if (event.actionMasked == android.view.MotionEvent.ACTION_UP &&
                            panel.releasePayload.isNotBlank() && panel.topicPublish.isNotBlank()
                        ) {
                            onPublish(panel, panel.releasePayload)
                        }
                    }
                }
                true
            }
        }

        private fun bindSlider(panel: Panel) {
            val vertical = panel.sliderVertical
            // показываем один из двух слайдеров: горизонтальный Material Slider
            // или собственный вертикальный (см. item_widget_slider.xml)
            slider?.visibility = if (vertical) View.GONE else View.VISIBLE
            sliderVerticalView?.visibility = if (vertical) View.VISIBLE else View.GONE
            if (vertical) bindSliderVertical(panel) else bindSliderHorizontal(panel)
        }

        /** Вертикальный слайдер — свой View: шаг, диапазон и публикация
         *  по отпусканию, как у горизонтального Material Slider. */
        private fun bindSliderVertical(panel: Panel) {
            val vs = sliderVerticalView ?: return
            vs.valueFrom = panel.sliderMin.toFloat()
            vs.valueTo = max(panel.sliderMin.toFloat() + 1f, panel.sliderMax.toFloat())
            vs.step = panel.sliderStep.toFloat().takeIf { it > 0f } ?: 0f
            val current = ru.mqtt.panel.data.ValueFormat.scaled(panel.lastValue, panel.factor)
                ?.toFloat()?.coerceIn(vs.valueFrom, vs.valueTo) ?: vs.valueFrom
            // значение присваиваем ДО подписки слушателей: программная
            // установка не должна публиковать команду в брокер
            vs.onValueChanged = null
            vs.onValueCommitted = null
            vs.value = current
            tvValue?.text = withUnit(panel, formatValue(vs.value))
            vs.onValueChanged = { v -> tvValue?.text = withUnit(panel, formatValue(v)) }
            vs.onValueCommitted = { v ->
                if (panel.topicPublish.isNotBlank()) onPublish(panel, formatValue(v))
            }
        }

        private fun bindSliderHorizontal(panel: Panel) {
            val sl = slider ?: return
            sl.valueFrom = panel.sliderMin.toFloat()
            sl.valueTo = max(panel.sliderMin.toFloat() + 1f, panel.sliderMax.toFloat())
            // шаг слайдера: 0 — непрерывный (как было до 0.2.79);
            // Slider требует stepSize > 0 либо ровно 0
            val step = panel.sliderStep.toFloat()
            sl.stepSize = if (step > 0f) step else 0f
            val current = ru.mqtt.panel.data.ValueFormat.scaled(panel.lastValue, panel.factor)
                ?.toFloat()?.coerceIn(sl.valueFrom, sl.valueTo) ?: sl.valueFrom
            sl.clearOnChangeListeners()
            sl.clearOnSliderTouchListeners()
            // значение должно попадать в сетку шага, иначе Slider кинет исключение
            sl.value = if (step > 0f) {
                val steps = ((current - sl.valueFrom) / step).roundToInt()
                (sl.valueFrom + steps * step).coerceIn(sl.valueFrom, sl.valueTo)
            } else current
            tvValue?.text = withUnit(panel, formatValue(sl.value))
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
            cv.unitSuffix = panel.unitSuffix
            cv.zoomEnabled = true
            cv.tooltipDark = ThemePrefs.isDark(cv.context)
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
                ru.mqtt.panel.data.ValueFormat.scaled(panel.lastValue, panel.factor)?.let { d ->
                    cv.addTimePoint(max(panel.lastTime, 0L).takeIf { it > 0 } ?: System.currentTimeMillis(), d.toFloat())
                }
            }
            tvValue?.text = withUnit(panel, panel.lastValue)
            bindPeriodChips(panel)
            bindChartSwipe(panel)
        }

        /**
         * Свайп по графику на карточке = следующий/предыдущий период, как тап
         * по чипу. Живёт внутри ChartView (onPeriodSwipe): там же обрабатывается
         * удержание пальца с показом значения, и один обработчик не мешает
         * другому. Панель в замыкании — актуальная: ViewHolder рециклится.
         */
        private fun bindChartSwipe(panel: Panel) {
            val cv = chart ?: return
            cv.onPeriodSwipe = { step ->
                val periods = listOf("hour", "day", "week", "month", "year")
                val idx = periods.indexOf(panel.historyPeriod ?: "day")
                periods.getOrNull(idx + step)?.let { period ->
                    cv.resetZoom()
                    cv.clearTouchHighlight()
                    onChartPeriodSelected?.invoke(panel, period)
                }
            }
        }

        /** Чипы периода «1d 1w 1m 1y» — narodmon-графики и MQTT-графики с историей из Grafana */
        private fun bindPeriodChips(panel: Panel) {
            val row = periodChips ?: return
            val grafanaHistory = panel.connectionId in grafanaConnectionIds
            // чипы периодов: Народмон, Grafana и демо-графики (синтетическая
            // история DemoHistory); прочие графики — только живые точки
            if (panel.source != "narodmon" && !grafanaHistory && !panel.demo) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            val periods = listOf("hour", "day", "week", "month", "year")
            val labels = listOf(
                R.string.chip_hour, R.string.chip_day, R.string.chip_week,
                R.string.chip_month, R.string.chip_year
            )
            // короткие подписи на случай тесной карточки: 1ч 1с 1н 1м 1г
            val labelsShort = listOf(
                R.string.chip_hour_short, R.string.chip_day_short, R.string.chip_week_short,
                R.string.chip_month_short, R.string.chip_year_short
            )
            val d = row.context.resources.displayMetrics.density
            if (row.childCount == 0) {
                labels.forEach { labelRes ->
                    val chip = android.widget.TextView(row.context).apply {
                        text = row.context.getString(labelRes)
                        // 11sp было мелко на телефоне; 13sp остаётся читаемым,
                        // а в тесной карточке 1/3 ширины подключается
                        // короткая форма подписей (1ч/1с/1н/1м/1г)
                        textSize = 13f
                        maxLines = 1
                        gravity = android.view.Gravity.CENTER
                        // Компактнее по вертикали (5dp): чипы не должны съедать
                        // высоту карточки графика в плотной сетке демо
                        setPadding(0, (5 * d).toInt(), 0, (5 * d).toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 14 * d
                            // плашка чуть плотнее — контраст на светлой теме
                            setColor(0x2E54634F)
                        }
                        // равные доли: пять подписей в карточке 1/3 экрана
                        // помещаются только так (wrap_content обрезался)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply { marginEnd = (2 * d).toInt() }
                    }
                    row.addView(chip)
                }
            }
            // Полные трёхбуквенные подписи (час/ден/нед/мес/год), а если карточка
            // слишком узкая — короткие (1ч/1с/1н/1м/1г). Ширину чипов узнаём
            // только после layout, поэтому решение принимаем в post{}.
            fun setChipLabels(short: Boolean) {
                val res = if (short) labelsShort else labels
                for (i in 0 until row.childCount) {
                    val chip = row.getChildAt(i) as? android.widget.TextView ?: continue
                    chip.text = row.context.getString(res[i])
                }
            }
            setChipLabels(false)
            row.post {
                if (row.width <= 0) return@post
                val chipW = (row.width - (2 * d).toInt() * row.childCount) / row.childCount
                val first = row.getChildAt(0) as? android.widget.TextView ?: return@post
                val needW = labels.maxOf { first.paint.measureText(row.context.getString(it)) }
                if (needW + 2 * d > chipW) setChipLabels(true)
            }
            // Слушатель — на КАЖДОМ бинде: ViewHolder рециклится, и чип,
            // созданный один раз, навсегда запомнил бы ПЕРВУЮ панель — тапы
            // меняли период чужой карточки, а текущая не реагировала.
            // Цвета чипов по теме. Активный — сплошная заливка акцентом:
            // светлая тема — тёмно-зелёная плашка с белым текстом, тёмная —
            // светло-зелёная с тёмным. Раньше активный отличался только
            // жирностью текста на одинаковых бледных плашках, и на карточке
            // его было почти не найти.
            val dark = ru.mqtt.panel.ui.ThemePrefs.isDark(row.context)
            val chipOnBg = if (dark) 0xFF9DD697.toInt() else 0xFF386A36.toInt()
            val chipOnFg = if (dark) 0xFF003900.toInt() else 0xFFFFFFFF.toInt()
            val chipOffBg = if (dark) 0x40BBCBB3 else 0x2E54634F
            val chipOffFg = if (dark) 0xFFC3D1BE.toInt() else 0xFF3A4A36.toInt()
            fun applyChip(c: android.widget.TextView, on: Boolean) {
                c.alpha = 1f
                c.setTypeface(c.typeface, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                c.setTextColor(if (on) chipOnFg else chipOffFg)
                val bg = c.background
                if (bg is android.graphics.drawable.GradientDrawable) {
                    bg.setColor(if (on) chipOnBg else chipOffBg)
                } else {
                    c.background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14 * d
                        setColor(if (on) chipOnBg else chipOffBg)
                    }
                }
            }
            for (i in 0 until row.childCount) {
                val chip = row.getChildAt(i) as android.widget.TextView
                applyChip(chip, panel.historyPeriod == periods[i])
                chip.setOnClickListener {
                    periods.getOrNull(i)?.let { period ->
                        // мгновенная подсветка выбранного чипа: карточка не
                        // перебиндивается сразу (перезагрузка истории идёт
                        // в фоне), и без этого тап выглядит «ничего не сделал»
                        for (j in 0 until row.childCount) {
                            applyChip(row.getChildAt(j) as android.widget.TextView, j == i)
                        }
                        chart?.resetZoom()
                        chart?.clearTouchHighlight()
                        onChartPeriodSelected?.invoke(panel, period)
                    }
                }
            }
        }

        /** Мультиграфик: линии по темам серий + легенда «цвет — подпись».
         *  Если у панели заданы цвета диапазона (мин/середина/макс) — все линии
         *  красятся по значению вместо цветов серий. */
        private fun bindMultiChart(panel: Panel) {
            val cv = chart ?: return
            // ёмкость = max(chartPoints, объём загруженной истории): иначе
            // недельная история (504 точки) на панели с chartPoints=10
            // обрезается до 10 последних точек и график выглядит
            // «не переключившимся» (как у одиночного, строка выше)
            cv.maxPoints = maxOf(
                panel.chartPoints,
                panel.series.indices.maxOfOrNull {
                    chartMultiHistory["${panel.id}:$it"]?.size ?: 0
                } ?: 0
            )
            cv.setMultiSeries(panel.series.map { parseColor(it.color, ChartView.DEFAULT_COLOR) })
            cv.showScale = panel.chartShowScale
            cv.unitSuffix = panel.unitSuffix
            cv.zoomEnabled = true
            cv.tooltipDark = ThemePrefs.isDark(cv.context)
            // у мультиграфика линии ВСЕГДА цветами серий: динамическая
            // раскраска по значению здесь не применяется (персональный цвет
            // каждой линии — из редактора серий)
            cv.valueColorizer = null
            cv.clear()
            panel.series.indices.forEach { i ->
                chartMultiHistory["${panel.id}:$i"]?.forEach { p -> cv.addMultiTimePoint(i, p.t, p.v) }
            }
            // Значения серий показываются ТОЛЬКО в легенде поверх графика —
            // верхняя строка дублировала их и съедала место у кривых
            tvValue?.visibility = View.GONE
            // Тема карточки не одна — список тем виден в легенде
            tvTopic?.visibility = View.GONE
            buildLegend(panel)
            // Легенда — плашка по центру верха поля графика; видна при касании
            // графика и 3 с ПОСЛЕ ПОСЛЕДНЕГО показания (окно продлевается
            // каждым новым значением — при частых показаниях легенда просто
            // не гаснет). Появление/скрытие — плавным угасанием 180 мс,
            // чтобы не мигала. Время передачи лежит в той же зоне справа
            // и гаснет, пока легенда видна: обе плашки — «свежесть данных»
            var legendByTouch = false
            fun applyLegendVis(animated: Boolean = true) {
                val lg = legend ?: return
                if (lg.childCount == 0) {
                    lg.visibility = View.GONE
                    cv.overlayTopRect.setEmpty()
                    return
                }
                lg.visibility = View.VISIBLE
                val until = multiLegendUntil[panel.id] ?: 0L
                val show = legendByTouch || until > System.currentTimeMillis()
                val dur = if (animated) 180L else 0L
                lg.animate().cancel()
                lg.animate().alpha(if (show) 0.88f else 0f).setDuration(dur).start()
                if (show) {
                    // пузырь перекрестия не должен уйти под плашку
                    lg.post {
                        if (lg.visibility == View.VISIBLE)
                            cv.overlayTopRect = android.graphics.RectF(
                                lg.x, lg.y, lg.x + lg.width, lg.y + lg.height
                            )
                    }
                    tvTime?.animate()?.cancel()
                    tvTime?.animate()?.alpha(0f)?.setDuration(dur)?.start()
                } else {
                    cv.overlayTopRect.setEmpty()
                    val showT = !locked && panel.showTime != false && panel.lastTime > 0
                    tvTime?.visibility = if (showT) View.VISIBLE else View.GONE
                    tvTime?.animate()?.cancel()
                    tvTime?.animate()?.alpha(1f)?.setDuration(dur)?.start()
                }
            }
            fun armLegendHide() {
                legendHideRun?.let { legend?.removeCallbacks(it) }
                val delay = (multiLegendUntil[panel.id] ?: 0L) - System.currentTimeMillis()
                if (delay > 0) {
                    legendHideRun = Runnable { applyLegendVis() }
                        .also { legend?.postDelayed(it, delay) }
                }
            }
            cv.onChartTouch = { down ->
                legendByTouch = down
                applyLegendVis()
                if (!down) armLegendHide()
            }
            legendByTouch = false
            applyLegendVis(animated = false)
            armLegendHide()
            bindPeriodChips(panel)
            bindChartSwipe(panel)
        }

        private fun buildLegend(panel: Panel) {
            val lg = legend ?: return
            val ctx = lg.context
            val d = ctx.resources.displayMetrics.density
            lg.removeAllViews()
            // Легенда — плашка ПОВЕРХ графика: без серий прячем целиком,
            // иначе видна пустая плашка фона (видимость при наличии серий
            // решает правило «касание/3 с после показания» в бинде)
            val visible = panel.series.count { it.topic.isNotBlank() || it.label.isNotBlank() }
            if (visible == 0) lg.visibility = View.GONE
            panel.series.forEachIndexed { i, s ->
                if (s.topic.isBlank() && s.label.isBlank()) return@forEachIndexed
                val row = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    // строки вплотную: легенда не должна отбирать высоту у графика
                    setPadding(0, 0, 0, 0)
                }
                val dotSize = (8 * d).toInt()
                val dot = View(ctx)
                dot.layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = (5 * d).toInt()
                }
                val fallback = parseColor(legendPalette[i % legendPalette.size], ChartView.DEFAULT_COLOR)
                val color = parseColor(s.color, fallback)
                dot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                }
                row.addView(dot)
                // Одна строка на серию: «1. Подпись» — только имя серии.
                // Значения в легенде не дублируются: их показывают крестовина
                // и большое значение карточки
                val numPrefix = if (visible > 1) "${i + 1}. " else ""
                val label = numPrefix + s.label.ifBlank { s.topic.substringAfterLast('/').ifBlank { s.topic } }
                val tv = android.widget.TextView(ctx).apply {
                    text = label
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                row.addView(tv)
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
