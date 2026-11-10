package ru.mqtt.panel.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mqtt.panel.R
import ru.mqtt.panel.data.AppRepository
import ru.mqtt.panel.data.ChartSeries
import ru.mqtt.panel.data.ColorRange
import ru.mqtt.panel.data.MqttConnection
import ru.mqtt.panel.data.NarodmonApi
import ru.mqtt.panel.data.Panel
import ru.mqtt.panel.data.Span
import ru.mqtt.panel.data.TopicRegistry
import ru.mqtt.panel.data.TopicSuggestions
import ru.mqtt.panel.data.WidgetType
import ru.mqtt.panel.databinding.ActivityDashboardBinding
import ru.mqtt.panel.mqtt.ConnectionState
import ru.mqtt.panel.mqtt.MqttService
import ru.mqtt.panel.mqtt.NarodmonService
import ru.mqtt.panel.mqtt.publishMqtt
import ru.mqtt.panel.mqtt.startMqttConnect
import ru.mqtt.panel.mqtt.startMqttScan
import ru.mqtt.panel.mqtt.startNarodmon
import ru.mqtt.panel.mqtt.stopMqtt
import ru.mqtt.panel.mqtt.stopMqttScan
import ru.mqtt.panel.mqtt.stopNarodmon
import ru.mqtt.panel.mqtt.syncMqttPanels
import ru.mqtt.panel.mqtt.syncNarodmonPanels
import ru.mqtt.panel.ui.applyBottomInset
import ru.mqtt.panel.ui.applyTopInsetToToolbar

class DashboardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"

        const val DEFAULT_CHART_COLOR = "#4CAF50"

        /** Палитра как в IoT MQTT Panel — 12 цветов для линий графиков */
        val CHART_PALETTE = listOf(
            "#4CAF50", "#2196F3", "#FF9800", "#F44336",
            "#9C27B0", "#00BCD4", "#FFEB3B", "#8BC34A",
            "#E91E63", "#3F51B5", "#795548", "#607D8B"
        )
    }

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var repository: AppRepository
    private lateinit var widgetAdapter: WidgetAdapter
    private var connectionId: Long = 0
    /** Текущее подключение — определяет, чем его обслуживать: MQTT или Narodmon */
    private var loadedConnection: MqttConnection? = null
    /** Сколько сообщений пришло — видно в статусе, чтобы отличить
     *  «подписались, но брокер молчит» от «не обновили UI» */
    private var receivedCount = 0
    /** Пользователь сам нажал «Отключить» — не переподключаться молча */
    private var userDisconnected = false
    private var dragHelper: androidx.recyclerview.widget.ItemTouchHelper? = null
    /** открытый диалог редактора панели — закрывается при батч-операциях */
    private var panelDialog: androidx.appcompat.app.AlertDialog? = null
    /** идёт загрузка историй графиков Народмона (чтобы не стартовать дважды) */
    private var historyLoading = false

    // --- сервис-агностика: подключение обслуживает MqttService или NarodmonService ---

    private fun useNarodmon() = loadedConnection?.narodmon == true

    private fun svcState(): ConnectionState =
        if (useNarodmon()) NarodmonService.connectionState else MqttService.connectionState

    private fun svcConnId(): Long =
        if (useNarodmon()) NarodmonService.activeConnectionId else MqttService.activeConnectionId

    private fun svcUptime(): Long =
        if (useNarodmon()) NarodmonService.uptimeSeconds() else MqttService.uptimeSeconds()

    private fun connectService(conn: MqttConnection, panels: List<Panel>) {
        if (conn.narodmon) startNarodmon(conn, panels) else startMqttConnect(conn, panels)
    }

    private fun disconnectService() {
        if (useNarodmon()) stopNarodmon() else stopMqtt()
    }

    private fun syncServicePanels(panels: List<Panel>) {
        if (useNarodmon()) syncNarodmonPanels(panels) else syncMqttPanels(panels)
    }

    private fun renderStatus(
        connected: Boolean = false,
        connecting: Boolean = false,
        error: String? = null
    ) {
        val base = when {
            error != null -> "✗ $error"
            connecting -> "⟳ " + getString(R.string.connecting)
            connected -> "✓ " + getString(R.string.connected)
            else -> "✗ " + getString(R.string.disconnected)
        }
        // Рядом со статусом — время в сети: 45 с / 5 мин 12 с / 1 ч 07 мин
        val up = svcUptime()
        binding.tvStatus.text =
            if (connected && up >= 0) "$base · ${formatUptime(up)}" else base
        val isConnected = connected || svcState() == ConnectionState.CONNECTED
        binding.btnConnect.setText(
            if (isConnected) R.string.disconnect else R.string.connect
        )
        // Иконка: ▶ отключено / ⏸ подключено
        binding.btnConnect.setIconResource(
            if (isConnected) R.drawable.ic_pause else R.drawable.ic_play
        )
        // Цветовой акцент: зелёный подключено, красный отключено
        if (isConnected) {
            binding.btnConnect.setBackgroundColor(
                android.graphics.Color.parseColor("#2E7D32")
            )
            binding.btnConnect.setTextColor(android.graphics.Color.WHITE)
            binding.btnConnect.iconTint =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            binding.btnConnect.setStrokeWidth(0)
        } else {
            binding.btnConnect.setBackgroundColor(0xFFEEEEEE.toInt())
            binding.btnConnect.setTextColor(0xFFD32F2F.toInt())
            binding.btnConnect.iconTint =
                android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
            binding.btnConnect.setStrokeWidth(1)
            binding.btnConnect.setStrokeColor(
                android.content.res.ColorStateList.valueOf(0xFFD32F2F.toInt())
            )
        }
    }

    /** 45 с → 5 мин 12 с → 1 ч 07 мин */
    private fun formatUptime(total: Long): String {
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        return when {
            h > 0 -> String.format("%d ч %02d мин", h, m)
            m > 0 -> String.format("%d мин %02d с", m, s)
            else -> "$s с"
        }
    }

    /** Раз в секунду обновляем счётчик времени в сети на статус-строке */
    private val uptimeTicker = object : android.os.Handler(android.os.Looper.getMainLooper()) {
        override fun handleMessage(msg: android.os.Message) {
            if (svcState() == ConnectionState.CONNECTED) {
                renderStatus(connected = true)
            }
            sendEmptyMessageDelayed(0, 1000)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(MqttService.EXTRA_STATUS) ?: return
            when {
                status == ConnectionState.CONNECTED.name -> renderStatus(connected = true)
                status == ConnectionState.CONNECTING.name -> renderStatus(connecting = true)
                status.startsWith("error:") ->
                    renderStatus(error = status.removePrefix("error:"))
                else -> renderStatus()
            }
        }
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            // Сервис держит одно соединение: сообщения от другого подключения
            // сюда приходить не должны, иначе значения «перетекают» между приборами.
            // narodmon-сервис рассылает значения в тот же broadcast (topic = sensorId)
            if (MqttService.activeConnectionId != connectionId &&
                NarodmonService.activeConnectionId != connectionId) return
            val topic = intent?.getStringExtra(MqttService.EXTRA_MESSAGE_TOPIC) ?: return
            val payload = intent.getStringExtra(MqttService.EXTRA_MESSAGE_PAYLOAD) ?: return
            // у Народмона — время платформы, у MQTT — момент приёма
            val time = intent.getLongExtra(MqttService.EXTRA_MESSAGE_TIME, System.currentTimeMillis())
            receivedCount++
            widgetAdapter.updateValueByTopic(topic, payload, time)
            renderStatus(connected = svcState() == ConnectionState.CONNECTED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // тема до инфлейта: светлая / тёмная / системная (выбор на главном экране)
        ThemePrefs.apply(this)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectionId = intent.getLongExtra(EXTRA_CONNECTION_ID, 0)
        repository = AppRepository(applicationContext)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Инсеты статус-бара забирает строка версии; тулбар без своего паддинга,
        // иначе над заголовком двойной отступ
        applyTopInsetToVersion(binding.tvVersion)
        binding.tvVersion.text = "v" + (try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "?" })
        applyBottomInset(this, binding.bottomBar)

        widgetAdapter = WidgetAdapter(
            onEdit = { panel -> editPanel(panel) },
            onDelete = { panel -> deletePanel(panel) },
            onDuplicate = { panel -> duplicatePanel(panel) },
            onPublish = { panel, payload ->
                publishMqtt(panel.topicPublish, payload, panel.qos, panel.retained)
            },
            onDragStart = { holder -> dragHelper?.startDrag(holder) }
        )
        // Чипы «1d/1w/1m/1y» на narodmon-графике: сохранить период и перезагрузить историю
        widgetAdapter.onChartPeriodSelected = { panel, period -> onChartPeriodChanged(panel, period) }

        // Сетка 6 колонок: span панели = 6 (вся ширина), 3 (1/2) или 2 (1/3)
        val grid = GridLayoutManager(this, 6)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                widgetAdapter.panelAt(position)?.span?.coerceIn(1, 6) ?: 2
        }
        binding.recyclerWidgets.layoutManager = grid
        binding.recyclerWidgets.adapter = widgetAdapter

        // Режим перемещения: перетаскивание карточек долгим нажатием
        val dragCallback = object : androidx.recyclerview.widget.ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Int = if (widgetAdapter.moveMode) {
                val drag = androidx.recyclerview.widget.ItemTouchHelper.UP or
                    androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                    androidx.recyclerview.widget.ItemTouchHelper.START or
                    androidx.recyclerview.widget.ItemTouchHelper.END
                makeMovementFlags(drag, 0)
            } else 0

            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                from: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                to: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                widgetAdapter.moveItem(from.bindingAdapterPosition, to.bindingAdapterPosition)
                return true
            }

            // Свайпом ничего не удаляем — только перетаскивание
            override fun onSwiped(
                vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                direction: Int
            ) = Unit

            // Старт drag инициирует долгое нажатие карточки (adapter.onDragStart)
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onSelectedChanged(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(vh, actionState)
                vh?.itemView?.scaleX = 1.05f
                vh?.itemView?.scaleY = 1.05f
            }

            override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(recyclerView, vh)
                vh.itemView.scaleX = 1f
                vh.itemView.scaleY = 1f
                // Порядок меняли — сохраняем sortOrder
                lifecycleScope.launch { repository.savePanelOrder(widgetAdapter.snapshotOrder()) }
            }
        }
        dragHelper = androidx.recyclerview.widget.ItemTouchHelper(dragCallback).also {
            it.attachToRecyclerView(binding.recyclerWidgets)
        }

        binding.btnMove.setOnClickListener { toggleMoveMode() }
        binding.btnAddPanel.setOnClickListener { addPanel() }
        binding.btnConnect.setOnClickListener { toggleConnection() }
        binding.btnScan.setOnClickListener { showScanner() }
        binding.tvStatus.setOnClickListener { ensureConnected() }

        loadConnection()
    }

    /** Включает/выключает режим перемещения карточек */
    private fun toggleMoveMode() {
        widgetAdapter.moveMode = !widgetAdapter.moveMode
        binding.btnMove.setIconResource(
            if (widgetAdapter.moveMode) R.drawable.ic_check else R.drawable.ic_move
        )
        if (widgetAdapter.moveMode) {
            Toast.makeText(this, R.string.move_mode_hint, Toast.LENGTH_LONG).show()
        } else {
            // порядок мог меняться — сохраняем и обновляем статус связи
            lifecycleScope.launch { repository.savePanelOrder(widgetAdapter.snapshotOrder()) }
            renderStatus(connected = MqttService.connectionState == ConnectionState.CONNECTED,
                connecting = MqttService.connectionState == ConnectionState.CONNECTING)
        }
        widgetAdapter.notifyDataSetChanged()
    }

    /**
     * Сканер топиков (как auto-create widgets в linear-mqtt-dashboard):
     * подписываемся на `#`, собираем приходящие темы, показываем список —
     * пользователь отмечает нужные, и для них создаём виджеты.
     */
    private fun showScanner() {
        // Сканер подписывается на MQTT-`#` — у narodmon-подключения он скрыт
        if (useNarodmon()) return
        lifecycleScope.launch {
            val connection = repository.getConnection(connectionId)
            if (connection == null) {
                Toast.makeText(this@DashboardActivity, R.string.scan_need_connection, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val existingFilters = repository.getPanels(connectionId).flatMap { p ->
                // У мультиграфика темы подписок — в сериях
                if (p.type == WidgetType.MULTI_CHART) p.series.map { it.topic } + p.topicSubscribe
                else listOf(p.topicSubscribe)
            }.filter { it.isNotBlank() }

            startMqttScan(connection)

            val selected = sortedSetOf<String>()
            var rows: List<TopicRegistry.Observed> = emptyList()
            /** topic -> чекбокс: добавляем только новые темы, иначе прокрутка
             *  улетала бы наверх каждую секунду и терялся отметок пользователя */
            val boxes = HashMap<String, android.widget.CheckBox>()

            // Высота списка ограничена: без этого диалог растягивается на все
            // найденные темы, и кнопки «Создать виджеты»/«Отмена» уходят за экран
            val scroll = object : android.widget.ScrollView(this@DashboardActivity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val maxH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                    super.onMeasure(
                        widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                    )
                }
            }
            val container = android.widget.LinearLayout(this@DashboardActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(24, 0, 24, 0)
            }
            scroll.addView(container)

            val dialog = MaterialAlertDialogBuilder(this@DashboardActivity)
                .setTitle(R.string.scan_title)
                .setView(scroll)
                .setPositiveButton(R.string.scan_add, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
            dialog.show()

            val header = android.widget.TextView(this@DashboardActivity).apply {
                textSize = 13f
                setPadding(0, 12, 0, 8)
                visibility = View.GONE
            }
            container.addView(header)

            fun rebuild() {
                rows = MqttService.topics.uncovered(existingFilters, connectionId)
                rows.filter { !boxes.containsKey(it.topic) }.forEach { obs ->
                    val cb = android.widget.CheckBox(this@DashboardActivity).apply {
                        text = obs.topic + "\n" + obs.lastPayload.take(60)
                        textSize = 13f
                        isChecked = selected.contains(obs.topic)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(obs.topic) else selected.remove(obs.topic)
                            updateScanTitle(dialog, rows.size, selected.size)
                        }
                    }
                    boxes[obs.topic] = cb
                    container.addView(cb)
                }
                // Свежее значение под последними темами, без пересборки раскладки
                rows.takeLast(40).forEach { obs ->
                    boxes[obs.topic]?.text = obs.topic + "\n" + obs.lastPayload.take(60)
                }
                header.text = if (rows.isEmpty()) getString(R.string.scan_empty)
                else getString(R.string.scan_found, rows.size)
                updateScanTitle(dialog, rows.size, selected.size)
            }
            rebuild()

            val timer = java.util.Timer()
            timer.scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    if (!dialog.isShowing) { cancel(); return }
                    runOnUiThread { if (dialog.isShowing) rebuild() }
                }
            }, 1000, 1000)
            dialog.setOnDismissListener {
                timer.cancel()
                stopMqttScan()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                val chosen = rows.filter { selected.contains(it.topic) }
                if (chosen.isEmpty()) {
                    Toast.makeText(this@DashboardActivity, R.string.scan_none_picked, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    chosen.forEach { obs ->
                        repository.addPanel(
                            TopicSuggestions.suggest(obs.topic, obs.lastPayload)
                                .copy(connectionId = connectionId)
                        )
                    }
                    Toast.makeText(
                        this@DashboardActivity,
                        getString(R.string.scan_added, chosen.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    reloadPanels()
                }
                dialog.dismiss()
            }
        }
    }

    /** Заголовок и подпись кнопки с количеством — иначе непонятно, что сохранять */
    private fun updateScanTitle(
        dialog: androidx.appcompat.app.AlertDialog,
        found: Int,
        picked: Int
    ) {
        dialog.setTitle(getString(R.string.scan_found, found))
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.text =
            getString(R.string.scan_add, picked)
    }

    private fun loadConnection() {
        lifecycleScope.launch {
            val connection = repository.getConnection(connectionId)
            if (connection == null) {
                Toast.makeText(this@DashboardActivity, R.string.unknown_connection, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            loadedConnection = connection

            // Версия — в одной строке с именем (subtitle под названием был слишком низко)
            supportActionBar?.title = connection.name
            supportActionBar?.subtitle = null

            val panels = repository.getPanels(connectionId)
            // lastValue из кэша репозитория относится к прежнему подключению —
            // не показываем протухшие значения, retained-сообщения придут сразу после подписки
            receivedCount = 0
            widgetAdapter.submitFresh(panels)
            // Мгновенный показ последних значений и графиков из runtime-кэша
            val cached = repository.loadRuntimeValues(connectionId)
            widgetAdapter.applyCached(cached)
            binding.tvEmpty.visibility = if (panels.isEmpty()) View.VISIBLE else View.GONE

            // Графики Народмона: подтянуть историю за сутки (пачками, с паузами
            // из-за лимита платформы), live-точки допишутся поверх
            if (connection.narodmon) loadNarodmonChartHistory(panels)

            // У narodmon-подключения сканер топиков бессмыслен — прячем кнопку
            binding.btnScan.visibility = if (connection.narodmon) View.GONE else View.VISIBLE

            // Автоподключение при входе на экран:
            // переподключаемся, если нет соединения ИЛИ соединение к другому подключению
            if (panels.isNotEmpty() &&
                (svcState() != ConnectionState.CONNECTED || svcConnId() != connectionId)) {
                connectService(connection, panels)
            }
        }
    }

    private fun toggleConnection() {
        lifecycleScope.launch {
            if (svcState() == ConnectionState.CONNECTED) {
                userDisconnected = true
                disconnectService()
                return@launch
            }
            userDisconnected = false
            ensureConnected()
        }
    }

    /**
     * Подключение этого подключения, если оно не установлено (или висит сокет
     * к другому). Используется при входе на экран, возврате с других экранов,
     * тапе по строке статуса и после правки панелей, когда сервис был offline.
     */
    private fun ensureConnected() {
        lifecycleScope.launch {
            val connection = repository.getConnection(connectionId) ?: return@launch
            loadedConnection = connection
            val panels = repository.getPanels(connectionId)
            if (panels.isEmpty()) return@launch
            if (svcState() == ConnectionState.CONNECTING) return@launch
            if (svcState() == ConnectionState.CONNECTED && svcConnId() == connectionId) return@launch
            userDisconnected = false
            connectService(connection, panels)
        }
    }

    private fun editPanel(panel: Panel) {
        val root = LayoutInflater.from(this)
            .inflate(R.layout.dialog_panel, null)

        root.findViewById<EditText>(R.id.et_name).setText(panel.name)
        root.findViewById<EditText>(R.id.et_topic_sub).setText(panel.topicSubscribe)
        root.findViewById<EditText>(R.id.et_topic_pub).setText(panel.topicPublish)
        root.findViewById<EditText>(R.id.et_topic_sensor).setText(panel.topicSubscribe)
        root.findViewById<EditText>(R.id.et_qos).setText(panel.qos.toString())
        root.findViewById<EditText>(R.id.et_unit).setText(panel.unitSuffix)
        setupTypePicker(root, panel.type)
        setupSpanPicker(root, Span.fromSpanCount(panel.span))
        root.findViewById<EditText>(R.id.et_min).setText(panel.sliderMin.toString())
        root.findViewById<EditText>(R.id.et_max).setText(panel.sliderMax.toString())
        root.findViewById<EditText>(R.id.et_on).setText(panel.onPayload)
        root.findViewById<EditText>(R.id.et_off).setText(panel.offPayload)
        root.findViewById<EditText>(R.id.et_payload).setText(panel.buttonPayload)
        root.findViewById<EditText>(R.id.et_points).setText(panel.chartPoints.toString())
        setupChartOptions(root, panel.chartColor, panel.chartShowScale)
        setupMultiChart(root, panel)
        setupSensorPicker(root)
        setupUnitPresets(root)
        setupColorRange(root, panel)
        setupHistoryPeriod(root, panel)
        root.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_show_time)
            .isChecked = panel.showTime == true
        showTypeSections(root, panel.type)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_panel)
            .setView(root)
            .setPositiveButton(R.string.save) { _, _ ->
                val updated = readPanelForm(root, panel)
                    .copy(id = panel.id, connectionId = panel.connectionId)
                if (updated.type == WidgetType.MULTI_CHART && updated.series.isEmpty()) {
                    Toast.makeText(this, R.string.multi_need_series, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    repository.updatePanel(updated)
                    reloadPanels()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { panelDialog = it }
    }

    private fun addPanel() {
        val root = LayoutInflater.from(this)
            .inflate(R.layout.dialog_panel, null)

        root.findViewById<EditText>(R.id.et_qos).setText("0")
        root.findViewById<EditText>(R.id.et_min).setText("0")
        root.findViewById<EditText>(R.id.et_max).setText("100")
        root.findViewById<EditText>(R.id.et_on).setText("1")
        root.findViewById<EditText>(R.id.et_off).setText("0")
        root.findViewById<EditText>(R.id.et_payload).setText("ON")
        root.findViewById<EditText>(R.id.et_points).setText("50")
        setupTypePicker(root, WidgetType.VALUE)
        setupSpanPicker(root, Span.THIRD)
        setupChartOptions(root, DEFAULT_CHART_COLOR, true)
        setupSensorPicker(root)
        setupUnitPresets(root)
        // у новой панели динамический цвет включён сразу (как в IoT MQTT Panel):
        // синий минимум → зелёная середина → красный максимум
        setupColorRange(root, Panel(
            connectionId = connectionId,
            colorMin = "#2196F3", colorMid = "#4CAF50", colorMax = "#F44336"
        ))
        setupHistoryPeriod(root, panel = Panel(connectionId = connectionId))
        root.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cb_show_time)
            .isChecked = true
        showTypeSections(root, WidgetType.VALUE)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_panel)
            .setView(root)
            .setPositiveButton(R.string.save) { _, _ ->
                val newPanel = readPanelForm(root, Panel(connectionId = connectionId))
                    .copy(connectionId = connectionId)
                if (newPanel.type == WidgetType.MULTI_CHART && newPanel.series.isEmpty()) {
                    Toast.makeText(this, R.string.multi_need_series, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    repository.addPanel(newPanel)
                    reloadPanels()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { panelDialog = it }
    }

    /**
     * У narodmon-подключения вместо поля темы — «Выбрать датчик»:
     * sensorsNearby?my=1&pub=1 показывает свои датчики списком.
     */
    private fun setupSensorPicker(root: View) {
        val etSensor = root.findViewById<EditText>(R.id.et_topic_sensor)
        etSensor.setOnClickListener { pickSensor(root, etSensor) }
        root.findViewById<View>(R.id.btn_pick_sensor).setOnClickListener { pickSensor(root, etSensor) }
        root.findViewById<View>(R.id.btn_add_all_sensors).setOnClickListener { addAllNarodmonSensors(root) }
    }

    private fun pickSensor(root: View, etSensor: EditText) {
        val connection = loadedConnection ?: return
        Toast.makeText(this, R.string.sensor_loading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            // Свои датчики требуют userLogon: логинимся здесь, а не только в сервисе —
            // иначе до добавления первой панели авторизация не происходит вовсе
            val result = runCatching {
                NarodmonApi.ensureLogon(applicationContext, connection.apiKey, connection.username, connection.password)
                NarodmonApi.sensorsNearby(applicationContext, connection.apiKey)
            }
            val sensors = result.getOrElse { emptyList() }
            if (sensors.isEmpty()) {
                // показываем причину платформы («Авторизуйтесь», лимит и т.п.), не просто «пусто»
                val err = result.exceptionOrNull()?.message
                val msg = err?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.sensor_none)
                Toast.makeText(this@DashboardActivity, msg, Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = sensors.map { s ->
                val value = s.value?.let { " = ${NarodmonApi.fmt(it)}" } ?: ""
                "#${s.id} ${s.name}$value"
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@DashboardActivity)
                .setTitle(R.string.sensor_picker_title)
                .setItems(labels) { _, which ->
                    val sensor = sensors[which]
                    etSensor.setText(sensor.id.toString())
                    // пустое название панели берём от датчика
                    val etName = root.findViewById<EditText>(R.id.et_name)
                    if (etName.text.isNullOrBlank()) etName.setText(sensor.name.ifBlank { "#${sensor.id}" })
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * Тип панели выбирается из списка, а не вводом текста: ручная ошибка в
     * подписи silently превращала панель в «Значение». Стрелка в поле
     * (drawableEnd в dialog_panel.xml) подсказывает, что здесь есть выбор.
     */
    private fun setupTypePicker(root: View, selected: WidgetType) {
        val etType = root.findViewById<EditText>(R.id.et_type)
        val labels = WidgetType.entries.map { it.label }.toTypedArray()
        fun set(type: WidgetType) {
            etType.setText(type.label)
            etType.tag = type
            showTypeSections(root, type)
        }
        set(selected)
        etType.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.panel_type)
                .setSingleChoiceItems(labels, WidgetType.entries.indexOf(currentType(etType))) { d, which ->
                    set(WidgetType.entries[which])
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun currentType(etType: EditText): WidgetType =
        etType.tag as? WidgetType ?: WidgetType.fromLabel(etType.text.toString())

    /** Ширина карточки выбирается из списка (в 6-колоночной сетке: 6/3/2) */
    private fun setupSpanPicker(root: View, selected: Span) {
        val etSpan = root.findViewById<EditText>(R.id.et_span)
        val labels = Span.entries.map { it.label }.toTypedArray()
        fun set(span: Span) {
            etSpan.setText(span.label)
            etSpan.tag = span
        }
        set(selected)
        etSpan.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.field_span)
                .setSingleChoiceItems(labels, Span.entries.indexOf(currentSpan(etSpan))) { d, which ->
                    set(Span.entries[which])
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun currentSpan(etSpan: EditText): Span =
        etSpan.tag as? Span ?: Span.fromSpanCount(2)

    /** Показываем только те параметры, что относятся к выбранному типу */
    /**
     * Быстрый выбор единиц с иконками: тап по чипсу подставляет суффикс
     * в поле «Единицы измерения». Термометр/влажность/заряд/напряжение/
     * ватт/ток/давление.
     */
    private fun setupUnitPresets(root: View) {
        val presets = listOf(
            "🌡 °C",
            "💧 %",
            "🔋 %",
            "⚡ V",
            "⚡ W",
            "⚡ A",
            "🌀 mmHg"
        )
        val row = root.findViewById<LinearLayout>(R.id.unit_presets_row)
        val etUnit = root.findViewById<EditText>(R.id.et_unit)
        val d = resources.displayMetrics.density
        presets.forEach { preset ->
            val chip = TextView(this).apply {
                text = preset
                textSize = 13f
                setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16 * d
                    setColor(0x22888888)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * d).toInt() }
            }
            chip.setOnClickListener { etUnit.setText(preset) }
            row.addView(chip)
        }
    }

    /**
     * Динамический цвет значения: три кружка (мин/середина/макс).
     * Тап — выбор цвета из палитры или hex; «Сбросить цвета» выключает.
     * Пустые цвета = динамический цвет выключен (рисуется серым).
     */
    private fun setupColorRange(root: View, panel: Panel) {
        val current = listOf(panel.colorMin, panel.colorMid, panel.colorMax)
        val placeholders = listOf("#2196F3", "#4CAF50", "#F44336")
        val ids = listOf(R.id.dot_color_min, R.id.dot_color_mid, R.id.dot_color_max)
        ids.forEachIndexed { i, id ->
            val dot = root.findViewById<View>(id)
            paintColorDot(dot, current[i], placeholders[i])
            dot.setOnClickListener {
                pickColorDialog(dot.tag as? String ?: placeholders[i]) { hex ->
                    paintColorDot(dot, hex, placeholders[i])
                }
            }
        }
        root.findViewById<View>(R.id.btn_color_clear).setOnClickListener {
            ids.forEachIndexed { i, id -> paintColorDot(root.findViewById(id), "", placeholders[i]) }
        }
    }

    private fun paintColorDot(dot: View, hex: String, placeholder: String) {
        val effective = hex.ifBlank { placeholder }
        dot.tag = hex
        dot.background = makeColorDot(if (hex.isBlank()) "#9E9E9E" else effective)
    }

    /** Выбор цвета: палитра 12 цветов + ручной ввод hex; пустой результат — выключить */
    private fun pickColorDialog(current: String, onPicked: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val etHex = EditText(this).apply {
            hint = "#RRGGBB"
            setText(current)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            textSize = 14f
        }
        container.addView(etHex)
        val paletteRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        CHART_PALETTE.forEach { hex ->
            val size = (32 * d).toInt()
            val gap = (4 * d).toInt()
            val dot = View(this)
            dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(gap, gap, gap, gap) }
            dot.background = makeColorDot(hex)
            dot.contentDescription = hex
            dot.setOnClickListener { etHex.setText(hex) }
            paletteRow.addView(dot)
        }
        container.addView(paletteRow)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.color_range_title)
            .setView(container)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.color_range_clear) { _, _ -> onPicked("") }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val hex = etHex.text.toString().trim()
                val parsed = if (hex.isBlank()) "" else ColorRange.parse(hex)?.let {
                    String.format("#%06X", it and 0xFFFFFF)
                }
                if (hex.isBlank() || parsed != null) {
                    onPicked(parsed ?: "")
                    dialog.dismiss()
                } else {
                    etHex.error = getString(R.string.color_range_title)
                }
            }
        }
        dialog.show()
    }

    private fun dotTag(root: View, id: Int): String =
        root.findViewById<View>(id).tag as? String ?: ""

    /**
     * Истории графиков Народмона: для каждой narodmon-панели типа График —
     * sensorsHistory (сутки) с паузами из-за лимита платформы (≈3 запроса/мин).
     * Обновление не чаще раза в 30 минут (время — в runtime-кэше панели).
     */
    private fun loadNarodmonChartHistory(panels: List<Panel>) {
        if (historyLoading) return
        val connection = loadedConnection ?: return
        val chartPanels = panels.filter {
            it.type == WidgetType.CHART && it.source == "narodmon"
        }
        if (chartPanels.isEmpty()) return
        historyLoading = true
        lifecycleScope.launch {
            try {
                var first = true
                for (panel in chartPanels) {
                    val sensorId = panel.topicSubscribe.toIntOrNull() ?: continue
                    val period = panel.historyPeriod ?: "day"
                    val runtime = repository.loadRuntimeValues(connectionId)
                    val rt = runtime["${panel.id}"]
                    val fresh = System.currentTimeMillis() - (rt?.fetchedAt ?: 0L) < 30 * 60_000L
                    if (fresh && rt?.history?.isNotEmpty() == true && rt?.period == period) continue

                    if (!first) delay(45_000L) // лимит платформы ≈3 запроса/мин
                    first = false
                    val points = runCatching {
                        NarodmonApi.sensorsHistory(applicationContext, connection.apiKey, sensorId, period)
                    }.getOrElse { emptyList() }
                    if (points.isEmpty()) continue

                    withContext(Dispatchers.Main) {
                        widgetAdapter.prependHistory(panel.id, points.map { ChartView.TimePoint(it.t, it.v) })
                    }
                    // запомнить историю и период: смена периода вызывает перезагрузку
                    val all = repository.loadRuntimeValues(connectionId).toMutableMap()
                    all["${panel.id}"] = (all["${panel.id}"]
                        ?: AppRepository.RuntimeValue()).copy(
                        fetchedAt = System.currentTimeMillis(),
                        period = period
                    )
                    repository.saveRuntimeValues(connectionId, all)
                }
            } finally {
                historyLoading = false
            }
        }
    }

    /**
     * Чип «1d/1w/1m/1y» на narodmon-графике: сохранить период панели,
     * сбросить кэш истории прежнего масштаба (иначе масштабы смешаются)
     * и перезагрузить историю карточки одним запросом.
     */
    private fun onChartPeriodChanged(panel: Panel, period: String) {
        if (panel.historyPeriod == period) return
        lifecycleScope.launch {
            val updated = panel.copy(historyPeriod = period)
            repository.updatePanel(updated)
            val all = repository.loadRuntimeValues(connectionId).toMutableMap()
            all["${panel.id}"]?.let { rt ->
                all["${panel.id}"] = rt.copy(history = emptyList(), fetchedAt = 0L, period = period)
            }
            repository.saveRuntimeValues(connectionId, all)
            withContext(Dispatchers.Main) {
                widgetAdapter.clearHistory(panel.id)
                widgetAdapter.updatePanelMeta(updated)
            }
            reloadPanelHistory(updated)
        }
    }

    /** История одной карточки после смены периода: один запрос, ошибка — тостом */
    private fun reloadPanelHistory(panel: Panel) {
        val connection = loadedConnection ?: return
        val sensorId = panel.topicSubscribe.toIntOrNull() ?: return
        val period = panel.historyPeriod ?: "day"
        lifecycleScope.launch {
            val result = runCatching {
                NarodmonApi.sensorsHistory(applicationContext, connection.apiKey, sensorId, period)
            }
            val points = result.getOrNull().orEmpty()
            if (points.isEmpty()) {
                val why = result.exceptionOrNull()?.message?.take(160) ?: "нет данных"
                Toast.makeText(this@DashboardActivity,
                    getString(R.string.history_unavailable, why), Toast.LENGTH_LONG).show()
                return@launch
            }
            withContext(Dispatchers.Main) {
                widgetAdapter.prependHistory(panel.id, points.map { ChartView.TimePoint(it.t, it.v) })
            }
            val all = repository.loadRuntimeValues(connectionId).toMutableMap()
            all["${panel.id}"] = (all["${panel.id}"]
                ?: AppRepository.RuntimeValue()).copy(
                fetchedAt = System.currentTimeMillis(),
                period = period,
                history = points.map { listOf(it.t.toDouble(), it.v.toDouble()) }
            )
            repository.saveRuntimeValues(connectionId, all)
        }
    }

    /**
     * «Добавить все датчики разом»: для каждого датчика аккаунта, у которого
     * ещё нет панели, создаётся карточка Значение (ед. измерения — от платформы).
     */
    private fun addAllNarodmonSensors(root: View) {
        val connection = loadedConnection ?: return
        Toast.makeText(this, R.string.sensor_loading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val sensors = runCatching {
                NarodmonApi.ensureLogon(applicationContext, connection.apiKey, connection.username, connection.password)
                NarodmonApi.sensorsNearby(applicationContext, connection.apiKey)
            }.getOrElse { emptyList() }
            val existing = repository.getPanels(connectionId)
                .filter { it.source == "narodmon" }
                .mapNotNull { it.topicSubscribe.toIntOrNull() }
                .toSet()
            val fresh = sensors.filter { it.id != 0 && it.id !in existing }
            if (fresh.isEmpty()) {
                Toast.makeText(this@DashboardActivity,
                    getString(R.string.sensors_already_added), Toast.LENGTH_LONG).show()
                return@launch
            }
            fresh.forEach { s ->
                repository.addPanel(
                    Panel(
                        name = s.name.ifBlank { "#${s.id}" },
                        type = WidgetType.VALUE,
                        topicSubscribe = s.id.toString(),
                        unitSuffix = NarodmonApi.prettyUnit(s.unit),
                        source = "narodmon",
                        connectionId = connectionId
                    )
                )
            }
            reloadPanels()
            panelDialog?.dismiss()
            Toast.makeText(this@DashboardActivity,
                getString(R.string.sensors_added_count, fresh.size), Toast.LENGTH_LONG).show()
        }
    }

    /** Выбор периода истории Народмона: Сутки / Неделя / Месяц */
    private fun setupHistoryPeriod(root: View, panel: Panel) {
        val periods = listOf("day", "week", "month", "year")
        val labels = listOf(
            getString(R.string.period_day),
            getString(R.string.period_week),
            getString(R.string.period_month),
            getString(R.string.period_year)
        )
        val et = root.findViewById<EditText>(R.id.et_history_period)
        val current = periods.indexOf(panel.historyPeriod).takeIf { it >= 0 } ?: 0
        et.setText(labels[current])
        et.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.field_history_period)
                .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                    et.setText(labels[which])
                    et.tag = periods[which]
                    d.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showTypeSections(root: View, type: WidgetType) {
        val narodmon = useNarodmon()
        root.findViewById<View>(R.id.layout_switch).visibility =
            if (type == WidgetType.SWITCH || type == WidgetType.LED) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.layout_slider).visibility =
            if (type == WidgetType.SLIDER) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.layout_button).visibility =
            if (type == WidgetType.BUTTON) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.layout_chart).visibility =
            if (type == WidgetType.CHART || type == WidgetType.MULTI_CHART) View.VISIBLE else View.GONE
        // У мультиграфика общий цвет линии не выбирается — цвета задаются в сериях
        root.findViewById<View>(R.id.chart_color_row).visibility =
            if (type == WidgetType.CHART) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.layout_multi).visibility =
            if (type == WidgetType.MULTI_CHART) View.VISIBLE else View.GONE
        // Тема подписки не нужна: у мультиграфика она в сериях, у narodmon — датчик
        root.findViewById<View>(R.id.layout_topic_sub).visibility =
            if (type == WidgetType.MULTI_CHART || narodmon) View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.layout_topic_pub).visibility =
            if (type == WidgetType.MULTI_CHART || narodmon) View.GONE else View.VISIBLE
        // У narodmon вместо темы — выбор датчика
        root.findViewById<View>(R.id.layout_topic_sensor).visibility =
            if (narodmon) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.btn_pick_sensor).visibility =
            if (narodmon) View.VISIBLE else View.GONE
        // и кнопка добавления всех датчиков аккаунта разом
        root.findViewById<View>(R.id.btn_add_all_sensors).visibility =
            if (narodmon) View.VISIBLE else View.GONE
        // Период истории — только у narodmon-графиков
        root.findViewById<View>(R.id.layout_history_period).visibility =
            if (narodmon && type == WidgetType.CHART) View.VISIBLE else View.GONE
        val progress = type == WidgetType.PROGRESS_V || type == WidgetType.PROGRESS_H
        // Динамический цвет значения/полосы прогресса — число, текст, график, прогресс
        root.findViewById<View>(R.id.layout_color_range).visibility =
            if (type == WidgetType.VALUE || type == WidgetType.TEXT || type == WidgetType.CHART || progress)
                View.VISIBLE else View.GONE
        // Диапазон Мин/Макс нужен слайдеру, динамическому цвету и полосе прогресса;
        // у графиков он задаёт масштаб раскраски линии/точек (без него дефолт 0..100)
        root.findViewById<View>(R.id.layout_slider).visibility =
            if (type == WidgetType.SLIDER || type == WidgetType.VALUE || type == WidgetType.TEXT ||
                type == WidgetType.CHART || type == WidgetType.MULTI_CHART || progress)
                View.VISIBLE else View.GONE
        // Единицы измерения имеют смысл только там, где значение показывают числом/текстом
        val unitVisible =
            if (type == WidgetType.SWITCH || type == WidgetType.BUTTON || type == WidgetType.LED)
                View.GONE else View.VISIBLE
        root.findViewById<View>(R.id.layout_unit).visibility = unitVisible
        root.findViewById<View>(R.id.unit_presets_scroll).visibility = unitVisible
        root.findViewById<View>(R.id.cb_show_time).visibility = View.VISIBLE
        // Переключились на мультиграфик при пустом списке — сразу одна пустая серия
        root.findViewById<View>(R.id.btn_add_series).setOnClickListener { addSeriesRow(root, null) }
        if (type == WidgetType.MULTI_CHART) {
            val container = root.findViewById<LinearLayout>(R.id.series_container)
            if (container.childCount == 0) addSeriesRow(root, null)
        }
    }

    /**
     * Редактор серий мультиграфика: строки «тема + цвет + подпись» создаются
     * кодом — их число зависит от панели.
     */
    private fun setupMultiChart(root: View, panel: Panel) {
        val container = root.findViewById<LinearLayout>(R.id.series_container)
        container.removeAllViews()
        panel.series.forEach { addSeriesRow(root, it) }
    }

    /** Строка серии: тема + кнопка удаления; ниже — цвет + подпись */
    private fun addSeriesRow(root: View, series: ChartSeries?) {
        val container = root.findViewById<LinearLayout>(R.id.series_container)
        val d = resources.displayMetrics.density
        val initialColor = series?.color ?: CHART_PALETTE[container.childCount % CHART_PALETTE.size]

        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val etTopic = EditText(this).apply {
            hint = getString(R.string.series_topic)
            setText(series?.topic ?: "")
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val borderless = android.util.TypedValue().also {
            theme.resolveAttribute(androidx.appcompat.R.attr.selectableItemBackgroundBorderless, it, true)
        }
        val btnRemove = ImageButton(this).apply {
            setImageResource(R.drawable.ic_delete)
            setBackgroundResource(borderless.resourceId)
            val s = (32 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = (4 * d).toInt() }
            contentDescription = getString(R.string.series_remove)
        }
        top.addView(etTopic)
        top.addView(btnRemove)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val dotSize = (24 * d).toInt()
        val dot = View(this)
        dot.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = (8 * d).toInt() }
        dot.background = makeColorDot(initialColor)
        dot.tag = initialColor
        dot.contentDescription = getString(R.string.chart_color)
        dot.setOnClickListener {
            pickSeriesColor(dot.tag as? String ?: initialColor) { hex ->
                dot.background = makeColorDot(hex)
                dot.tag = hex
            }
        }
        val etLabel = EditText(this).apply {
            hint = getString(R.string.series_label)
            setText(series?.label ?: "")
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottom.addView(dot)
        bottom.addView(etLabel)

        row.addView(top)
        row.addView(bottom)
        btnRemove.setOnClickListener { container.removeView(row) }
        container.addView(row)
    }

    /** Выбор цвета серии: палитра в две строки по 6 цветов */
    private fun pickSeriesColor(current: String, onPick: (String) -> Unit) {
        val d = resources.displayMetrics.density
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.chart_color)
            .setView(grid)
            .setNegativeButton(R.string.cancel, null)
            .create()
        CHART_PALETTE.chunked(6).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { hex ->
                val size = (32 * d).toInt()
                val gap = (4 * d).toInt()
                val dot = View(this)
                dot.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(gap, gap, gap, gap) }
                dot.background = makeColorDot(hex)
                dot.alpha = if (hex.equals(current, ignoreCase = true)) 1f else 0.55f
                dot.contentDescription = hex
                dot.setOnClickListener {
                    onPick(hex)
                    dialog.dismiss()
                }
                line.addView(dot)
            }
            grid.addView(line)
        }
        dialog.show()
    }

    /** Чтение серий из редактора; строки с пустой темой пропускаются */
    private fun readSeries(root: View): List<ChartSeries> {
        val container = root.findViewById<LinearLayout>(R.id.series_container) ?: return emptyList()
        val result = mutableListOf<ChartSeries>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as LinearLayout
            val top = row.getChildAt(0) as LinearLayout
            val bottom = row.getChildAt(1) as LinearLayout
            val etTopic = top.getChildAt(0) as EditText
            val dot = bottom.getChildAt(0) as View
            val etLabel = bottom.getChildAt(1) as EditText
            val topic = etTopic.text.toString().trim()
            if (topic.isEmpty()) continue
            result.add(
                ChartSeries(
                    topic = topic,
                    color = dot.tag as? String ?: CHART_PALETTE[0],
                    label = etLabel.text.toString().trim()
                )
            )
        }
        return result
    }

    /**
     * Палитра цвета линии графика (как выбор цвета топика в IoT MQTT Panel)
     * и переключатель шкалы. Выбранный цвет лежит в tag контейнера.
     */
    private fun setupChartOptions(root: View, color: String, showScale: Boolean) {
        val palette = root.findViewById<LinearLayout>(R.id.chart_colors)
        val cbScale = root.findViewById<android.widget.CheckBox>(R.id.cb_show_scale)
        cbScale.isChecked = showScale
        palette.tag = color

        val size = (32 * resources.displayMetrics.density).toInt()
        val gap = (4 * resources.displayMetrics.density).toInt()
        palette.removeAllViews()
        CHART_PALETTE.forEach { hex ->
            val dot = android.view.View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(gap, gap, gap, gap)
            dot.layoutParams = lp
            dot.background = makeColorDot(hex)
            dot.isSelected = hex.equals(color, ignoreCase = true)
            dot.alpha = if (dot.isSelected) 1f else 0.55f
            dot.contentDescription = hex
            dot.setOnClickListener {
                palette.tag = hex
                for (i in 0 until palette.childCount) {
                    val child = palette.getChildAt(i)
                    val sel = child.contentDescription == hex
                    child.isSelected = sel
                    child.alpha = if (sel) 1f else 0.55f
                }
            }
            palette.addView(dot)
        }
    }

    private fun makeColorDot(hex: String): android.graphics.drawable.GradientDrawable {
        val d = android.graphics.drawable.GradientDrawable()
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setColor(try { android.graphics.Color.parseColor(hex) } catch (e: Exception) {
            android.graphics.Color.parseColor(DEFAULT_CHART_COLOR)
        })
        d.setStroke((2 * resources.displayMetrics.density).toInt(), 0xFF888888.toInt())
        return d
    }

    /** Чтение формы панели из диалога */
    private fun readPanelForm(root: View, base: Panel): Panel {
        val etType = root.findViewById<EditText>(R.id.et_type)
        val colors = root.findViewById<LinearLayout>(R.id.chart_colors)
        val narodmon = useNarodmon()
        val panel = base.copy(
            name = root.findViewById<EditText>(R.id.et_name).text.toString().trim()
                .ifBlank { "Новая панель" },
            type = currentType(etType),
            unitSuffix = root.findViewById<EditText>(R.id.et_unit).text.toString().trim(),
            topicSubscribe = if (narodmon) {
                // у narodmon-панели в теме подписки хранится ID датчика
                root.findViewById<EditText>(R.id.et_topic_sensor).text.toString().trim()
            } else {
                root.findViewById<EditText>(R.id.et_topic_sub).text.toString().trim()
            },
            topicPublish = if (narodmon) "" else {
                root.findViewById<EditText>(R.id.et_topic_pub).text.toString().trim()
            },
            qos = root.findViewById<EditText>(R.id.et_qos).text.toString().toIntOrNull() ?: 0,
            sliderMin = root.findViewById<EditText>(R.id.et_min).text.toString().toDoubleOrNull() ?: 0.0,
            sliderMax = root.findViewById<EditText>(R.id.et_max).text.toString().toDoubleOrNull() ?: 100.0,
            onPayload = root.findViewById<EditText>(R.id.et_on).text.toString().ifBlank { "1" },
            offPayload = root.findViewById<EditText>(R.id.et_off).text.toString().ifBlank { "0" },
            buttonPayload = root.findViewById<EditText>(R.id.et_payload).text.toString().ifBlank { "ON" },
            chartPoints = root.findViewById<EditText>(R.id.et_points).text.toString().toIntOrNull() ?: 50,
            chartColor = colors.tag as? String ?: DEFAULT_CHART_COLOR,
            chartShowScale = root.findViewById<android.widget.CheckBox>(R.id.cb_show_scale).isChecked,
            span = currentSpan(root.findViewById(R.id.et_span)).spanCount,
            showTime = root.findViewById<android.widget.CheckBox>(R.id.cb_show_time).isChecked,
            historyPeriod = root.findViewById<EditText>(R.id.et_history_period).tag
                as? String ?: root.findViewById<EditText>(R.id.et_history_period).text.toString(),
            colorMin = dotTag(root, R.id.dot_color_min),
            colorMid = dotTag(root, R.id.dot_color_mid),
            colorMax = dotTag(root, R.id.dot_color_max),
            source = if (narodmon) "narodmon" else "mqtt"
        )
        return if (panel.type == WidgetType.MULTI_CHART) {
            // у мультиграфика темы подписки — в сериях, общее поле не используется
            panel.copy(series = readSeries(root), topicSubscribe = "")
        } else panel
    }

    /** Клон панели: та же конфигурация, новое имя с суффиксом « (копия)» */
    private fun duplicatePanel(panel: Panel) {
        lifecycleScope.launch {
            repository.addPanel(panel.copy(id = 0, name = panel.name + " (копия)"))
            Toast.makeText(this@DashboardActivity, R.string.panel_duplicated, Toast.LENGTH_SHORT).show()
            reloadPanels()
        }
    }

    private fun deletePanel(panel: Panel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_panel)
            .setMessage(panel.name + "?")
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deletePanel(panel.id)
                    reloadPanels()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private suspend fun reloadPanels() {
        val panels = repository.getPanels(connectionId)
        widgetAdapter.submitKeepingValues(panels)
        binding.tvEmpty.visibility = if (panels.isEmpty()) View.VISIBLE else View.GONE
        // Состав панелей изменился — обновляем подписки сервиса без переподключения.
        // Если соединения нет (или оно к другому подключению) — поднимаем заново,
        // иначе правка панели на «остывшем» сокете оставляла экран offline навсегда
        if (svcConnId() == connectionId && svcState() == ConnectionState.CONNECTED) {
            syncServicePanels(panels)
        } else if (!userDisconnected) {
            ensureConnected()
        }
    }

    override fun onResume() {
        super.onResume()
        val lm = LocalBroadcastManager.getInstance(this)
        lm.registerReceiver(statusReceiver, IntentFilter(MqttService.BROADCAST_STATUS))
        lm.registerReceiver(messageReceiver, IntentFilter(MqttService.BROADCAST_MESSAGE))
        // После перезахода/возврата: соединение могло умереть, пока экрана не было
        if (!userDisconnected) ensureConnected()
        // Запускаем тикер обновления аптайма на статус-строке
        uptimeTicker.sendEmptyMessage(0)
    }

    override fun onPause() {
        super.onPause()
        val lm = LocalBroadcastManager.getInstance(this)
        lm.unregisterReceiver(statusReceiver)
        lm.unregisterReceiver(messageReceiver)
        uptimeTicker.removeMessages(0)
        // сохранить последние значения и истории графиков для мгновенного показа
        lifecycleScope.launch {
            repository.saveRuntimeValues(connectionId, widgetAdapter.snapshotState())
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
