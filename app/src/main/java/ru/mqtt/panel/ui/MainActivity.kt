package ru.mqtt.panel.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.launch
import ru.mqtt.panel.R
import ru.mqtt.panel.data.AppRepository
import ru.mqtt.panel.data.ImportLog
import ru.mqtt.panel.data.IotMqttPanelImporter
import ru.mqtt.panel.databinding.ActivityMainBinding
import ru.mqtt.panel.mqtt.ConnectionState
import ru.mqtt.panel.mqtt.MqttService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var repository: AppRepository
        private set

    private var exportedJson: String = ""

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(MqttService.EXTRA_STATUS) ?: return
            binding.toolbar.subtitle = when {
                status == ConnectionState.CONNECTED.name -> getString(R.string.connected)
                status == ConnectionState.CONNECTING.name -> getString(R.string.connecting)
                status.startsWith("error:") -> status.removePrefix("error:")
                else -> getString(R.string.disconnected)
            }
        }
    }

    /** Импорт из нашего формата */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            readUri(uri) { json ->
                lifecycleScope.launch {
                    val ok = repository.importConfig(json)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) R.string.import_success else R.string.import_error,
                        Toast.LENGTH_LONG
                    ).show()
                    if (ok) refreshFragment()
                }
            }
        }

    /** Импорт из формата IoT MQTT Panel */
    private val iotImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            readUri(uri) { json ->
                lifecycleScope.launch {
                    val result = IotMqttPanelImporter.importDetailed(json)
                    val report = result.getOrNull()
                    if (report == null) {
                        val err = result.exceptionOrNull()?.message ?: "unknown"
                        ImportLog.write(this@MainActivity, "Импорт IoT MQTT Panel: ОШИБКА: $err")
                        Toast.makeText(
                            this@MainActivity,
                            "${getString(R.string.import_error)}: $err",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    val config = report.config
                    repository.importConfig(Gson().toJson(config))

                    // Полный лог импорта в файл
                    ImportLog.write(this@MainActivity, report.details.joinToString("\n"))

                    val counts = config.panels.groupingBy { it.connectionId }.eachCount()
                    val report2 = config.connections.joinToString("\n") { c ->
                        "• ${c.name}: ${counts[c.id] ?: 0} пан."
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Импортировано ${config.connections.size} подкл., ${config.panels.size} панелей")
                        .setMessage(report2 + "\n\n" + getString(R.string.log_saved_to, ImportLog.path(this@MainActivity)))
                        .setPositiveButton(R.string.ok, null)
                        .setNeutralButton(R.string.share_log) { _, _ -> shareLog() }
                        .show()
                    refreshFragment()
                }
            }
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(exportedJson.toByteArray()) }
                Toast.makeText(this, R.string.export_success, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "${getString(R.string.export_error)}: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(applicationContext)
        setSupportActionBar(binding.toolbar)
        applyTopInsetToToolbar(this, binding.toolbar)
        binding.toolbar.title = getString(R.string.app_name)
        binding.tvVersion.text = "v${appVersion()}"

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ConnectionListFragment.newInstance())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver, IntentFilter(MqttService.BROADCAST_STATUS)
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    /** Вызывается кнопкой «Импорт» внизу списка подключений */
    fun showImportMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_import)
            .setItems(
                arrayOf(
                    getString(R.string.import_iot_panel),
                    getString(R.string.import_our_format)
                )
            ) { _, which ->
                val types = arrayOf("application/json", "text/plain", "*/*")
                when (which) {
                    0 -> iotImportLauncher.launch(types)
                    1 -> importLauncher.launch(types)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Вызывается кнопкой «Экспорт» внизу списка подключений */
    fun exportSettings() {
        lifecycleScope.launch {
            exportedJson = repository.exportConfig()
            createDocumentLauncher.launch("mqtt-panel-config.json")
        }
    }

    private fun readUri(uri: Uri, block: (String) -> Unit) {
        try {
            val json = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            if (json.isNullOrBlank()) {
                Toast.makeText(this, R.string.import_error, Toast.LENGTH_SHORT).show()
            } else {
                block(json)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.import_error)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Полный путь к файлу журнала (для показа пользователю) */
    private fun shareLog() {
        val file = java.io.File(ImportLog.path(this))
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MQTT Panel ${appVersion()} debug log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_log)))
    }

    private fun refreshFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, ConnectionListFragment.newInstance())
            .commit()
    }

    /** Версия сборки — показывается в заголовке, чтобы видеть какой APK установлен */
    fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
