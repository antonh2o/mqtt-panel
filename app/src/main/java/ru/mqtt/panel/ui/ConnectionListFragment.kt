package ru.mqtt.panel.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ru.mqtt.panel.R
import ru.mqtt.panel.data.AppRepository
import ru.mqtt.panel.data.MqttConnection
import ru.mqtt.panel.data.NarodmonApi
import ru.mqtt.panel.databinding.FragmentConnectionListBinding
import ru.mqtt.panel.mqtt.startMqttConnect
import ru.mqtt.panel.mqtt.startNarodmon
import ru.mqtt.panel.mqtt.stopMqttConnection
import ru.mqtt.panel.mqtt.stopMqtt
import ru.mqtt.panel.mqtt.stopNarodmon

class ConnectionListFragment : Fragment() {

    companion object {
        fun newInstance() = ConnectionListFragment()
    }

    private var _binding: FragmentConnectionListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ConnectionListAdapter
    private lateinit var repository: AppRepository

    /** connectionId -> графана жива (для светодиода); нет записи — проверяется */
    private val grafanaOk = mutableMapOf<Long, Boolean>()

    /** Светодиоды MQTT/Narodmon обновляются по broadcast'у статусов сервисов */
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateRuntimeStates()
        }
    }

    private fun updateRuntimeStates() {
        if (_binding == null) return
        adapter.setMqttStates(
            ru.mqtt.panel.mqtt.MqttService.states,
            ru.mqtt.panel.mqtt.NarodmonService.connectionState,
            ru.mqtt.panel.mqtt.NarodmonService.serviceAlive
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = AppRepository(requireContext().applicationContext)

        adapter = ConnectionListAdapter(
            onConnect = { conn -> openDashboard(conn) },
            onEdit = { conn -> editConnection(conn) },
            onDelete = { conn -> confirmDeleteConnection(conn) },
            onEnabledChange = { conn, enabled -> setConnectionEnabled(conn, enabled) }
        )
        binding.recyclerConnections.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerConnections.adapter = adapter

        binding.btnAdd.setOnClickListener { editConnection(null) }
        binding.btnImport.setOnClickListener { showImportDialog() }
        binding.btnExport.setOnClickListener { showExportDialog() }

        migrateGrafanaPrefs()
    }

    override fun onResume() {
        super.onResume()
        loadConnections()
        // Статусы MQTT/Narodmon живут в сервисах и рассылаются через
        // LocalBroadcastManager (так же слушает дашборд). Обычный
        // ContextCompat.registerReceiver эти события НЕ получает — из-за этого
        // светодиод висел на «не подключено» при живом соединении.
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(requireContext())
            .registerReceiver(
                statusReceiver,
                IntentFilter(ru.mqtt.panel.mqtt.MqttService.BROADCAST_STATUS)
            )
        updateRuntimeStates()
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(requireContext())
                .unregisterReceiver(statusReceiver)
        }
    }

    /**
     * Разовая миграция: настройки Grafana из prefs (0.2.51–0.2.52, глобальные)
     * переносятся в первое MQTT-подключение — теперь Grafана настраивается
     * в самом подключении и связана с ним.
     */
    private fun migrateGrafanaPrefs() {
        val prefs = requireContext().getSharedPreferences("grafana", Context.MODE_PRIVATE)
        val url = prefs.getString("url", null)?.trim().orEmpty()
        val uid = prefs.getString("dsUid", null)?.trim().orEmpty()
        if (url.isEmpty() || uid.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val target = repository.getConnections()
                .firstOrNull { !it.narodmon && it.ghUrl.isNullOrBlank() } ?: return@launch
            repository.updateConnection(target.copy(
                ghUrl = url,
                ghUser = prefs.getString("user", null).orEmpty(),
                ghPass = prefs.getString("pass", null).orEmpty(),
                ghDsUid = uid,
                ghMeasurement = "mqtt_consumer",
                ghField = "value",
                ghTagKey = "topic"
            ))
            prefs.edit().clear().apply()
            loadConnections()
        }
    }

    /**
     * Статус Grafana на карточках подключений: по умолчанию «подключено»,
     * фоновый probe (GET /api/health) уточняет — версия или ошибка.
     */
    private fun probeGrafana(connections: List<MqttConnection>) {
        connections.filter { it.grafanaEnabled() }.forEach { conn ->
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = runCatching {
                    ru.mqtt.panel.data.GrafanaApi.probe(ru.mqtt.panel.data.GrafanaApi.settingsOf(conn))
                }.isSuccess
                // светодиод и подпись Grafana на карточке: зелёный/красный по probe
                grafanaOk[conn.id] = ok
                if (_binding != null) adapter.setGrafanaStatus(grafanaOk.toMap())
            }
        }
    }

    /** Единая точка импорта: из файла или из Яндекс Диска */
    private fun showImportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_from)
            .setItems(arrayOf(
                getString(R.string.import_source_file),
                getString(R.string.import_source_yandex),
                getString(R.string.mqtt_share_import_source)
            )) { _, which ->
                when (which) {
                    0 -> (requireActivity() as? MainActivity)?.showImportMenu()
                    1 -> (requireActivity() as? MainActivity)?.yandexRestore()
                    2 -> (requireActivity() as? MainActivity)?.importConfigFromMqtt()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Единая точка экспорта: в файл или на Яндекс Диск */
    private fun showExportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_to)
            .setItems(arrayOf(
                getString(R.string.export_target_file),
                getString(R.string.export_target_yandex),
                getString(R.string.mqtt_share_export_target)
            )) { _, which ->
                when (which) {
                    0 -> (requireActivity() as? MainActivity)?.exportSettings()
                    1 -> (requireActivity() as? MainActivity)?.yandexBackup()
                    2 -> (requireActivity() as? MainActivity)?.publishConfigViaMqtt()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadConnections() {
        if (_binding == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val connections = repository.getConnections()
            if (_binding == null) return@launch
            // Счётчик панелей по подключениям — виден результат импорта
            val counts = repository.getAllPanels().groupingBy { it.connectionId }.eachCount()
            adapter.setPanelCounts(counts)
            adapter.submitList(connections)
            binding.recyclerConnections.visibility =
                if (connections.isEmpty()) View.GONE else View.VISIBLE
            binding.tvEmpty.visibility =
                if (connections.isEmpty()) View.VISIBLE else View.GONE
            probeGrafana(connections)
            autoConnectFirstEnabled(connections)
        }
    }

    /**
     * Главный экран — хаб подключений: все ВКЛЮЧЁННЫЕ MQTT-подключения
     * поднимаются ПАРАЛЛЕЛЬНО (у сервиса по клиенту на каждое); Народмон —
     * один аккаунт, сервис сам разруливает. ensureConnected переиспользует
     * уже живые соединения и не рвёт чужие.
     */
    private fun autoConnectFirstEnabled(connections: List<MqttConnection>) {
        val enabledMqtt = connections.filter { it.enabled != false && !it.narodmon }
        val enabledNm = connections.firstOrNull { it.enabled != false && it.narodmon }
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            enabledMqtt.forEach { conn ->
                val panels = repository.getPanels(conn.id)
                // force=false: авто-коннект уважает 5-минутный backoff после
                // отказа авторизации (иначе LED флапает жёлтый/красный)
                ctx.startMqttConnect(conn, panels, force = false)
            }
            enabledNm?.let { conn ->
                if (ru.mqtt.panel.mqtt.NarodmonService.activeConnectionId == -1L) {
                    val panels = repository.getPanels(conn.id)
                    ctx.startNarodmon(conn, panels)
                }
            }
        }
    }

    private fun editConnection(conn: MqttConnection?) {
        val root = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_connection, null)
        val cbNarodmon = root.findViewById<CheckBox>(R.id.cb_narodmon)
        val cbNmHttp = root.findViewById<CheckBox>(R.id.cb_nm_insecure_http)
        val layoutMqtt = root.findViewById<View>(R.id.layout_mqtt)
        val etHost = root.findViewById<EditText>(R.id.et_host)

        // Narodmon-подключение: MQTT-поля не нужны, учётные данные и API-ключ общие
        val ghCb = root.findViewById<CheckBox>(R.id.cb_grafana_conn)
        val layoutGh = root.findViewById<View>(R.id.layout_grafana_conn)
        val etGhUrl = root.findViewById<EditText>(R.id.et_gh_url)
        val etGhUser = root.findViewById<EditText>(R.id.et_gh_user)
        val etGhPass = root.findViewById<EditText>(R.id.et_gh_pass)
        val etGhUid = root.findViewById<EditText>(R.id.et_gh_uid)
        val etGhM = root.findViewById<EditText>(R.id.et_gh_measurement)
        val etGhF = root.findViewById<EditText>(R.id.et_gh_field)
        val etGhT = root.findViewById<EditText>(R.id.et_gh_tag)

        // Narodmon-подключение: MQTT-поля не нужны, учётные данные и API-ключ общие.
        // Grafana с Народмоном не связывается — секция только у MQTT.
        fun applyNarodmonMode(checked: Boolean) {
            layoutMqtt.visibility = if (checked) View.GONE else View.VISIBLE
            root.findViewById<View>(R.id.layout_api_key).visibility =
                if (checked) View.VISIBLE else View.GONE
            root.findViewById<View>(R.id.layout_nm_account).visibility =
                if (checked) View.VISIBLE else View.GONE
            root.findViewById<View>(R.id.layout_nm_http).visibility =
                if (checked) View.VISIBLE else View.GONE
            layoutGh.visibility = if (checked) View.GONE else View.VISIBLE
            if (checked && etHost.text.isBlank()) etHost.setText("api.narodmon.ru")
        }
        cbNarodmon.setOnCheckedChangeListener { _, checked -> applyNarodmonMode(checked) }

        // --- userLogon: регистрация по email (userLogon БЕЗ hash) и userLogout.
        // Оба работают по уже введённым в форму ключу/почте, без сохранения
        val etApiKey = root.findViewById<EditText>(R.id.et_api_key)
        root.findViewById<View>(R.id.btn_nm_register).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isBlank()) {
                Toast.makeText(requireContext(), R.string.nm_need_key_first, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val etUsername = root.findViewById<EditText>(R.id.et_username)
            val email = etUsername.text.toString().trim()
            val input = EditText(requireContext()).apply {
                setText(email)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.nm_register_title)
                .setMessage(R.string.nm_register_hint)
                .setView(input)
                .setPositiveButton(R.string.save) { _, _ ->
                    val sent = input.text.toString().trim()
                    if (sent.isBlank()) {
                        Toast.makeText(requireContext(),
                            getString(R.string.nm_register_failed, "email empty"),
                            Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    etUsername.setText(sent)
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val reply = NarodmonApi.registerByEmail(
                                requireContext().applicationContext, apiKey, sent)
                            Toast.makeText(requireContext(),
                                getString(R.string.nm_register_ok, reply.take(160)),
                                Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(),
                                getString(R.string.nm_register_failed,
                                    e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        root.findViewById<View>(R.id.btn_nm_logout).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isBlank()) {
                Toast.makeText(requireContext(), R.string.nm_need_key_first, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    NarodmonApi.logOut(requireContext().applicationContext, apiKey)
                    Toast.makeText(requireContext(), R.string.nm_logout_ok, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(),
                        getString(R.string.nm_logout_failed,
                            e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            }
        }

        // чекбокс Grafana раскрывает поля; при первом включении — дефолты telegraf
        ghCb.setOnCheckedChangeListener { _, checked ->
            layoutGh.visibility = if (checked && !cbNarodmon.isChecked) View.VISIBLE else View.GONE
            if (checked && etGhM.text.isBlank()) {
                etGhM.setText("mqtt_consumer"); etGhF.setText("value"); etGhT.setText("topic")
            }
        }

        if (conn != null) {
            root.findViewById<EditText>(R.id.et_name).setText(conn.name)
            etHost.setText(conn.host)
            root.findViewById<EditText>(R.id.et_port).setText(conn.port.toString())
            root.findViewById<EditText>(R.id.et_username).setText(conn.username)
            root.findViewById<EditText>(R.id.et_password).setText(conn.password)
            root.findViewById<EditText>(R.id.et_keepalive).setText(conn.keepAlive.toString())
            root.findViewById<EditText>(R.id.et_client_id).setText(conn.clientId)
            root.findViewById<EditText>(R.id.et_topic_prefix).setText(conn.topicPrefix)
            cbNarodmon.isChecked = conn.narodmon
            root.findViewById<EditText>(R.id.et_api_key).setText(conn.apiKey)
            cbNmHttp.isChecked = NarodmonApi.allowInsecureHttp(requireContext())
            root.findViewById<CheckBox>(R.id.cb_ssl).isChecked = conn.ssl
            root.findViewById<CheckBox>(R.id.cb_websocket).isChecked = conn.websocket
            root.findViewById<CheckBox>(R.id.cb_clean).isChecked = conn.cleanSession
            ghCb.isChecked = conn.grafanaEnabled()
            etGhUrl.setText(conn.ghUrl.orEmpty())
            etGhUser.setText(conn.ghUser.orEmpty())
            etGhPass.setText(conn.ghPass.orEmpty())
            etGhUid.setText(conn.ghDsUid.orEmpty())
            etGhM.setText(conn.ghMeasurement ?: "mqtt_consumer")
            etGhF.setText(conn.ghField ?: "value")
            etGhT.setText(conn.ghTagKey ?: "topic")
            applyNarodmonMode(conn.narodmon)
        } else {
            root.findViewById<EditText>(R.id.et_host).setText("broker.emqx.io")
            root.findViewById<EditText>(R.id.et_port).setText("1883")
            root.findViewById<EditText>(R.id.et_keepalive).setText("60")
            root.findViewById<EditText>(R.id.et_client_id).setText("")
            root.findViewById<CheckBox>(R.id.cb_clean).isChecked = true
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (conn == null) R.string.add_connection else R.string.edit_connection)
            .setView(root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = root.findViewById<EditText>(R.id.et_name).text.toString().trim()
                val host = etHost.text.toString().trim()
                val isNarodmon = cbNarodmon.isChecked
                val apiKey = root.findViewById<EditText>(R.id.et_api_key).text.toString().trim()
                if (name.isBlank() || (!isNarodmon && host.isBlank())) {
                    Toast.makeText(requireContext(), "Заполните имя и хост", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // без личного ключа API narodmon не авторизует запросы
                if (isNarodmon && apiKey.isBlank()) {
                    Toast.makeText(requireContext(), R.string.narodmon_need_key, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                // выбор «разрешить HTTP» общий для аккаунта Народмона
                if (isNarodmon) {
                    NarodmonApi.setAllowInsecureHttp(requireContext(), cbNmHttp.isChecked)
                }
                val ghOn = ghCb.isChecked && !isNarodmon
                val defaultClientId = "mqttpanel-" + System.currentTimeMillis().toString(36)
                val connection = MqttConnection(
                    id = conn?.id ?: 0,
                    name = name,
                    host = if (isNarodmon) "api.narodmon.ru" else host,
                    port = root.findViewById<EditText>(R.id.et_port).text.toString().toIntOrNull() ?: 1883,
                    username = root.findViewById<EditText>(R.id.et_username).text.toString().trim(),
                    password = root.findViewById<EditText>(R.id.et_password).text.toString(),
                    ssl = root.findViewById<CheckBox>(R.id.cb_ssl).isChecked,
                    websocket = root.findViewById<CheckBox>(R.id.cb_websocket).isChecked,
                    keepAlive = root.findViewById<EditText>(R.id.et_keepalive).text.toString().toIntOrNull() ?: 60,
                    cleanSession = root.findViewById<CheckBox>(R.id.cb_clean).isChecked,
                    clientId = root.findViewById<EditText>(R.id.et_client_id).text.toString().trim(),
                    topicPrefix = if (isNarodmon) "" else
                        root.findViewById<EditText>(R.id.et_topic_prefix).text.toString().trim().trimEnd('/'),
                    narodmon = isNarodmon,
                    apiKey = apiKey,
                    ghUrl = if (ghOn) etGhUrl.text.toString().trim().trimEnd('/') else null,
                    ghUser = if (ghOn) etGhUser.text.toString().trim() else null,
                    ghPass = if (ghOn) etGhPass.text.toString() else null,
                    ghDsUid = if (ghOn) etGhUid.text.toString().trim() else null,
                    ghMeasurement = if (ghOn) etGhM.text.toString().trim() else null,
                    ghField = if (ghOn) etGhF.text.toString().trim() else null,
                    ghTagKey = if (ghOn) etGhT.text.toString().trim() else null
                )
                viewLifecycleOwner.lifecycleScope.launch {
                    if (conn == null) repository.addConnection(connection)
                    else repository.updateConnection(connection)
                    loadConnections()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteConnection(conn: MqttConnection) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_connection)
            .setMessage("${conn.name} (${conn.host}:${conn.port})?")
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    repository.deleteConnection(conn.id)
                    loadConnections()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Выключатель подключения на карточке. Включение сразу поднимает сервис
     * (MQTT/Narodmon) с панелями этого подключения — светодиод на главной
     * должен показывать реальное состояние, а не «включён, но не подключён».
     * Выключение гасит активный сервис этого подключения.
     */
    private fun setConnectionEnabled(conn: MqttConnection, enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.updateConnection(conn.copy(enabled = enabled))
            val ctx = requireContext().applicationContext
            if (enabled) {
                val panels = repository.getPanels(conn.id)
                if (conn.narodmon) ctx.startNarodmon(conn, panels) else ctx.startMqttConnect(conn, panels)
            } else {
                ctx.stopMqttConnection(conn.id)
                if (ru.mqtt.panel.mqtt.NarodmonService.activeConnectionId == conn.id) ctx.stopNarodmon()
            }
            loadConnections()
        }
    }

    private fun openDashboard(conn: MqttConnection) {
        // выключенное подключение не открываем: данных в нём не будет,
        // а пользователь ждёт живой дашборд — вежливо говорим почему
        if (conn.enabled == false) {
            Toast.makeText(requireContext(), R.string.connection_disabled_toast, Toast.LENGTH_SHORT)
                .show()
            return
        }
        startActivity(
            Intent(requireContext(), DashboardActivity::class.java)
                .putExtra(DashboardActivity.EXTRA_CONNECTION_ID, conn.id)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
