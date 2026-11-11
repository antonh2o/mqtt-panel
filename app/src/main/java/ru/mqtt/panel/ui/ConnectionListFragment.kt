package ru.mqtt.panel.ui

import android.content.Context
import android.content.Intent
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
import ru.mqtt.panel.databinding.FragmentConnectionListBinding

class ConnectionListFragment : Fragment() {

    companion object {
        fun newInstance() = ConnectionListFragment()
    }

    private var _binding: FragmentConnectionListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ConnectionListAdapter
    private lateinit var repository: AppRepository

    /** connectionId -> статус Grafana (заполняется фоновыми проверками) */
    private val grafanaStatus = mutableMapOf<Long, String>()

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
            onDelete = { conn -> confirmDeleteConnection(conn) }
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
                val text = runCatching {
                    ru.mqtt.panel.data.GrafanaApi.probe(ru.mqtt.panel.data.GrafanaApi.settingsOf(conn))
                }.fold(
                    { v -> getString(R.string.grafana_status_connected, v) },
                    { e -> getString(R.string.grafana_status_error, e.message ?: "ошибка") }
                )
                grafanaStatus[conn.id] = text
                if (_binding != null) adapter.setGrafanaStatus(grafanaStatus.toMap())
            }
        }
    }

    /** Единая точка импорта: из файла или из Яндекс Диска */
    private fun showImportDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_from)
            .setItems(arrayOf(
                getString(R.string.import_source_file),
                getString(R.string.import_source_yandex)
            )) { _, which ->
                when (which) {
                    0 -> (requireActivity() as? MainActivity)?.showImportMenu()
                    1 -> (requireActivity() as? MainActivity)?.yandexRestore()
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
                getString(R.string.export_target_yandex)
            )) { _, which ->
                when (which) {
                    0 -> (requireActivity() as? MainActivity)?.exportSettings()
                    1 -> (requireActivity() as? MainActivity)?.yandexBackup()
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
        }
    }

    private fun editConnection(conn: MqttConnection?) {
        val root = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_connection, null)
        val cbNarodmon = root.findViewById<CheckBox>(R.id.cb_narodmon)
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
            layoutGh.visibility = if (checked) View.GONE else View.VISIBLE
            if (checked && etHost.text.isBlank()) etHost.setText("api.narodmon.ru")
        }
        cbNarodmon.setOnCheckedChangeListener { _, checked -> applyNarodmonMode(checked) }

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
            cbNarodmon.isChecked = conn.narodmon
            root.findViewById<EditText>(R.id.et_api_key).setText(conn.apiKey)
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

    private fun openDashboard(conn: MqttConnection) {
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
