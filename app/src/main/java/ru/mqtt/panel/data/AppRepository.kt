package ru.mqtt.panel.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Хранилище подключений и панелей (JSON-файл во внутренней памяти).
 * Аналог DatabaseManager из narmon-client, но без SQLite — конфигурация небольшая.
 */
class AppRepository(private val context: Context) {

    companion object {
        private const val TAG = "AppRepository"
        private const val FILE_NAME = "config.json"
        private const val BACKUP_NAME = "config.backup.json"
        private val gson = Gson()

        /**
         * Кэш один на процесс. Каждый экран создаёт свой AppRepository, и при
         * раздельных кэшах список подключений возвращал устаревшее число панелей
         * после того, как панели добавлялись на экране дашборда (сканер, «+»).
         */
        private val lock = Any()
        private var cache: AppConfig? = null
    }

    private fun load(): AppConfig = synchronized(lock) {
        cache?.let { return it }
        val main = java.io.File(context.filesDir, FILE_NAME)
        val backup = java.io.File(context.filesDir, BACKUP_NAME)
        // Основной файл мог оборваться на середине записи (усиленная сборка мусора
        // во время save()) — тогда читаем резервную копию, иначе приложение
        // «забыло» бы все панели и подключения, как уже случалось.
        val fromMain = parse(main)
        val config = (fromMain ?: parse(backup) ?: AppConfig()).fixIds()
        cache = config
        if (fromMain == null) {
            Log.w(TAG, "config.json повреждён/отсутствует — восстановлен из $BACKUP_NAME")
            save(config)
        } else {
            refreshBackup(main, backup)
        }
        config
    }

    /** null — если файл отсутствует, битый или содержит JSON без массивов */
    private fun parse(file: java.io.File): AppConfig? {
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<AppConfig>() {}.type
            val config = gson.fromJson<AppConfig>(file.readText(), type)
            if (config == null || config.connections == null || config.panels == null) {
                Log.e(TAG, "Parse ${file.name}: некорректная структура")
                null
            } else config
        } catch (e: Exception) {
            Log.e(TAG, "Parse ${file.name} error", e)
            null
        }
    }

    private fun refreshBackup(main: java.io.File, backup: java.io.File) {
        try {
            if (!backup.exists() || backup.length() != main.length()) {
                main.copyTo(backup, overwrite = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backup error", e)
        }
    }

    /** Гарантирует уникальность id после импорта/загрузки */
    private fun AppConfig.fixIds(): AppConfig {
        var cid = 0L
        val fixedConnections = connections.map {
            // Gson не вызывает конструктор: в конфигах до 0.2.19 полей narodmon/apiKey
            // нет, и они физически null despite non-null типы — чистим здесь
            it.copy(narodmon = it.narodmon ?: false, apiKey = it.apiKey ?: "").copy(id = ++cid)
        }
        // ClientID уникален для каждого устройства: брокер разрывает
        // соединение клиента с уже занятым ID, поэтому два приложения,
        // развёрнутые из одной резервной копии, флапают, выбивая друг
        // друга. Раньше ID менялся только если поле пустое — а в копии
        // оно всегда заполнено, и дубликат выживал. Сессии у нас не
        // хранятся (cleanSession), поэтому новый ID при каждом запуске/
        // импорте безопасен. Народмон ходит через REST, MQTT-ID не нужен.
        val fixedConnections2 = fixedConnections.map {
            if (it.narodmon) it else it.copy(
                clientId = "mqttpanel-" + java.util.UUID.randomUUID().toString().take(8)
            )
        }
        var pid = 0L
        val oldToNew = connections.mapIndexed { i, c -> c.id to fixedConnections2[i].id }.toMap()
        val fixedPanels = panels.map {
            it.copy(
                id = ++pid,
                connectionId = oldToNew[it.connectionId] ?: it.connectionId,
                // Gson не вызывает конструктор: в конфигах до 0.2.9 поля series нет,
                // и despite non-null тип оно физически null — чистим здесь
                series = it.series ?: emptyList(),
                source = it.source ?: "mqtt",
                showTime = it.showTime ?: true,
                lastTime = it.lastTime ?: 0L,
                colorMin = it.colorMin ?: "",
                colorMid = it.colorMid ?: "",
                colorMax = it.colorMax ?: "",
                // конфиги из 0.2.36–0.2.47 могли хранить в historyPeriod текст-метку
                // («Сутки») — редактор писал её при сохранении без выбора периода
                historyPeriod = it.historyPeriod?.takeIf { p ->
                    p == "day" || p == "week" || p == "month" || p == "year"
                } ?: "day",
                // Gson не вызывает конструктор: в конфигах до 0.2.51 gh-полей нет,
                // физически null — это штатно; чистим только пустые строки
                ghMeasurement = it.ghMeasurement?.trim()?.takeIf { m -> m.isNotEmpty() },
                ghField = it.ghField?.trim()?.takeIf { f -> f.isNotEmpty() },
                ghTagKey = it.ghTagKey?.trim()?.takeIf { t -> t.isNotEmpty() }
            )
        }
        return AppConfig(fixedConnections2, fixedPanels)
    }

    suspend fun getConnections(): List<MqttConnection> = withContext(Dispatchers.IO) {
        load().connections
    }

    suspend fun getConnection(id: Long): MqttConnection? = withContext(Dispatchers.IO) {
        load().connections.find { it.id == id }
    }

    suspend fun getPanels(connectionId: Long): List<Panel> = withContext(Dispatchers.IO) {
        load().panels.filter { it.connectionId == connectionId }
            // Порядок карточек: сохранённый sortOrder, новые — в конце по id
            .sortedWith(compareBy({ if (it.sortOrder > 0) 0 else 1 }, { it.sortOrder }, { it.id }))
    }

    /** Все панели (для счётчиков в списке подключений) */
    suspend fun getAllPanels(): List<Panel> = withContext(Dispatchers.IO) {
        load().panels
    }

    suspend fun addConnection(connection: MqttConnection): Long = withContext(Dispatchers.IO) {
        val config = load()
        val id = (config.connections.maxOfOrNull { it.id } ?: 0L) + 1
        save(config.copy(connections = config.connections + connection.copy(id = id)))
        id
    }

    suspend fun updateConnection(connection: MqttConnection) = withContext(Dispatchers.IO) {
        val config = load()
        save(config.copy(connections = config.connections.map {
            if (it.id == connection.id) connection else it
        }))
    }

    suspend fun deleteConnection(id: Long) = withContext(Dispatchers.IO) {
        val config = load()
        deleteRuntime(context, id)
        save(
            config.copy(
                connections = config.connections.filterNot { it.id == id },
                panels = config.panels.filterNot { it.connectionId == id }
            )
        )
    }

    suspend fun addPanel(panel: Panel): Long = withContext(Dispatchers.IO) {
        val config = load()
        val id = (config.panels.maxOfOrNull { it.id } ?: 0L) + 1
        save(config.copy(panels = config.panels + panel.copy(id = id)))
        id
    }

    suspend fun updatePanel(panel: Panel) = withContext(Dispatchers.IO) {
        val config = load()
        save(config.copy(panels = config.panels.map {
            if (it.id == panel.id) panel else it
        }))
    }

    suspend fun deletePanel(id: Long) = withContext(Dispatchers.IO) {
        val config = load()
        save(config.copy(panels = config.panels.filterNot { it.id == id }))
    }

    /** Сохранение порядка карточек (режим перемещения) */
    suspend fun savePanelOrder(ordered: List<Panel>) = withContext(Dispatchers.IO) {
        val config = load()
        val order = ordered.withIndex().associate { (i, p) -> p.id to (i + 1) }
        save(config.copy(panels = config.panels.map { p ->
            val o = order[p.id]
            if (o != null && p.sortOrder != o) p.copy(sortOrder = o) else p
        }))
    }

    private fun save(config: AppConfig) = synchronized(lock) {
        cache = config
        try {
            val json = gson.toJson(config)
            val file = java.io.File(context.filesDir, FILE_NAME)
            // Атомарная запись: сканер добавляет панели пачкой и несколько
            // save() могли перерезать друг друга посередине файла
            val tmp = java.io.File(context.filesDir, FILE_NAME + ".tmp")
            tmp.writeText(json)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "save: не удалось удалить старый config.json")
            }
            if (!tmp.renameTo(file)) tmp.writeText(json).let { file.writeText(json) }
            java.io.File(context.filesDir, BACKUP_NAME).writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Save error", e)
        }
    }

    /**
     * Экспорт конфигурации в JSON-строку (для выгрузки в файл)
     */
    suspend fun exportConfig(): String = withContext(Dispatchers.IO) {
        gson.toJson(load())
    }

    // --- кэш последних значений и истории графиков (runtime-<id>.json) ---
    // Отдельный файл, чтобы мгновенно показывать последние значения и графики
    // при открытии дашборда, не дожидаясь broker/платформы. Пишется при уходе
    // с экрана и раз в минуту; в config.json эти данные не попадают.

    /** Последнее значение панели или серии мультиграфика */
    data class RuntimeValue(
        val last: String = "",
        val time: Long = 0,
        /** история точек графиков: [[время_мс, значение], ...] */
        val history: List<List<Double>> = emptyList(),
        /** когда последний раз тянули историю из Народмона (мс) */
        val fetchedAt: Long = 0,
        /** за какой период тянули историю (day/week/month) */
        val period: String = ""
    )

    private fun runtimeFile(context: Context, connectionId: Long): java.io.File =
        java.io.File(context.filesDir, "runtime-$connectionId.json")

    suspend fun saveRuntimeValues(connectionId: Long, values: Map<String, RuntimeValue>) =
        withContext(Dispatchers.IO) {
            try {
                val file = runtimeFile(context, connectionId)
                val tmp = java.io.File(context.filesDir, "runtime-$connectionId.json.tmp")
                tmp.writeText(gson.toJson(values))
                if (file.exists() && !file.delete()) Log.w(TAG, "saveRuntime: не удалось удалить старый файл")
                if (!tmp.renameTo(file)) file.writeText(gson.toJson(values))
            } catch (e: Exception) {
                Log.e(TAG, "saveRuntimeValues error", e)
            }
        }

    suspend fun loadRuntimeValues(connectionId: Long): Map<String, RuntimeValue> =
        withContext(Dispatchers.IO) {
            val file = runtimeFile(context, connectionId)
            if (!file.exists()) return@withContext emptyMap()
            try {
                val type = object : TypeToken<Map<String, RuntimeValue>>() {}.type
                gson.fromJson<Map<String, RuntimeValue>>(file.readText(), type) ?: emptyMap()
            } catch (e: Exception) {
                Log.e(TAG, "loadRuntimeValues error", e)
                emptyMap()
            }
        }

    /** Удалить кэш вместе с подключением */
    private fun deleteRuntime(context: Context, connectionId: Long) {
        runCatching { runtimeFile(context, connectionId).delete() }
    }

    /**
     * Импорт конфигурации из JSON-строки. Полностью заменяет текущую.
     */
    suspend fun importConfig(json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val type = object : TypeToken<AppConfig>() {}.type
            val config: AppConfig = gson.fromJson(json, type) ?: return@withContext false
            // Проверяем минимальную валидность
            if (config.connections.any { it.host.isBlank() }) return@withContext false
            save(config.fixIds())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import error", e)
            false
        }
    }
}
