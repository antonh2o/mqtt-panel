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
import ru.mqtt.panel.data.MqttTopicFilter
import ru.mqtt.panel.data.Panel
import ru.mqtt.panel.data.TopicRegistry
import ru.mqtt.panel.data.WidgetType
import ru.mqtt.panel.ui.MainActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Фоновый сервис с активным MQTT-подключением.
 * Аналог WatchService из narmon-client: удерживает соединение,
 * автоматически переподключается, рассылает сообщения на UI.
 */
class MqttService : Service(), MqttCallbackExtended {

    companion object {
        private const val TAG = "MqttService"
        const val CHANNEL_ID = "mqtt_service"
        const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "ru.mqtt.panel.CONNECT"
        const val ACTION_DISCONNECT = "ru.mqtt.panel.DISCONNECT"
        const val ACTION_SYNC_PANELS = "ru.mqtt.panel.SYNC_PANELS"
        const val ACTION_PUBLISH = "ru.mqtt.panel.PUBLISH"
        const val EXTRA_CONNECTION = "connection"
        const val EXTRA_PANELS = "panels"
        const val EXTRA_TOPIC = "topic"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_QOS = "qos"
        const val EXTRA_RETAINED = "retained"

        const val BROADCAST_MESSAGE = "ru.mqtt.panel.MESSAGE"
        const val BROADCAST_STATUS = "ru.mqtt.panel.STATUS"
        const val EXTRA_MESSAGE_TOPIC = "message_topic"
        const val EXTRA_MESSAGE_PAYLOAD = "message_payload"
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

        @Volatile
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED
            private set

        /** id подключения, к которому сейчас висит сокет — один сервис держит
         *  только одно соединение, поэтому при смене дашборда нужно переподключиться */
        @Volatile
        var activeConnectionId: Long = -1L
            private set

        /** Момент установки соединения — по нему считаем «время в сети» на статус-баре */
        @Volatile
        var connectedAt: Long = 0L
            private set

        /** Секунды с момента подключения; -1 если соединения нет */
        fun uptimeSeconds(): Long =
            if (connectedAt == 0L) -1L else (System.currentTimeMillis() - connectedAt) / 1000L
    }

    private var client: MqttClient? = null
    private var connection: MqttConnection? = null
    private val panels = CopyOnWriteArrayList<Panel>()
    /** topic -> список панелей, подписанных на эту тему */
    private val topicMap = ConcurrentHashMap<String, MutableList<Panel>>()

    /**
     * Подписка/отписка выполняется вне callback-потока Paho: блокирующий
     * subscribe() ждёт SUBACK, а SUBACK обрабатывает тот же поток — отсюда
     * «Timed out waiting for a response» и разорванное соединение.
     */
    private val mqttWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Сканер топиков: пока включён, держим подписку на `#` */
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
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.connecting)))
                // connect() блокирующий (TCP + CONNACK, таймаут 30 с), а
                // onStartCommand крутится на main-потоке сервиса — при смене
                // дашборда это вешало UI до полного переподключения.
                // Очередь single-thread сериализует переключения.
                mqttWorker.submit { connect(conn, panelList) }
            }
            ACTION_SYNC_PANELS -> {
                @Suppress("UNCHECKED_CAST")
                val panelList = intent.getSerializableExtra(EXTRA_PANELS) as? ArrayList<Panel> ?: ArrayList()
                syncPanels(panelList)
            }
            ACTION_PUBLISH -> {
                val topic = intent.getStringExtra(EXTRA_TOPIC)
                val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: ""
                if (topic != null) publish(topic, payload, intent.getIntExtra(EXTRA_QOS, 0), intent.getBooleanExtra(EXTRA_RETAINED, false))
            }
            ACTION_SCAN_START -> {
                val conn = intent.getSerializableExtra(EXTRA_CONNECTION) as? MqttConnection
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.scan_title)))
                startScan(conn)
            }
            ACTION_SCAN_STOP -> stopScan()
            ACTION_DISCONNECT -> {
                // disconnect(1000) блокирует до секунды — тот же main-поток, что и UI
                mqttWorker.submit { disconnect() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Сканер топиков: подписка на `#` раскрывает всё дерево брокера.
     * Если соединение этого подключения уже есть — просто добавляем подписку,
     * не переподключаясь; иначе поднимаем соединение с пустым списком панелей
     * (экран вернётся к панелям сам, когда скан закроют).
     */
    private fun startScan(conn: MqttConnection?) {
        if (scanning) return
        scanning = true
        // Чистим темы именно этого подключения: иначе в список попадали бы
        // топики с другого брокера, собранные во время предыдущего скана
        if (conn != null) topics.clear(conn.id)
        ImportLog.write(this, "SCAN START conn=${conn?.id}")
        val live = client
        if (live != null && live.isConnected && conn != null && activeConnectionId == conn.id) {
            mqttWorker.submit { subscribeScanFilter() }
        } else if (conn != null) {
            mqttWorker.submit { connect(conn, emptyList()) }
        } else {
            ImportLog.write(this, "SCAN: нет подключения")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        ImportLog.write(this, "SCAN STOP: найдено ${topics.size} топиков")
        mqttWorker.submit {
            try {
                client?.unsubscribe(SCAN_FILTER)
            } catch (e: Exception) {
                Log.w(TAG, "Unsubscribe scan failed", e)
            }
        }
    }

    private fun subscribeScanFilter() {
        val mqttClient = client ?: return
        try {
            mqttClient.subscribe(SCAN_FILTER, 0)
            ImportLog.write(this, "SUBSCRIBED '$SCAN_FILTER' — сканер запущен")
        } catch (e: Exception) {
            ImportLog.write(this, "SCAN SUBSCRIBE FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun connect(conn: MqttConnection?, panelList: List<Panel>) {
        if (conn == null) return
        disconnectClient()
        connection = conn
        activeConnectionId = conn.id
        panels.clear()
        panels.addAll(panelList)
        buildTopicMap()

        connectionState = ConnectionState.CONNECTING
        broadcastStatus(ConnectionState.CONNECTING)

        try {
            val uri = "${conn.getScheme()}://${conn.host}:${conn.port}"
            // BROKER рвёт старое соединение при дубле ClientID — два устройства
            // с импортированным конфигом выбивали бы друг друга по кругу (flap).
            // Поэтому при импорте генерируется новый ClientID (AppRepository),
            // а здесь — запасной вариант для пустого поля.
            var clientId = conn.clientId
            if (clientId.isBlank()) clientId = "mqttpanel-" + UUID.randomUUID().toString().take(8)

            ImportLog.write(this, "CONNECT ${conn.name} → $uri clientId=$clientId user=${conn.username.ifBlank { "—" }} keepAlive=${conn.keepAlive} clean=${conn.cleanSession} панелей=${panelList.size} тем=${topicMap.size}")

            val mqttClient = MqttClient(uri, clientId, MemoryPersistence())
            mqttClient.setCallback(this)
            // ВАЖНО: назначаем поле client ДО connect() — Paho вызывает
            // connectComplete() синхронно внутри connect(), а там subscribeAll()
            // подписывается через this.client
            client = mqttClient
            mqttClient.connect(buildOptions(conn))
        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            ImportLog.write(this, "CONNECT FAILED ${conn.name}: ${e.javaClass.simpleName}: ${e.message}")
            connectionState = ConnectionState.DISCONNECTED
            broadcastStatus(ConnectionState.DISCONNECTED)
            broadcastStatusText("${getString(R.string.mqtt_connect_fail)}: ${e.message}")
        }
    }

    private fun buildOptions(conn: MqttConnection): MqttConnectOptions {        val options = MqttConnectOptions()
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

    private fun buildTopicMap() {
        topicMap.clear()
        for (panel in panels) {
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

    /** Обновление состава панелей без переподключения */
    private fun syncPanels(panelList: List<Panel>) {
        val oldTopics = topicMap.keys.toSet()
        panels.clear()
        panels.addAll(panelList)
        buildTopicMap()
        val newTopics = topicMap.keys.toSet()
        val topicsToSubscribe = (newTopics - oldTopics).toList()
        if (topicsToSubscribe.isNotEmpty()) {
            mqttWorker.submit {
                subscribeTopics(topicsToSubscribe)
            }
        }
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        Log.i(TAG, "connectComplete reconnect=$reconnect")
        ImportLog.write(this, "CONNECTED $serverURI (reconnect=$reconnect), подписок=${topicMap.size}")
        connectedAt = System.currentTimeMillis()
        connectionState = ConnectionState.CONNECTED
        broadcastStatus(ConnectionState.CONNECTED)
        // Подписку выносим в отдельный поток, чтобы не вызывать блокирующий
        // subscribe() из callback-потока Paho — это вызывает таймауты.
        mqttWorker.submit { subscribeAll() }
    }

    private fun subscribeAll() {
        ImportLog.write(this, "SUBSCRIBE REQUEST: ${topicMap.size} тем")
        subscribeTopics(topicMap.keys.toList())
        // После (пере)подключения возвращаем сканирующую подписку, если скан активен
        if (scanning) subscribeScanFilter()
    }

    /**
     * Пакетная подписка одним SUBSCRIBE-пакетом. Вызывается только из
     * mqttWorker — из callback-потока Paho блокирующий subscribe привёл бы
     * к «Timed out waiting for a response from the server».
     */
    private fun subscribeTopics(topics: List<String>) {
        if (topics.isEmpty()) return
        val mqttClient = client ?: return
        val arr = topics.toTypedArray()
        val qos = IntArray(arr.size) { topicMap[topics[it]]?.firstOrNull()?.qos ?: 0 }
        try {
            mqttClient.subscribe(arr, qos)
            ImportLog.write(this, "SUBSCRIBED ${arr.size}: ${arr.joinToString(", ")}")
        } catch (e: Exception) {
            // Массивом не вышло — по одной, чтобы не потерять остальные
            Log.w(TAG, "Batch subscribe failed, retrying one by one", e)
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

    override fun connectionLost(cause: Throwable?) {
        Log.w(TAG, "connectionLost", cause)
        ImportLog.write(this, "CONNECTION LOST: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        // Paho с automaticReconnect сам восстановит соединение
        connectionState = ConnectionState.CONNECTING
        broadcastStatus(ConnectionState.CONNECTING)
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val payloadBytes = message?.payload ?: ByteArray(0)
        // Реестр для сканера топиков пишется всегда: когда пользователь открывает
        // сканер, уже подписанные темы у него на виду, а не пустой экран
        topics.record(topic ?: "", payloadBytes, connectionId = activeConnectionId)
        val intent = Intent(BROADCAST_MESSAGE).apply {
            putExtra(EXTRA_MESSAGE_TOPIC, topic ?: "")
            putExtra(EXTRA_MESSAGE_PAYLOAD, String(payloadBytes))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

    fun publish(topic: String, payload: String, qos: Int, retained: Boolean) {
        val mqttClient = client ?: return
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

    private fun disconnect() {
        disconnectClient()
        connection = null
        activeConnectionId = -1L
        panels.clear()
        topicMap.clear()
        connectionState = ConnectionState.DISCONNECTED
        broadcastStatus(ConnectionState.DISCONNECTED)
    }

    private fun disconnectClient() {
        try {
            client?.let {
                it.setCallback(null)
                if (it.isConnected) it.disconnect(1000)
                it.close(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error", e)
        }
        client = null
    }

    private fun broadcastStatus(state: ConnectionState) {
        val intent = Intent(BROADCAST_STATUS).apply { putExtra(EXTRA_STATUS, state.name) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastStatusText(text: String) {
        val intent = Intent(BROADCAST_STATUS).apply { putExtra(EXTRA_STATUS, "error:$text") }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
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

/** Запуск сервиса подключения */
fun Context.startMqttConnect(connection: MqttConnection, panels: List<Panel>) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_CONNECT
        putExtra(MqttService.EXTRA_CONNECTION, connection)
        putExtra(MqttService.EXTRA_PANELS, ArrayList(panels))
    }
    startForegroundService(intent)
}

fun Context.stopMqtt() {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_DISCONNECT
    }
    startService(intent)
}

fun Context.syncMqttPanels(panels: List<Panel>) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_SYNC_PANELS
        putExtra(MqttService.EXTRA_PANELS, ArrayList(panels))
    }
    startService(intent)
}

fun Context.publishMqtt(topic: String, payload: String, qos: Int, retained: Boolean) {
    val intent = Intent(this, MqttService::class.java).apply {
        action = MqttService.ACTION_PUBLISH
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
