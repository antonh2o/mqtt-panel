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
        /** Список ID датчиков, помеченных звёздочкой на платформе (fav=1) */
        const val BROADCAST_FAVORITES = "ru.mqtt.panel.NM_FAVORITES"
        const val EXTRA_FAVORITE_IDS = "favoriteIds"
        /** Устройство для sensorsOnDevice найдено, датчик панели — там:
         *  список пар «sensorId → deviceName» (диагностика «датчика нет в my=1») */
        const val EXTRA_DEVICE_SOURCES = "deviceSources"

        @Volatile
        var connectionState: ConnectionState = ConnectionState.DISCONNECTED

        @Volatile
        var activeConnectionId: Long = -1L

        /** Сервис запущен (опрашивает аккаунт) — для светодиода статуса на
         *  главном экране: не запущен ≠ ошибка, точка серая, а не красная */
        @Volatile
        var serviceAlive: Boolean = false

        @Volatile
        var connectedAt: Long = 0L

        fun uptimeSeconds(): Long =
            if (connectedAt == 0L) -1L else (System.currentTimeMillis() - connectedAt) / 1000L

        /** Последние N сообщений для replay при старте DashboardActivity —
         *  аналог MqttService.replayLast. Нужен потому что NarodmonService
         *  запускается в onCreate(), а приёмник регистрируется в onResume() —
         *  первый poll мог случиться раньше регистрации и значения бы потерялись */
        private val lastMessages = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Triple<String, String, Long>>>()

        fun replayLast(connectionId: Long): List<Triple<String, String, Long>> =
            lastMessages[connectionId] ?: emptyList()
    }

    private var connection: MqttConnection? = null
    private val panels = java.util.concurrent.CopyOnWriteArrayList<Panel>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val polling = AtomicBoolean(false)
    private var backoff = 1

    private val pollLoop = object : Runnable {
        override fun run() {
            // значения опрашиваются всегда: пауза на время пакета истории
            // (0.2.78–0.2.80) замораживала карточки на 5–15 минут — см. FIXPLAN
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
                // (sensorsValues на 06.11.2026 отдаёт 410 Gone — см. NarodmonApi).
                // Берём и устройства: их id нужны для sensorsOnDevice, а fav=1
                // у датчика — для фильтра «только избранное» в дашборде
                val snapshot = NarodmonApi.fetchOwnSnapshot(applicationContext, conn.apiKey)
                val sensors = supplementFromDevices(conn, snapshot)
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

    /** Когда последний раз тянули sensorsOnDevice (доп. запрос к лимиту 3/мин) */
    private var lastOnDeviceAt = 0L

    /** Имена устройств, где нашли датчик панели — для журнала */
    private val deviceSources = HashMap<Int, String>()

    /**
     * sensorsNearby?my=1 отдаёт только датчики СВОИХ устройств. Панель может
     * быть настроена на чужой публичный датчик (или на свой, который платформа
     * ещё не включила в «my») — тогда значение к нам не приходит вовсе.
     * Для таких панелей тянем sensorsOnDevice по id устройств из этого же
     * ответа: эндпоинт принимает список устройств и возвращает их датчики
     * вместе с чужими публичными.
     *
     * Экономим лимит платформы (3 запроса/мин, история тоже их тратит):
     * запрос делаем только если есть «пропавшие» панели и не чаще раза в 10 мин.
     */
    private suspend fun supplementFromDevices(
        conn: MqttConnection, snapshot: NarodmonApi.Snapshot
    ): List<NarodmonApi.NmSensor> {
        val have = snapshot.sensors.associateBy { it.id }
        val missing = panels.mapNotNull { p ->
            val id = p.topicSubscribe.toIntOrNull() ?: return@mapNotNull null
            if (have.containsKey(id)) null else id
        }
        if (missing.isEmpty() || snapshot.devices.isEmpty()) return snapshot.sensors
        val now = System.currentTimeMillis()
        if (now - lastOnDeviceAt < 10 * 60_000L) return snapshot.sensors
        lastOnDeviceAt = now
        return try {
            // id устройств из «my»-ответа: среди них ищутся чужие публичные
            val deviceIds = snapshot.devices.map { it.id }.filter { it != 0 }
            val extra = NarodmonApi.sensorsOnDevice(applicationContext, conn.apiKey, deviceIds)
            val merged = LinkedHashMap<Int, NarodmonApi.NmSensor>()
            snapshot.sensors.forEach { merged[it.id] = it }
            extra.filter { it.id != 0 && it.id in missing }.forEach { s ->
                if (!merged.containsKey(s.id)) {
                    merged[s.id] = s
                    deviceSources[s.id] = snapshot.devices
                        .firstOrNull { it.sensors.any { x -> x.id == s.id } }?.name ?: "?"
                }
            }
            val found = extra.map { it.id }.filter { it in missing }
            if (found.isNotEmpty()) {
                ImportLog.write(applicationContext,
                    "NARODMON sensorsOnDevice: датчики панелей найдены на устройствах: " +
                        found.joinToString(", ") { "#$it (${deviceSources[it]})" })
            }
            merged.values.toList()
        } catch (e: Exception) {
            ImportLog.write(applicationContext,
                "NARODMON sensorsOnDevice failed: ${e.javaClass.simpleName}: ${e.message}")
            snapshot.sensors
        }
    }

    /** Разослать значения датчиков: общий код первого опроса и повтора после релога */
    private fun deliver(conn: MqttConnection, sensors: List<NarodmonApi.NmSensor>) {
        // Диагностика «значения не обновляются»: успешный опрос в журнал не
        // писался вовсе, и отличить «платформа молчит по датчику» от «приложение
        // не опрашивает» было невозможно. Раз в 5 минут фиксируем: кого нет
        // в ответе и кого платформа отдаёт устаревшим (см. FIXPLAN, задача 1)
        diagnosePoll(sensors)
        val withValue = sensors.filter { it.value != null }
        // Пустой ответ — НЕ «подключено»: платформа могла отдать пустой список
        // (устройства offline/ошибка разбора), и зелёный статус с таймером
        // маскировал бы застывшие значения. Оставляем CONNECTING и пишем причину
        if (withValue.isEmpty()) {
            ImportLog.write(applicationContext,
                "NARODMON poll: платформа вернула 0 датчиков со значением — значения не обновлены")
            connectionState = ConnectionState.CONNECTING
            broadcastStatus(ConnectionState.CONNECTING)
            return
        }
        // Таймер «в сети» — от первого успешного опроса, а не от каждого:
        // иначе он вечно показывает «1 мин N с» и врёт про длительность сессии
        if (connectedAt == 0L || connectionState != ConnectionState.CONNECTED) {
            connectedAt = System.currentTimeMillis()
        }
        connectionState = ConnectionState.CONNECTED
        backoff = 1
        broadcastStatus(ConnectionState.CONNECTED)
        // Избранное платформы (fav=1 в sensorsNearby) — в дашборд для звёздочек
        // и фильтра «только избранное». Шлём всегда, даже пустым: пустой список
        // означает «на платформе избранного нет», и локальные звёздочки надо снять
        broadcastFavorites(sensors)
        val lm = LocalBroadcastManager.getInstance(this@NarodmonService)
        val msgs = mutableListOf<Triple<String, String, Long>>()
        withValue.forEach { s ->
            val payload = NarodmonApi.fmt(s.value!!)
            // время платформы (сек) идёт в график: точки встают на шкалу времени
            val timeMs = if (s.time > 0) s.time * 1000L else System.currentTimeMillis()
            // Реестр тем сканера не пишем: датчики выбираются списком, не сканом
            lm.sendBroadcast(Intent(MqttService.BROADCAST_MESSAGE).apply {
                putExtra(MqttService.EXTRA_MESSAGE_TOPIC, s.id.toString())
                putExtra(MqttService.EXTRA_MESSAGE_PAYLOAD, payload)
                putExtra(MqttService.EXTRA_MESSAGE_TIME, timeMs)
            })
            msgs.add(Triple(s.id.toString(), payload, timeMs))
        }
        // replay для DashboardActivity (replayLast) — до 50 последних сообщений
        lastMessages[conn.id] = msgs.takeLast(50).toMutableList()
    }

    /** Время последней диагностической записи об успешном опросе (троттлинг 5 мин) */
    private var lastDiagLog = 0L

    /** Что вернул успешный опрос: кого нет в ответе, у кого нет значения,
     *  и кого платформа отдаёт устаревшим */
    private fun diagnosePoll(sensors: List<NarodmonApi.NmSensor>) {
        val now = System.currentTimeMillis()
        if (now - lastDiagLog < 5 * 60_000L) return
        lastDiagLog = now
        val have = sensors.associateBy { it.id }
        val missing = mutableListOf<String>()
        val noValue = mutableListOf<String>()
        panels.forEach { p ->
            val id = p.topicSubscribe.toIntOrNull() ?: return@forEach
            val s = have[id]
            when {
                s == null -> missing.add("${id} «${p.name}»")
                s.value == null -> noValue.add("${id} «${p.name}»")
            }
        }
        val nowSec = now / 1000L
        val stale = sensors.filter { it.value != null && it.time in 1 until nowSec - 3600 }
            .map { s ->
                val stamp = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(s.time * 1000L))
                "${s.id} «${s.name}» (платформа: значение от $stamp, ${ageHuman(nowSec - s.time)})"
            }
        if (missing.isEmpty() && noValue.isEmpty() && stale.isEmpty()) return
        ImportLog.write(applicationContext, buildString {
            append("NARODMON poll ok: датчиков в ответе ${sensors.size}")
            if (missing.isNotEmpty()) append("; НЕТ В ОТВЕТЕ: ${missing.joinToString(", ")}")
            if (noValue.isNotEmpty()) append("; БЕЗ ЗНАЧЕНИЯ: ${noValue.joinToString(", ")}")
            if (stale.isNotEmpty()) append("; СТАРШЕ ЧАСА: ${stale.joinToString(", ")}")
        })
    }

    /** Возраст в человекочитаемом виде: «3 ч 5 мин» / «2 сут» */
    private fun ageHuman(sec: Long): String {
        val min = sec / 60
        return when {
            min < 60 -> "$min мин"
            min < 48 * 60 -> "${min / 60} ч ${min % 60} мин"
            else -> "${min / (24 * 60)} сут"
        }
    }

    private fun broadcastStatus(state: ConnectionState) {
        // тот же broadcast, что и у MqttService: статусы видят оба экрана
        val intent = Intent(MqttService.BROADCAST_STATUS).apply {
            putExtra(MqttService.EXTRA_STATUS, state.name)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /** Рассказать дашборду, какие датчики на платформе в Избранном (fav=1) */
    private fun broadcastFavorites(sensors: List<NarodmonApi.NmSensor>) {
        val favIds = sensors.filter { it.fav == 1 }.map { it.id }
        val intent = Intent(BROADCAST_FAVORITES).apply {
            putExtra(EXTRA_FAVORITE_IDS, favIds.toIntArray())
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
        serviceAlive = false
        // Сервис умер — статус не должен остаться «Подключено»: дашборд
        // показывал зелёный ✓ с застывшим таймером, хотя опроса уже не было
        connectionState = ConnectionState.DISCONNECTED
        connectedAt = 0L
        broadcastStatus(ConnectionState.DISCONNECTED)
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
