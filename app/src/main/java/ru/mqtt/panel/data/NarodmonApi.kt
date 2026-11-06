package ru.mqtt.panel.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * REST-клиент Народного Мониторинга (api.narodmon.ru, JSON UTF-8).
 *
 * Проверено живым API (2026-11-06):
 *  - Narodmon-Api-Key и User-Agent — заголовки; uuid устройства — QUERY-параметр
 *    каждого запроса (в заголовке платформа его не видит);
 *  - sensorsNearby возвращает devices[].sensors[] (датчики вложены в устройства);
 *  - sensorsValues?sensors=id-id (до 30) возвращает sensors[] — плоский список;
 *  - ошибка приходит статусом 200 в конверте {"error":..,"errno":..}:
 *    401 «Авторизуйтесь» = нужны приватные датчики → требуется userLogon;
 *  - лимит 1 запрос/мин на клиента и IP (429); при 429 сервис наращивает интервал;
 *  - userLogon: POST JSON {login, hash, uuid}, hash = MD5(uuid + MD5(пароль)),
 *    не чаще раза в сутки.
 * Некоторые сети режут 443 до api.narodmon.ru — при отказе соединения пробуем HTTP.
 */
object NarodmonApi {

    /** Ключ разработчика narodmon.ru («Мои приложения»); лимит общий на ключ и IP */
    const val API_KEY = "[PERSONAL_KEY_REMOVED]"
    private const val USER_AGENT = "MQTT-Panel/0.2 (narodmon polling; Android)"

    private val gson = Gson()

    /** Датчик из sensorsNearby/sensorsValues */
    data class NmSensor(
        val id: Int = 0,
        val name: String = "",
        val value: Double? = null,
        val time: Long = 0
    )

    data class SensorsResponse(val sensors: List<NmSensor> = emptyList())

    data class NmDevice(val sensors: List<NmSensor> = emptyList())
    data class DevicesResponse(val devices: List<NmDevice> = emptyList())

    /** Конверт ошибок narodmon приходит статусом 200: {"error":..,"errno":..} */
    data class ErrorEnvelope(
        val error: String = "",
        @SerializedName("errno") val errno: Int = 0
    )

    /** 401 «Авторизуйтесь» — для датчиков, видимых только после userLogon */
    class AuthRequiredException(message: String) : IllegalStateException(message)

    class RateLimitException : IllegalStateException("Narodmon: 429 rate limit")

    // --- uuid устройства (генерится один раз, живёт в SharedPreferences) ---

    private const val PREFS = "narodmon"
    private const val KEY_UUID = "uuid"
    private const val KEY_LOGON_AT = "logon_at"

    fun uuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_UUID, null)?.let { return it }
        val fresh = md5(UUID.randomUUID().toString())
        prefs.edit().putString(KEY_UUID, fresh).apply()
        return fresh
    }

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** hash = MD5(uuid + MD5(пароль)) — по документации narodmon.ru/appdoc */
    fun loginHash(uuid: String, password: String): String = md5(uuid + md5(password))

    /**
     * Логин не чаще раза в сутки — открывает доступ к приватным датчикам.
     * Пустые логин/пароль — публичные датчики, пропускаем.
     */
    suspend fun ensureLogon(context: Context, login: String, password: String) {
        if (login.isBlank() || password.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LOGON_AT, 0L)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        val u = uuid(context)
        val body = gson.toJson(
            mapOf("login" to login, "hash" to loginHash(u, password), "uuid" to u)
        )
        request(context, "userLogon", body)
        prefs.edit().putLong(KEY_LOGON_AT, System.currentTimeMillis()).apply()
    }

    /** Свои датчики (sensorsNearby?my=1): и опубликованные, и приватные — после логина */
    suspend fun sensorsNearby(context: Context): List<NmSensor> = withContext(Dispatchers.IO) {
        parseList(request(context, "sensorsNearby?my=1"))
    }

    /**
     * Значения всех своих датчиков одним запросом.
     *
     * sensorsValues на 06.11.2026 отвечает «410 Gone»/рвёт соединение при лимите,
     * поэтому поллинг идёт через sensorsNearby?my=1 — в его ответе уже есть
     * текущие value каждого датчика. Возвращает id → значение (без null).
     */
    suspend fun fetchOwnValues(context: Context): Map<Int, Double> = withContext(Dispatchers.IO) {
        parseList(request(context, "sensorsNearby?my=1"))
            .filter { it.value != null }
            .associate { it.id to it.value!! }
    }

    // --- парсинг вынесен наружу для юнит-тестов (без Android-зависимостей) ---

    /**
     * sensorsNearby отдаёт датчики, вложенные в устройства (devices[].sensors[]),
     * поэтому понимаем оба конверта: по наличию ключа devices/sensors.
     */
    fun parseList(json: String): List<NmSensor> {
        errorEnvelope(json)?.let { throw envelopeException(it) }
        val obj = runCatching {
            gson.fromJson(json, com.google.gson.JsonObject::class.java)
        }.getOrNull() ?: return emptyList()
        val sensors = when {
            obj.has("devices") -> gson.fromJson(obj, DevicesResponse::class.java)
                ?.devices?.flatMap { it.sensors }
            obj.has("sensors") -> gson.fromJson(obj, SensorsResponse::class.java)?.sensors
            else -> null
        }
        return sensors.orEmpty().filter { it.id != 0 }
    }

    fun parseValues(json: String): Map<Int, Double> {
        errorEnvelope(json)?.let { throw envelopeException(it) }
        return runCatching {
            gson.fromJson(json, SensorsResponse::class.java)?.sensors
                ?.filter { it.id != 0 && it.value != null }
                ?.associate { it.id to it.value!! }
                ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    /** {"error":..,"errno":..} — платформа присылает ошибки статусом 200 */
    private fun errorEnvelope(json: String): ErrorEnvelope? =
        runCatching { gson.fromJson(json, ErrorEnvelope::class.java) }
            .getOrNull()
            ?.takeIf { it.error.isNotBlank() }

    /** 401 — требуются приватные датчики (userLogon), прочие — обычная ошибка */
    private fun envelopeException(e: ErrorEnvelope): IllegalStateException =
        if (e.errno == 401) AuthRequiredException(e.error)
        else IllegalStateException("Narodmon errno=${e.errno}: ${e.error}")

    /** Значение в строку для lastValue: без хвостового «.0» */
    fun fmt(value: Double): String {
        val rounded = value.toLong()
        return if (value == rounded.toDouble()) rounded.toString() else value.toString()
    }

    /**
     * GET/POST с обязательными заголовками Narodmon-Api-Key/User-Agent;
     * uuid — query-параметр. Первым идёт HTTPS, при отказе соединения — HTTP:
     * некоторые сети блокируют 443 до api.narodmon.ru.
     */
    private fun request(context: Context, path: String, body: String? = null): String {
        val separator = if (path.contains('?')) "&" else "?"
        val fullPath = "$path${separator}uuid=${uuid(context)}"
        val text = runCatching { open(context, "https://api.narodmon.ru/$fullPath", body) }
            .getOrElse { e ->
                if (e is IllegalStateException || e is RateLimitException) throw e
                open(context, "http://api.narodmon.ru/$fullPath", body)
            }
        ImportLog.write(context, "NARODMON $path → ${text.take(300)}")
        errorEnvelope(text)?.let { throw envelopeException(it) }
        return text
    }

    private fun open(context: Context, url: String, body: String?): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Narodmon-Api-Key", API_KEY)
            setRequestProperty("User-Agent", USER_AGENT)
            requestMethod = if (body != null) "POST" else "GET"
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 429) throw RateLimitException()
        if (code !in 200..299) throw IllegalStateException("Narodmon HTTP $code: ${text.take(200)}")
        return text
    }
}
