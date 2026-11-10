package ru.mqtt.panel.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Резервная копия настроек в Яндекс Диск (REST API cloud-api.yandex.net).
 *
 * Авторизация: OAuth-токен Яндекса. Один раз пользователь регистрирует
 * приложение на oauth.yandex.ru (права «Яндекс Диск (REST)», Redirect URI
 * mqttpanel://yandex-oauth), вписывает ClientID в настройках и получает
 * токен через браузер (response_type=token, токен приходит в фрагменте
 * ссылки и перехватывается deep link'ом приложения).
 *
 * Хранилище: файл /backup/mqtt-panel-config.json на Диске (полный доступ
 * к Диску, scope cloud_api:disk).
 */
object YandexDisk {

    private const val PREFS = "yandex"
    private const val KEY_TOKEN = "token"
    private const val KEY_CLIENT = "client_id"
    private const val REMOTE_PATH = "/backup/mqtt-panel-config.json"

    const val REDIRECT_URI = "mqttpanel://yandex-oauth"

    data class Credentials(val clientId: String, val token: String)

    fun credentials(context: Context): Credentials {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Credentials(
            clientId = p.getString(KEY_CLIENT, "") ?: "",
            token = p.getString(KEY_TOKEN, "") ?: ""
        )
    }

    fun saveCredentials(context: Context, clientId: String, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CLIENT, clientId)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun authUrl(clientId: String): String =
        "https://oauth.yandex.ru/authorize?response_type=token&client_id=$clientId"

    /**
     * Загрузить текст в /backup/mqtt-panel-config.json (overwrite=true).
     * Протокол Диска двухшаговый: сначала GET ссылки загрузки
     * (v1/disk/resources/upload), затем PUT контента по полученному href.
     * Возвращает (успех, текст ошибки платформы).
     */
    suspend fun upload(context: Context, token: String, content: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext Pair(false, "Не задан OAuth-токен Яндекса")
            runCatching {
                val path = URLEncoder.encode(REMOTE_PATH, "UTF-8")
                // шаг 1: получить ссылку загрузки
                val conn = URL(
                    "https://cloud-api.yandex.net/v1/disk/resources/upload?path=$path&overwrite=true"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Authorization", "OAuth $token")
                if (conn.responseCode !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    return@runCatching Pair(false, "HTTP ${conn.responseCode}: ${err.take(200)}")
                }
                val href = org.json.JSONObject(
                    conn.inputStream.bufferedReader().use { it.readText() }
                ).optString("href")
                if (href.isBlank()) return@runCatching Pair(false, "Диск не вернул ссылку загрузки")
                // шаг 2: PUT контента по ссылке
                val put = URL(href).openConnection() as HttpURLConnection
                put.requestMethod = "PUT"
                put.connectTimeout = 20_000
                put.readTimeout = 60_000
                put.doOutput = true
                put.outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                Pair(put.responseCode in 200..299, null)
            }.getOrDefault(Pair(false, "Не удалось соединиться с Яндекс Диском"))
        }

    /**
     * Скачать текст резервной копии: сначала получить ссылку для скачивания,
     * затем скачать содержимое. null — не удалось.
     */
    suspend fun download(context: Context, token: String): String? =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext null
            runCatching {
                val path = URLEncoder.encode(REMOTE_PATH, "UTF-8")
                val conn = URL(
                    "https://cloud-api.yandex.net/v1/disk/resources/download?path=$path"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Authorization", "OAuth $token")
                if (conn.responseCode !in 200..299) return@runCatching null
                val href = org.json.JSONObject(
                    conn.inputStream.bufferedReader().use { it.readText() }
                ).optString("href")
                if (href.isBlank()) return@runCatching null
                val dl = URL(href).openConnection() as HttpURLConnection
                dl.connectTimeout = 20_000
                dl.readTimeout = 60_000
                if (dl.responseCode !in 200..299) return@runCatching null
                dl.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
        }
}
