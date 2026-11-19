package ru.mqtt.panel.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import ru.mqtt.panel.R
import ru.mqtt.panel.data.ImportLog
import ru.mqtt.panel.data.MqttConnection
import ru.mqtt.panel.data.Panel
import ru.mqtt.panel.data.TopicRegistry
import ru.mqtt.panel.data.WidgetType
import ru.mqtt.panel.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Фоновый сервис MQTT-подключений.
 *
 * Все ВКЛЮЧЁННЫЕ MQTT-подключения живут ПАРАЛЛЕЛЬНО — по собственному
 * MqttClient на каждое (раньше сервис держал одно соединение и переподключался
 * при смене дашборда). Выключатель на карточке главного экрана включает и
 * выключает именно «своего» клиента, дашборды открываются поверх уже живого
 * соединения. Народмон живёт в собственном NarodmonService и здесь не участвует.
 */
class MqttService : Service() {

    companion object {
        private const val TAG = "MqttService"
        const val CHANNEL_ID = "mqtt_service"
        const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "ru.mqtt.panel.CONNECT"
        const val ACTION_DISCONNECT = "ru.mqtt.panel.DISCONNECT"
        /** Закрыть ОДНО подключение (выключатель на карточке / выход из дашборда выключенного) */
        const val ACTION_DISCONNECT_CONN = "ru.mqtt.panel.DISCONNECT_CONN"
        const val ACTION_SYNC_PANELS = "ru.mqtt.panel.SYNC_PANELS"
        const val ACTION_PUBLISH = "ru.mqtt.panel.PUBLISH"
        const val EXTRA_CONNECTION = "connection"
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_PANELS = "panels"
        const val EXTRA_TOPIC = "topic"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_QOS = "qos"
        const val EXTRA_RETAINED = "retained"
        const val EXTRA_FORCE = "force"

        const val BROADCAST_MESSAGE = "ru.mqtt.panel.MESSAGE"
        const val BROADCAST_STATUS = "ru.mqtt.panel.STATUS"
        const val EXTRA_MESSAGE_TOPIC = "message_topic"
        const val EXTRA_MESSAGE_PAYLOAD = "message_payload"
        /** время передачи сообщения (мс); Народмон передаёт время платформы */
        const val EXTRA_MESSAGE_TIME = "message_time"
        const val EXTRA_STATUS = "status"

        const val ACTION_SCAN_START = "ru.mqtt.panel.SCAN_START"
        const val ACTION_SCAN_STOP = "ru.mqtt.panel.SCAN_STOP"
        const val BROADCAST_SCANNED = "ru.mqtt.panel.SCANNED"
        const val EXTRA_SCANNED_TOPICS = "scanned_topics"
        const val EXTRA_SCANNED_PAYLOADS = "scanned_payloads"

        /** Тема, подписка на которую раскрывает всё дерево брокера */
        const val SCAN_FILTER = "#"

        /** Реестр observed-топиков живёт в процессе: сканер читает его напрямую */
        val topics = TopicRegistry()

        /** Состояние каждого подключения (connId -> state) — снапшот для UI */
        @Volatile
        var states: Map<Long, ConnectionState> = emptyMap()
            private set

        /** id подключения чей дашборд/сканер сейчас на экране (для совместимости
         *  статусов тулбара; клиенты при этом живут все) */
        @Volatile
        var activeConnectionId: Long = -1L
            private set

        /** Момент соединения по каждому подключению — «время в сети» тулбара */
        @Volatile
        private var connectedAtMap: Map<Long, Long> = emptyMap()

        fun stateOf(connectionId: Long): ConnectionState? = states[connectionId]

        fun uptimeSecondsOf(connectionId: Long): Long {
            val at = connectedAtMap[connectionId] ?: return -1L
            return (System.currentTimeMillis() - at) / 1000L
        }

        /** Совместимость со старым UI: состояние активного подключения */
        val connectionState: ConnectionState
            get() = states[activeConnectionId] ?: ConnectionState.DISCONNECTED

        fun uptimeSeconds(): Long = uptimeSecondsOf(activeConnectionId)
    }

    /** Один параллельный клиент: подключение + его панели и подписки */
    private class Entry(
        val conn: MqttConnection,
        val panels: CopyOnWriteArrayList<Panel> = CopyOnWriteArrayList(),
        /** topic -> панели, подписанные на тему */
        val topicMap: ConcurrentHashMap<String, MutableList<Panel>> = ConcurrentHashMap(),
        @Volatile var client: MqttClient? = null,
        @Volatile var state: ConnectionState = ConnectionState.CONNECTING,
        @Volatile var connectedAtMs: Long = 0L,
        @Volatile var demoFuture: java.util.concurrent.ScheduledFuture<*>? = null
    ) {
        fun buildTopicMap() {
            topicMap.clear()
            for (panel in panels) {
                // Народмон-панели опрашивает NarodmonService — MQTT их не подписывает
                if (panel.source == "narodmon") continue
                // Мультиграфик подписан на темы своих серий, а не на topicSubscribe
                if (panel.type == WidgetType.MULTI_CHART) {
                    panel.series.forEach { s ->
                        if (s.topic.isNotBlank()) topicMap.getOrPut(s.topic) { mutableListOf() }.add(panel)
                    }
                    continue
                }
                val topic = panel.topicSubscribe
                if (topic.isBlank()) continue
                topicMap.getOrPut(topic) { mutableListOf() }.add(panel)
            }
        }
    }

    /** Колбэк конкретного клиента: Paho не сообщает, какой клиент вызвал */
    private inner class ClientCallback(val entry: Entry) : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) =
            this@MqttService.connectComplete(entry, reconnect, serverURI)
        override fun connectionLost(cause: Throwable?) =
            this@MqttService.connectionLost(entry, cause)
        override fun messageArrived(topic: String?, message: MqttMessage?) =
            this@MqttService.messageArrived(entry, topic, message)
        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** connId -> время, до которого НЕ ретраим подключение после отказа
     *  авторизации: брокер принял TCP, но отклонил логин/пароль (CONNACK 4/5).
     *  Ретраи каждые несколько секунд = «флап» жёлтый/красный; creds сами
     *  не починятся — даём пользователю 5 минут на правку настроек */
    private val authFailedUntil = ConcurrentHashMap<Long, Long>()

    /**
     * Подписка/отписка выполняется вне callback-потоков Paho: блокирующий
     * subscribe() ждёт SUBACK, а SUBACK обрабатывает тот же поток — отсюда
     * «Timed out waiting for a response». Один воркер сериализует connects/
     * subscribes всех клиентов — параллельность даёт не поток, а отдельный
     * MqttClient на каждое подключение.
     */
    private val mqttWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Демо-режим (первый запуск): показания публикует само приложение */
    private val demoExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "demo-publisher")
    }

    /** Сканер топиков: пока включён, держим подписку на `#` у сканируемого клиента */
    @Volatile
    private var scanning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val conn = intent.getSerializableExtra(EXTRA_CONNECTION) as? MqttConnection
                @Suppress("UNCHECKED_CAST")
                val panelList = intent.getSerializableExtra(EXTRA_PANELS) as? ArrayList<Panel> ?: ArrayList()
                val force = intent.getBooleanExtra(EXTRA_FORCE, true)
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.connecting)))
                mqttWorker.submit { ensureConnected(conn, panelList, force) }
            }
            ACTION_SYNC_PANELS -> {
                @Suppress("UNCHECKED_CAST")
                val panelList = intent.getSerializableExtra(EXTRA_PANELS) as? ArrayList<Panel> ?: ArrayList()
                val connId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
                mqttWorker.submit { syncPanels(connId, panelList) }
            }
            ACTION_PUBLISH -> {
                val topic = intent.getStringExtra(EXTRA_TOPIC)
                val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: ""
                val connId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
                if (topic != null) publish(connId, topic, payload,
                    intent.getIntExtra(EXTRA_QOS, 0), intent.getBooleanExtra(EXTRA_RETAINED, false))
            }
            ACTION_SCAN_START -> {
                val conn = intent.getSerializableExtra(EXTRA_CONNECTION) as? MqttConnection
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.scan_title)))
                startScan(conn)
            }
            ACTION_SCAN_STOP -> stopScan()
            ACTION_DISCONNECT_CONN -> {
                val connId = intent.getLongExtra(EXTRA_CONNECTION_ID, -1L)
                mqttWorker.submit { disconnectConn(connId) }
                maybeStop()
            }
            ACTION_DISCONNECT -> {
                mqttWorker.submit { disconnectAll() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Нет клиентов и сканера — сервису нечего держать */
    private fun maybeStop() {
        if (entries.isEmpty() && !scanning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    /**
     * Подключить (или переиспользовать) клиента подключения. Уже живое
     * соединение НЕ рвётся — только дообновляются панели и подписки
     * (раньше смена дашборда переподключалась с нуля).
     */
    private fun ensureConnected(conn: MqttConnection?, panelList: List<Panel>, force: Boolean = false) {
        if (conn == null) return
        activeConnectionId = conn.id
        // после отказа авторизации не дёргаем брокера 5 минут (явное действие
        // пользователя — открытие дашборда/выключатель — пробует сразу: вдруг
        // креды уже исправлены)
        val until = authFailedUntil[conn.id]
        if (!force && until != null && System.currentTimeMillis() < until) {
            ImportLog.write(this, "SKIP ${conn.name}: отказ авторизации, повтор через " +
                "${(until - System.currentTimeMillis()) / 1000} с")
            entries[conn.id]?.let { setState(it, ConnectionState.DISCONNECTED) }
            return
        }
        val existing = entries[conn.id]
        if (existing != null && existing.client?.isConnected == true) {
            existing.panels.clear()
            existing.panels.addAll(panelList)
            val old = existing.topicMap.keys.toSet()
            existing.buildTopicMap()
            val delta = existing.topicMap.keys - old
            if (delta.isNotEmpty()) subscribeTopics(existing, delta.toList())
            setState(existing, ConnectionState.CONNECTED)
            ImportLog.write(this, "REUSE ${conn.name}: соединение уже живо, панелей=${panelList.size}, +тем=${delta.size}")
            return
        }
        if (existing == null) {
            val e = Entry(conn)
            e.panels.addAll(panelList)
            e.buildTopicMap()
            entries[conn.id] = e
        } else {
            existing.panels.clear()
            existing.panels.addAll(panelList)
            existing.buildTopicMap()
        }
        val entry = entries[conn.id] ?: return
        disconnectClientOf(entry) // недоживший прошлый клиент — закрыть перед новым
        setState(entry, ConnectionState.CONNECTING)

        try {
            val uri = "${conn.getScheme()}://${conn.host}:${conn.port}"
            var clientId = conn.clientId
            if (clientId.isBlank()) clientId = "mqttpanel-" + UUID.randomUUID().toString().take(8)

            ImportLog.write(this, "CONNECT ${conn.name} → $uri clientId=$clientId user=${conn.username.ifBlank { "—" }} keepAlive=${conn.keepAlive} clean=${conn.cleanSession} панелей=${entry.panels.size} тем=${entry.topicMap.size} (параллельно клиентов=${entries.size})")

            val mqttClient = MqttClient(uri, clientId, MemoryPersistence())
            // колбэк привязан к записи: сообщение知道自己 откуда пришло
            mqttClient.setCallback(ClientCallback(entry))
            entry.client = mqttClient
            mqttClient.connect(buildOptions(conn))
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            // CONNACK 4/5 (или текст) — брокер отклонил логин/пароль: ретраить
            // бессмысленно, ставим стабильный красный и понятную подсказку
            val isAuth = (e is org.eclipse.paho.client.mqttv3.MqttException &&
                (e.reasonCode == 4 || e.reasonCode == 5)) ||
                e.message?.contains("authorized", ignoreCase = true) == true ||
                e.message?.contains("Bad user", ignoreCase = true) == true
            if (isAuth) {
                authFailedUntil[conn.id] = System.currentTimeMillis() + 300_000L
                ImportLog.write(this,
                    "AUTH FAILED ${conn.name}: брокер отклонил логин/пароль — " +
                    "исправьте настройки подключения; повтор через 5 минут")
                setState(entry, ConnectionState.DISCONNECTED)
                broadcastStatusText(entry, getString(R.string.auth_failed_hint))
                return
            }
            ImportLog.write(this, "CONNECT FAILED ${conn.name}: ${e.javaClass.simpleName}: ${e.message}")
            setState(entry, ConnectionState.DISCONNECTED)
            broadcastStatusText(entry, "${getString(R.string.mqtt_connect_fail)}: ${e.message}")
        }
    }

    private fun buildOptions(conn: MqttConnection): MqttConnectOptions {
        val options = MqttConnectOptions()
        options.isCleanSession = conn.cleanSession
        options.keepAliveInterval = conn.keepAlive.coerceIn(5, 3600)
        options.isAutomaticReconnect = true
        options.connectionTimeout = 30
        if (conn.username.isNotBlank()) {
            options.userName = conn.username
            options.password = conn.password.toCharArray()
        }
        return options
    }

    /** Обновление состава панелей без переподключения */
    private fun syncPanels(connectionId: Long, panelList: List<Panel>) {
        val entry = entries[connectionId] ?: return
        val oldTopics = entry.topicMap.keys.toSet()
        entry.panels.clear()
        entry.panels.addAll(panelList)
        entry.buildTopicMap()
        val topicsToSubscribe = (entry.topicMap.keys - oldTopics).toList()
        if (topicsToSubscribe.isNotEmpty()) {
            mqttWorker.submit { subscribeTopics(entry, topicsToSubscribe) }
        }
    }

    private fun connectComplete(entry: Entry, reconnect: Boolean, serverURI: String?) {
        Log.i(TAG, "connectComplete ${entry.conn.name} reconnect=$reconnect")
        ImportLog.write(this, "CONNECTED ${entry.conn.name} $serverURI (reconnect=$reconnect), подписок=${entry.topicMap.size}")
        entry.connectedAtMs = System.currentTimeMillis()
        publishStates()
        setState(entry, ConnectionState.CONNECTED)
        // Подписку выносим в отдельный поток, чтобы не вызывать блокирующий
        // subscribe() из callback-потока Paho — это вызывает таймауты.
        mqttWorker.submit { subscribeAll(entry) }
        // Демо-подключение само генерирует показания — иначе пустому публичному
        // брокеру показывать нечего ($SYS и # анонимам закрыты).
        // Маркер — флаг demo: хост может быть любым публичным (emqx/hivemq)
        if (entry.conn.demo) {
            startDemoPublisher(entry)
        }
    }

    /** Публикация демо-показаний каждые 4 с на топики с префиксом демо-конфига */
    private fun startDemoPublisher(entry: Entry) {
        val prefix = entry.panels.firstNotNullOfOrNull {
            ru.mqtt.panel.data.DemoConfig.prefixOfTopic(it.topicSubscribe)
        } ?: entry.panels.firstNotNullOfOrNull { p ->
            p.series.firstNotNullOfOrNull { ru.mqtt.panel.data.DemoConfig.prefixOfTopic(it.topic) }
        } ?: run {
            ImportLog.write(this, "DEMO PUBLISHER: демо-топики не найдены")
            return
        }
        stopDemoPublisher(entry)
        val sensors = ru.mqtt.panel.data.DemoConfig.DemoSensors()
        entry.demoFuture = demoExecutor.scheduleWithFixedDelay({
            val mqttClient = entry.client ?: return@scheduleWithFixedDelay
            if (!mqttClient.isConnected) return@scheduleWithFixedDelay
            try {
                sensors.tick().forEach { (suffix, payload) ->
                    publish(entry.conn.id, "$prefix/$suffix", payload, 0, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Demo publish error", e)
            }
        }, 800, 4000, java.util.concurrent.TimeUnit.MILLISECONDS)
        ImportLog.write(this, "DEMO PUBLISHER: запущен, $prefix каждые 4 с")
    }

    private fun stopDemoPublisher(entry: Entry) {
        entry.demoFuture?.cancel(false)
        entry.demoFuture = null
    }

    private fun subscribeAll(entry: Entry) {
        ImportLog.write(this, "SUBSCRIBE REQUEST ${entry.conn.name}: ${entry.topicMap.size} тем")
        subscribeTopics(entry, entry.topicMap.keys.toList())
        // После (пере)подключения возвращаем сканирующую подписку, если скан активен
        if (scanning && entry.conn.id == activeConnectionId) subscribeScanFilter(entry)
    }

    /**
     * Сканер топиков: подписка на `#` у сканируемого подключения. Живое
     * соединение переиспользуется, отсутствующее — поднимается с пустым
     * списком панелей (экран вернётся к панелям сам, когда скан закроют).
     */
    private fun startScan(conn: MqttConnection?) {
        if (scanning) return
        scanning = true
        if (conn != null) topics.clear(conn.id)
        ImportLog.write(this, "SCAN START conn=${conn?.id}")
        if (conn == null) {
            ImportLog.write(this, "SCAN: нет подключения")
            return
        }
        mqttWorker.submit {
            ensureConnected(conn, emptyList())
            entries[conn.id]?.let { subscribeScanFilter(it) }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        ImportLog.write(this, "SCAN STOP: найдено ${topics.size} топиков")
        mqttWorker.submit {
            entries[activeConnectionId]?.let { entry ->
                try {
                    entry.client?.unsubscribe(SCAN_FILTER)
                } catch (e: Exception) {
                    Log.w(TAG, "Unsubscribe scan failed", e)
                }
            }
        }
    }

    private fun subscribeScanFilter(entry: Entry) {
        val mqttClient = entry.client ?: return
        try {
            mqttClient.subscribe(SCAN_FILTER, 0)
            ImportLog.write(this, "SUBSCRIBED '$SCAN_FILTER' — сканер запущен (${entry.conn.name})")
        } catch (e: Exception) {
            ImportLog.write(this, "SCAN SUBSCRIBE FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Пакетная подписка одним SUBSCRIBE-пакетом. Вызывается только из
     * mqttWorker — из callback-потока Paho блокирующий subscribe привёл бы
     * к «Timed out waiting for a response from the server».
     */
    private fun subscribeTopics(entry: Entry, topics: List<String>) {
        if (topics.isEmpty()) return
        val mqttClient = entry.client ?: return
        val arr = topics.toTypedArray()
        val qos = IntArray(arr.size) { entry.topicMap[topics[it]]?.firstOrNull()?.qos ?: 0 }
        try {
            mqttClient.subscribe(arr, qos)
            ImportLog.write(this, "SUBSCRIBED ${entry.conn.name} ${arr.size}: ${arr.joinToString(", ")}")
        } catch (e: Exception) {
            Log.w(TAG, "Batch subscribe failed, retry one by one", e)
            var ok = 0
            arr.forEachIndexed { i, topic ->
                try {
                    mqttClient.subscribe(topic, qos[i])
                    ok++
                } catch (e2: Exception) {
                    ImportLog.write(this, "SUBSCRIBE FAILED $topic: ${e2.javaClass.simpleName}: ${e2.message}")
                }
            }
            ImportLog.write(this, "SUBSCRIBED $ok/${arr.size} (по одной)")
        }
    }

    private fun connectionLost(entry: Entry, cause: Throwable?) {
        Log.w(TAG, "connectionLost ${entry.conn.name}", cause)
        ImportLog.write(this, "CONNECTION LOST ${entry.conn.name}: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        // Paho с automaticReconnect сам восстановит соединение
        setState(entry, ConnectionState.CONNECTING)
    }

    private fun messageArrived(entry: Entry, topic: String?, message: MqttMessage?) {
        val payloadBytes = message?.payload ?: ByteArray(0)
        // Реестр для сканера топиков пишется всегда
        topics.record(topic ?: "", payloadBytes, connectionId = entry.conn.id)
        val intent = Intent(BROADCAST_MESSAGE).apply {
            putExtra(EXTRA_MESSAGE_TOPIC, topic ?: "")
            putExtra(EXTRA_MESSAGE_PAYLOAD, String(payloadBytes))
            putExtra(EXTRA_MESSAGE_TIME, System.currentTimeMillis())
            putExtra(EXTRA_CONNECTION_ID, entry.conn.id)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun publish(connectionId: Long, topic: String, payload: String, qos: Int, retained: Boolean) {
        // приоритет: указанное подключение → активное → любой живой клиент
        val entry = entries[connectionId]
            ?: entries[activeConnectionId]
            ?: entries.values.firstOrNull { it.client?.isConnected == true }
            ?: return
        val mqttClient = entry.client ?: return
        if (!mqttClient.isConnected) return
        try {
            val msg = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
                this.isRetained = retained
            }
            mqttClient.publish(topic, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Publish error", e)
        }
    }

    /** Закрыть одного клиента (выключатель, выход из дашборда выключенного) */
    private fun disconnectConn(connectionId: Long) {
        val entry = entries.remove(connectionId) ?: return
        stopDemoPublisher(entry)
        disconnectClientOf(entry)
        ImportLog.write(this, "DISCONNECT ${entry.conn.name}")
        publishStates()
        broadcastStatus(entry.conn.id, ConnectionState.DISCONNECTED)
    }

    private fun disconnectAll() {
        entries.keys.toList().forEach { disconnectConn(it) }
        activeConnectionId = -1L
    }

    private fun disconnectClientOf(entry: Entry) {
        stopDemoPublisher(entry)
        try {
            entry.client?.let {
                it.setCallback(null)
                if (it.isConnected) it.disconnect(1000)
                it.close(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error", e)
        }
        entry.client = null
    }

    override fun onDestroy() {
        demoExecutor.shutdownNow()
        super.onDestroy()
    }

    // --- статусы: карта состояний + локальная рассылка ---

    private fun setState(entry: Entry, state: ConnectionState) {
        entry.state = state
        publishStates()
        broadcastStatus(entry.conn.id, state)
    }

    private fun publishStates() {
        states = entries.entries.associate { it.key to it.value.state }
        connectedAtMap = entries.entries.associate { it.key to it.value.connectedAtMs }
    }

    private fun broadcastStatus(connectionId: Long, state: ConnectionState) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, state.name)
            putExtra(EXTRA_CONNECTION_ID, connectionId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastStatusText(entry: Entry, text: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, "error:$text")
            putExtra(EXTRA_CONNECTION_ID, entry.conn.id)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateNotification() {
        val connected = states.count { it.value == ConnectionState.CONNECTED }
        val text = getString(R.string.mqtt_parallel_status, connected, states.size)
        startForeground(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mqtt)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

/** Запуск/переиспользование подключения (не рвёт остальные).
 *  force=true — явное действие пользователя: игнорирует 5-минутный
 *  backoff после отказа авторизации (креды могли уже исправить) */
fun Context.startMqttConnect(connection: MqttConnection, panels: List<Panel>, force: Boolean = true) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_CONNECT
        putExtra(MqttService.EXTRA_CONNECTION, connection)
        putExtra(MqttService.EXTRA_PANELS, ArrayList(panels))
        putExtra(MqttService.EXTRA_FORCE, force)
    }
    startForegroundService(intent)
}

/** Закрыть одно подключение (выключатель на карточке) */
fun Context.stopMqttConnection(connectionId: Long) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_DISCONNECT_CONN
        putExtra(MqttService.EXTRA_CONNECTION_ID, connectionId)
    }
    startService(intent)
}

/** Полный останов сервиса (все подключения) */
fun Context.stopMqtt() {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_DISCONNECT
    }
    startService(intent)
}

fun Context.syncMqttPanels(connectionId: Long, panels: List<Panel>) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_SYNC_PANELS
        putExtra(MqttService.EXTRA_CONNECTION_ID, connectionId)
        putExtra(MqttService.EXTRA_PANELS, ArrayList(panels))
    }
    startService(intent)
}

fun Context.publishMqtt(
    connectionId: Long, topic: String, payload: String, qos: Int, retained: Boolean
) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_PUBLISH
        putExtra(MqttService.EXTRA_CONNECTION_ID, connectionId)
        putExtra(MqttService.EXTRA_TOPIC, topic)
        putExtra(MqttService.EXTRA_PAYLOAD, payload)
        putExtra(MqttService.EXTRA_QOS, qos)
        putExtra(MqttService.EXTRA_RETAINED, retained)
    }
    startService(intent)
}

fun Context.startMqttScan(connection: MqttConnection) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_SCAN_START
        putExtra(MqttService.EXTRA_CONNECTION, connection)
    }
    // Скан может стартовать при остановленном сервисе — только foreground-запуск легален на O+
    startForegroundService(intent)
}

fun Context.stopMqttScan() {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_SCAN_STOP
    }
    startService(intent)
}
