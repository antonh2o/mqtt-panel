package ru.mqtt.panel.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений: свежий APK лежит в этом же репозитории
 * (apk/mqtt-panel-X.Y.Z.apk на ветке main). Смотрим содержимое папки
 * через GitHub API, выбираем максимальную версию и сравниваем с текущей.
 */
object UpdateChecker {

    private const val API_DIR = "https://api.github.com/repos/antonh2o/mqtt-panel/contents/apk"

    data class Info(val version: String, val url: String, val apkName: String)

    /** Разобрать «0.2.31» в список чисел для сравнения */
    fun parseVersion(v: String): List<Int> =
        v.removePrefix("v").split(".").map { it.trim().toIntOrNull() ?: 0 }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest); val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Последний APK из папки apk/ репозитория; null — не удалось/нет файлов */
    suspend fun latest(context: Context): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API_DIR).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MQTT-Panel")
            if (conn.responseCode !in 200..299) return@runCatching null
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            var best: Info? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "")
                val m = Regex("mqtt-panel-([0-9.]+)\\.apk").find(name) ?: continue
                val ver = m.groupValues[1]
                if (best == null || isNewer(ver, best.version)) {
                    best = Info(ver, o.optString("download_url"), name)
                }
            }
            best
        }.getOrNull()
    }

    /**
     * Скачать APK во внутренний кэш (для установки через FileProvider).
     * onProgress — (скачано, всего) в байтах, всего может быть -1 (размер
     * неизвестен); cancelled — проверить на отмену пользователем.
     */
    suspend fun download(
        context: Context,
        url: String,
        target: java.io.File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false }
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) return@runCatching false
            val total = conn.contentLength
            target.parentFile?.mkdirs()
            var done = 0
            var lastReport = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        if (cancelled()) {
                            target.delete()
                            return@runCatching false
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // отчёт не чаще раза в 100 мс — UI не спамим
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 100) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            done > 0
        }.getOrDefault(false)
    }
}
