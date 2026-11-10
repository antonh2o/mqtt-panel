package ru.mqtt.panel.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.mqtt.panel.data.YandexDisk
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
        // тема до инфлейта: светлая / тёмная / системная (кнопки на тулбаре)
        ThemePrefs.apply(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(applicationContext)
        setSupportActionBar(binding.toolbar)
        // Инсеты статус-бара забирает строка версии; тулбар без своего паддинга,
        // иначе над заголовком двойной отступ
        applyTopInsetToVersion(binding.tvVersion)
        binding.toolbar.title = getString(R.string.app_name)
        binding.tvVersion.text = "v${appVersion()}"

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, ConnectionListFragment.newInstance())
                .commit()
        }
        handleYandexOAuth(intent.data)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_theme, menu)
        updateThemeIcons(menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_check_updates -> checkForUpdates(manual = true)
            R.id.action_ya_backup -> yandexBackup()
            R.id.action_ya_restore -> yandexRestore()
            R.id.action_ya_settings -> yandexSettings()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
        val mode = when (item.itemId) {
            R.id.action_theme_light -> ThemePrefs.LIGHT
            R.id.action_theme_dark -> ThemePrefs.DARK
            R.id.action_theme_auto -> ThemePrefs.SYSTEM
            else -> return super.onOptionsItemSelected(item)
        }
        ThemePrefs.save(this, mode)
        updateThemeIcons(currentMenu ?: return true)
        return true
    }

    private var currentMenu: android.view.Menu? = null

    /** Активная тема — яркая иконка, остальные приглушены */
    private fun updateThemeIcons(menu: android.view.Menu) {
        currentMenu = menu
        mapOf(
            R.id.action_theme_light to ThemePrefs.LIGHT,
            R.id.action_theme_dark to ThemePrefs.DARK,
            R.id.action_theme_auto to ThemePrefs.SYSTEM
        ).forEach { (id, mode) ->
            menu.findItem(id)?.icon?.mutate()?.setAlpha(ThemePrefs.alphaFor(this, mode).let { (it * 255).toInt() })
        }
    }

    /**
     * Проверка обновлений: свежий APK — в папке apk/ этого же репозитория
     * на GitHub. manual=true — показать результат тостом, иначе только
     * диалог при наличии новой версии.
     */
    private fun checkForUpdates(manual: Boolean) {
        lifecycleScope.launch {
            val info = ru.mqtt.panel.data.UpdateChecker.latest(applicationContext)
            val current = appVersion()
            if (info == null || !ru.mqtt.panel.data.UpdateChecker.isNewer(info.version, current)) {
                if (manual) Toast.makeText(this@MainActivity,
                    R.string.update_latest, Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.update_available, info.version))
                .setPositiveButton(R.string.update_install) { _, _ ->
                    downloadAndInstall(info)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun downloadAndInstall(info: ru.mqtt.panel.data.UpdateChecker.Info) {
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val dir = java.io.File(cacheDir, "update").apply { mkdirs() }
            val apk = java.io.File(dir, info.apkName)
            val ok = ru.mqtt.panel.data.UpdateChecker.download(applicationContext, info.url, apk)
            if (!ok) {
                Toast.makeText(this@MainActivity,
                    R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity, "$packageName.fileprovider", apk
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    /** Настройки Яндекс Диска: ClientID + OAuth-токен */
    private fun yandexSettings() {
        val root = LayoutInflater.from(this)
            .inflate(R.layout.dialog_yandex, null)
        val etClient = root.findViewById<android.widget.EditText>(R.id.et_ya_client_id)
        val etToken = root.findViewById<android.widget.EditText>(R.id.et_ya_token)
        val cred = YandexDisk.credentials(this)
        etClient.setText(cred.clientId)
        etToken.setText(cred.token)

        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_ya_get_token)
            .setOnClickListener {
                val clientId = etClient.text.toString().trim()
                if (clientId.isBlank()) {
                    Toast.makeText(this, R.string.yandex_need_client, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                // браузер: вход в Яндекс → согласие → редирект mqttpanel://yandex-oauth#access_token=…
                startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(YandexDisk.authUrl(clientId))
                ))
            }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yandex_settings)
            .setView(root)
            .setPositiveButton(R.string.save) { _, _ ->
                YandexDisk.saveCredentials(
                    this, etClient.text.toString().trim(), etToken.text.toString().trim()
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Выгрузка настроек на Яндекс Диск (если токен получен) */
    private fun yandexBackup() {
        val cred = YandexDisk.credentials(this)
        if (cred.token.isBlank()) {
            Toast.makeText(this, R.string.yandex_need_token, Toast.LENGTH_LONG).show()
            yandexSettings()
            return
        }
        lifecycleScope.launch {
            val json = repository.exportConfig()
            val (ok, msg) = YandexDisk.upload(applicationContext, cred.token, json)
            if (ok) {
                // msg может уточнять, куда легла копия («папка приложения»)
                Toast.makeText(this@MainActivity,
                    getString(R.string.yandex_backup_done) + (msg ?: ""),
                    Toast.LENGTH_LONG).show()
            } else {
                // показать причину платформы (нет прав Диска, истёк токен и т.п.)
                Toast.makeText(this@MainActivity,
                    msg ?: getString(R.string.yandex_backup_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Восстановление настроек из Яндекс Диска (полная замена конфигурации) */
    private fun yandexRestore() {
        val cred = YandexDisk.credentials(this)
        if (cred.token.isBlank()) {
            Toast.makeText(this, R.string.yandex_need_token, Toast.LENGTH_LONG).show()
            yandexSettings()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yandex_restore)
            .setMessage(R.string.yandex_restore_confirm)
            .setPositiveButton(R.string.save) { _, _ ->
                lifecycleScope.launch {
                    val (okDl, res) = YandexDisk.download(applicationContext, cred.token)
                    if (!okDl) {
                        Toast.makeText(this@MainActivity,
                            res ?: getString(R.string.yandex_restore_empty),
                            Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val ok = repository.importConfig(res ?: "")
                    Toast.makeText(this@MainActivity,
                        if (ok) R.string.yandex_restore_done else R.string.import_error,
                        Toast.LENGTH_LONG).show()
                    if (ok) refreshFragment()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** OAuth-токен Яндекс Диска из deep link: mqttpanel://yandex-oauth#access_token=… */
    private fun handleYandexOAuth(data: android.net.Uri?) {
        if (data == null || data.host != "yandex-oauth") return
        val token = Regex("access_token=([^&]+)").find(data.encodedFragment ?: "")?.groupValues?.get(1)
        if (!token.isNullOrBlank()) {
            YandexDisk.saveToken(this, token)
            Toast.makeText(this, R.string.yandex_token_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleYandexOAuth(intent.data)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver, IntentFilter(MqttService.BROADCAST_STATUS)
        )
        // авто-проверка обновлений не чаще раза в 12 часов
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val last = prefs.getLong("last_update_check", 0L)
        if (System.currentTimeMillis() - last > 12 * 3600_000L) {
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            checkForUpdates(manual = false)
        }
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
