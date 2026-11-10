package ru.mqtt.panel.mqtt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.mqtt.panel.R
import ru.mqtt.panel.data.MqttConnection
import ru.mqtt.panel.data.NarodmonApi
import ru.mqtt.panel.data.ImportLog
import ru.mqtt.panel.data.Panel
import ru.mqtt.panel.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-сервис поллинга Народного Мониторинга — аналог MqttService для
 * подключений типа narodmon. Раз в минуту одним пакетом sensorsValues тянет
 * значения всех датчиков панелей и рассылает их в UI тем же broadcast'ом,
 * что и MQTT (EXTRA_MESSAGE_TOPIC = sensorId), поэтому маршрутизация
 * значений в WidgetAdapter и графики работают без изменений.
 *
 * Лимит платформы 1 запрос/мин: после 429 интервал удваивается (до 10 мин),
 * при успехе возвращается к 60 с.
 */
class NarodmonService : Service() {

    companion object {
        private const val TAG = "NarodmonService"
        private const val CHANNEL_ID = "narodmon_service"
        private const val NOTIFICATION_ID = 2

        const val ACTION_CONNECT = "ru.mqtt.panel.NM_CONNECT"
        const val ACTION_DISCONNECT = "ru.mqtt.panel.NM_DISCONNECT"
        const val ACTION_SYNC_PANELS = "ru.mqtt.panel.NM_SYNC_PANELS"
        const val EXTRA_CONNECTION = "connection"
        const val EXTRA_PANELS = "panels"

        @Volatile
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED

        @Volatile
        var activeConnectionId: Long = -1L

        @Volatile
        var connectedAt: Long = 0L

        fun uptimeSeconds(): Long =
            if (connectedAt == 0L) -1L else (System.currentTimeMillis() - connectedAt) / 1000L
    }

    private var connection: MqttConnection? = null
    private val panels = java.util.concurrent.CopyOnWriteArrayList<Panel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val polling = AtomicBoolean(false)
    private var backoff = 1

    private val pollLoop = object : Runnable {
        override fun run() {
            pollOnce()
            handler.postDelayed(this, 60_000L * backoff)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val conn = intent.getSerializableExtra(EXTRA_CONNECTION) as? MqttConnection
                    ?: return START_NOT_STICKY
                @Suppress("UNCHECKED_CAST")
                val list = intent.getSerializableExtra(EXTRA_PANELS) as? ArrayList<Panel> ?: ArrayList()
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.narodmon_polling)))
                connection = conn
                activeConnectionId = conn.id
                panels.clear()
                panels.addAll(list)
                backoff = 1
                connectionState = ConnectionState.CONNECTING
                broadcastStatus(ConnectionState.CONNECTING)
                scope.launch {
                    // приватные датчики доступны после суточного userLogon
                    runCatching {
                        NarodmonApi.ensureLogon(applicationContext, conn.apiKey, conn.username, conn.password)
                    }.onFailure { Log.w(TAG, "logon failed", it) }
                    handler.removeCallbacks(pollLoop)
                    handler.post(pollLoop)
                }
            }
            ACTION_SYNC_PANELS -> {
                @Suppress("UNCHECKED_CAST")
                val list = intent.getSerializableExtra(EXTRA_PANELS) as? ArrayList<Panel> ?: ArrayList()
                panels.clear()
                panels.addAll(list)
            }
            ACTION_DISCONNECT -> {
                handler.removeCallbacks(pollLoop)
                scope.launch {
                    connection = null
                    activeConnectionId = -1L
                    panels.clear()
                    connectionState = ConnectionState.DISCONNECTED
                    broadcastStatus(ConnectionState.DISCONNECTED)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Один пакет sensorsValues на все датчики панелей (значение → broadcast) */
    private fun pollOnce() {
        if (!polling.compareAndSet(false, true)) return
        val conn = connection
        if (conn == null || panels.isEmpty()) {
            polling.set(false)
            return
        }
        scope.launch {
            try {
                // один запрос на все свои датчики: sensorsNearby?my=1
                // (sensorsValues на 06.11.2026 отдаёт 410 Gone — см. NarodmonApi)
                val sensors = NarodmonApi.fetchOwnSensors(applicationContext, conn.apiKey)
                deliver(conn, sensors)
            } catch (e: NarodmonApi.AuthRequiredException) {
                // 401 «Авторизуйтесь»: сессия слетела на платформе (её сбрасывают,
                // например, при создании нового API-ключа). ensureLogon при старте
                // сервиса throttle'ится раз в сутки и тут уже не сработает —
                // нужен форс-логин, не чаще раза в 5 минут: неверный пароль
                // не должен сжигать попытки входа каждый опрос.
                val canLogon = conn.username.isNotBlank() && conn.password.isNotBlank()
                if (!canLogon) {
                    ImportLog.write(applicationContext,
                        "NARODMON poll: 401 «${e.message}» — заполните логин/пароль аккаунта в подключении")
                    connectionState = ConnectionState.CONNECTING
                    broadcastStatus(ConnectionState.CONNECTING)
                } else {
                    val relogged = System.currentTimeMillis() - lastForceLogon > 5 * 60_000L &&
                        runCatching {
                            lastForceLogon = System.currentTimeMillis()
                            NarodmonApi.ensureLogon(applicationContext, conn.apiKey,
                                conn.username, conn.password, force = true)
                        }.isSuccess
                    if (relogged) {
                        ImportLog.write(applicationContext,
                            "NARODMON poll: 401 — сессия слетела, повторный userLogon ok, пробую снова")
                        deliver(conn, NarodmonApi.fetchOwnSensors(applicationContext, conn.apiKey))
                    } else {
                        ImportLog.write(applicationContext,
                            "NARODMON poll failed: ${e.javaClass.simpleName}: ${e.message} (повторный логин отложен/не удался)")
                        connectionState = ConnectionState.CONNECTING
                        broadcastStatus(ConnectionState.CONNECTING)
                    }
                }
            } catch (e: NarodmonApi.RateLimitException) {
                backoff = (backoff * 2).coerceAtMost(10)
                Log.w(TAG, "429 — интервал опроса x$backoff")
                ImportLog.write(applicationContext, "NARODMON poll: лимит платформы (429), интервал опроса x$backoff")
                connectionState = ConnectionState.CONNECTING
                broadcastStatus(ConnectionState.CONNECTING)
            } catch (e: Exception) {
                // причина в журнал: пользователь без adb увидит её в mqtt-import.log
                ImportLog.write(applicationContext, "NARODMON poll failed: ${e.javaClass.simpleName}: ${e.message}")
                Log.w(TAG, "poll failed", e)
                connectionState = ConnectionState.CONNECTING
                broadcastStatus(ConnectionState.CONNECTING)
            } finally {
                polling.set(false)
            }
        }
    }

    /** Время последней попытки форс-логина — троттлинг повторных входов при 401 */
    private var lastForceLogon = 0L

    /** Разослать значения датчиков: общий код первого опроса и повтора после релогина */
    private fun deliver(conn: MqttConnection, sensors: List<NarodmonApi.NmSensor>) {
        connectedAt = System.currentTimeMillis()
        connectionState = ConnectionState.CONNECTED
        backoff = 1
        broadcastStatus(ConnectionState.CONNECTED)
        val lm = LocalBroadcastManager.getInstance(this@NarodmonService)
        sensors.forEach { s ->
            val payload = NarodmonApi.fmt(s.value!!)
            // время платформы (сек) идёт в график: точки встают на шкалу времени
            val timeMs = if (s.time > 0) s.time * 1000L else System.currentTimeMillis()
            // Реестр тем сканера не пишем: датчики выбираются списком, не сканом
            lm.sendBroadcast(Intent(MqttService.BROADCAST_MESSAGE).apply {
                putExtra(MqttService.EXTRA_MESSAGE_TOPIC, s.id.toString())
                putExtra(MqttService.EXTRA_MESSAGE_PAYLOAD, payload)
                putExtra(MqttService.EXTRA_MESSAGE_TIME, timeMs)
            })
        }
    }

    private fun broadcastStatus(state: ConnectionState) {
        // тот же broadcast, что и у MqttService: статусы видят оба экрана
        val intent = Intent(MqttService.BROADCAST_STATUS).apply {
            putExtra(MqttService.EXTRA_STATUS, state.name)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.narodmon_account),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.narodmon_account))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mqtt)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollLoop)
        scope.cancel()
        super.onDestroy()
    }
}

fun Context.startNarodmon(connection: MqttConnection, panels: List<Panel>) {
    val intent = Intent(this, NarodmonService::class.java).apply {
        action = NarodmonService.ACTION_CONNECT
        putExtra(NarodmonService.EXTRA_CONNECTION, connection)
        putExtra(NarodmonService.EXTRA_PANELS, ArrayList(panels))
    }
    startForegroundService(intent)
}

fun Context.stopNarodmon() {
    val intent = Intent(this, NarodmonService::class.java).apply {
        action = NarodmonService.ACTION_DISCONNECT
    }
    startService(intent)
}

fun Context.syncNarodmonPanels(panels: List<Panel>) {
    val intent = Intent(this, NarodmonService::class.java).apply {
        action = NarodmonService.ACTION_SYNC_PANELS
        putExtra(NarodmonService.EXTRA_PANELS, ArrayList(panels))
    }
    startService(intent)
}
