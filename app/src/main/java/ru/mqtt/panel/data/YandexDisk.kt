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
 * приложение на oauth.yandex.ru, вписывает ClientID в настройках и получает
 * токен через браузер (response_type=token, токен приходит в фрагменте
 * ссылки и перехватывается deep link'ом приложения).
 *
 * Хранилище — файл резервной копии на Диске. Скоупы приложений Яндекса
 * бывают разные: «запись в любом месте на Диске» (полный доступ) или
 * «доступ только к папке приложения» (app:/). Поэтому путь подбирается
 * автоматически: сначала /backup/mqtt-panel-config.json, а при отказе
 * (403) — папка приложения app:/mqtt-panel-config.json, которая есть у
 * любого приложения и существует по умолчанию.
 *
 * Протокол Диска двухшаговый: сначала получить временную ссылку
 * (v1/disk/resources/upload|download), затем скачать/загрузить контент по
 * ней. Папку /backup API сам не создаёт — перед первой загрузкой она
 * создаётся отдельным запросом (отсюда была ошибка 409
 * DiskPathDoesntExistsError).
 */
object YandexDisk {

    private const val PREFS = "yandex"
    private const val KEY_TOKEN = "token"
    private const val KEY_CLIENT = "client_id"

    /** Полный путь — для токенов с полным доступом к Диску */
    private const val PATH_FULL = "/backup/mqtt-panel-config.json"
    /** Папка приложения — единственная доступная токенам с скоупом app_folder */
    private const val PATH_APP = "app:/mqtt-panel-config.json"

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

    // ---------- низкоуровневые помощники ----------

    private class Resp(val code: Int, val body: String) {
        val json: org.json.JSONObject? by lazy {
            runCatching { org.json.JSONObject(body) }.getOrNull()
        }
    }

    private fun enc(p: String): String = URLEncoder.encode(p, "UTF-8")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
        }

    private fun errorBody(conn: HttpURLConnection): String =
        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""

    private fun call(method: String, url: String, token: String?, payload: ByteArray? = null): Resp {
        val conn = open(url)
        conn.requestMethod = method
        token?.let { conn.setRequestProperty("Authorization", "OAuth $it") }
        if (payload != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(payload) }
        }
        val code = conn.responseCode
        val body = if (code in 200..299)
            conn.inputStream.bufferedReader().use { it.readText() }
        else errorBody(conn)
        return Resp(code, body)
    }

    /** Человекочитаемая причина ошибки по коду и телу ответа */
    private fun failMessage(code: Int, body: String): String = when (code) {
        401 -> "Токен Яндекса недействителен — в настройках Диска нажмите «Получить токен» заново"
        else -> "HTTP $code: ${body.take(200)}"
    }


    /**
     * Создать папку на Диске (для PATH_FULL это /backup). 201 — создана,
     * 409 — уже существует (не ошибка). null — успех, иначе текст ошибки.
     */
    private fun ensureDir(token: String, dir: String): String? {
        val r = call("PUT", "https://cloud-api.yandex.net/v1/disk/resources?path=${enc(dir)}", token)
        if (r.code == 409) return null
        if (r.code == 403) return "нет доступа к папке $dir"
        if (r.code in 200..299) return null
        return failMessage(r.code, r.body)
    }

    /**
     * Выбрать путь хранения по правам токена. Пробующий листинг /backup —
     * единственный надёжный признак: у токенов с доступом «только папка
     * приложения» ссылку загрузки в /backup API всё равно выдаёт (проверка
     * прав на uploader'е отсутствует), и копия молча ушла бы в недоступную
     * для чтения папку, затерев копию с другого устройства. Поэтому:
     * 200 (видит /backup) → полный путь; 403 → папка приложения;
     * 404 (полный доступ, папки нет) → создаём и берём полный путь.
     * (path, null) — успех; ("", текст ошибки) — отказ.
     */
    private fun resolveRemote(token: String): Pair<String, String?> {
        val probe = call("GET",
            "https://cloud-api.yandex.net/v1/disk/resources?path=${enc("/backup")}&fields=name",
            token)
        return when {
            probe.code == 200 -> Pair(PATH_FULL, null)
            probe.code == 401 -> Pair("", failMessage(401, probe.body))
            probe.code == 403 -> Pair(PATH_APP, null)
            probe.code == 404 -> {
                val e = ensureDir(token, "/backup")
                if (e == null) Pair(PATH_FULL, null)
                else if (e.startsWith("нет доступа")) Pair(PATH_APP, null)
                else Pair("", e)
            }
            else -> Pair("", failMessage(probe.code, probe.body))
        }
    }

    /** Шаг 1: временная ссылка Диска на загрузку/скачивание файла */
    private fun getHref(token: String, op: String, path: String): Resp =
        call("GET",
            "https://cloud-api.yandex.net/v1/disk/resources/$op?path=${enc(path)}" +
                if (op == "upload") "&overwrite=true" else "",
            token)

    // ---------- публичный API ----------

    /**
     * Загрузить конфигурацию на Диск: в /backup (полный доступ) или в папку
     * приложения (токен app_folder — путь выбирается пробой листинга).
     * Возвращает (успех, ошибка или «куда загрузили» для тоста).
     */
    suspend fun upload(context: Context, token: String, content: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext Pair(false, "Не задан OAuth-токен Яндекса")
            runCatching {
                val (path, err) = resolveRemote(token)
                if (err != null) return@runCatching Pair(false, err)
                val h = getHref(token, "upload", path)
                if (h.code == 401) return@runCatching Pair(false, failMessage(h.code, h.body))
                if (h.code !in 200..299) return@runCatching Pair(false, failMessage(h.code, h.body))
                val href = h.json?.optString("href") ?: ""
                if (href.isBlank()) return@runCatching Pair(false, "Диск не вернул ссылку загрузки")
                val put = call("PUT", href, null, content.toByteArray(Charsets.UTF_8))
                if (put.code !in 200..299) return@runCatching Pair(false, failMessage(put.code, put.body))
                Pair(true, if (path == PATH_APP) " (папка приложения)" else "")
            }.getOrDefault(Pair(false, "Не удалось соединиться с Яндекс Диском"))
        }

    /**
     * Скачать конфигурацию с Диска с того же пути, что выбрала загрузка.
     * (true, текст) — успех; (false, причина) — ошибка.
     */
    suspend fun download(context: Context, token: String): Pair<Boolean, String?> =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) return@withContext Pair(false, "Не задан OAuth-токен Яндекса")
            runCatching {
                val (path, err) = resolveRemote(token)
                if (err != null) return@runCatching Pair(false, err)
                val h = getHref(token, "download", path)
                if (h.code == 401) return@runCatching Pair(false, failMessage(h.code, h.body))
                if (h.code == 404) return@runCatching Pair(false,
                    "На Диске ещё нет резервной копии — сначала сделайте «Резервную копию»")
                if (h.code !in 200..299) return@runCatching Pair(false, failMessage(h.code, h.body))
                val href = h.json?.optString("href") ?: ""
                if (href.isBlank()) return@runCatching Pair(false, "Диск не вернул ссылку скачивания")
                val dl = call("GET", href, null)
                if (dl.code !in 200..299) return@runCatching Pair(false, failMessage(dl.code, dl.body))
                Pair(true, dl.body)
            }.getOrDefault(Pair(false, "Не удалось соединиться с Яндекс Диском"))
        }
}
