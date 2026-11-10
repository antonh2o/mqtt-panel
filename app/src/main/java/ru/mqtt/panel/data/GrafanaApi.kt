package ru.mqtt.panel.data

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * История графиков из Grafana: POST /api/ds/query (Grafana 8+) c basic auth,
 * datasource InfluxDB, InfluxQL-запрос (схема telegraf-стиля: measurement +
 * поле + тег). Живые точки при этом остаются из MQTT — Grafana только история.
 *
 * Чистые функции (influxQuery/bucketFor/parseFrames) не зависят от Android —
 * покрыты юнит-тестами. Парсинг — Gson, чтобы тесты шли на JVM без заглушек.
 */
object GrafanaApi {

    private const val PREFS = "grafana"
    private const val USER_AGENT = "MQTT-Panel/0.2 (grafana history; Android)"
    private val gson = Gson()

    data class Settings(
        val url: String = "",
        val user: String = "",
        val pass: String = "",
        val dsUid: String = ""
    ) {
        val configured: Boolean get() = url.isNotBlank() && dsUid.isNotBlank()
    }

    fun credentials(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            url = p.getString("url", null).orEmpty(),
            user = p.getString("user", null).orEmpty(),
            pass = p.getString("pass", null).orEmpty(),
            dsUid = p.getString("dsUid", null).orEmpty()
        )
    }

    fun saveCredentials(context: Context, url: String, user: String, pass: String, dsUid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", url.trim().trimEnd('/'))
            .putString("user", user.trim())
            .putString("pass", pass)
            .putString("dsUid", dsUid.trim())
            .apply()
    }

    /** Длительность периода чипов 1d/1w/1m/1y в мс */
    fun periodSpan(period: String): Long = when (period) {
        "week" -> 7L * 24 * 3600_000L
        "month" -> 30L * 24 * 3600_000L
        "year" -> 365L * 24 * 3600_000L
        else -> 24L * 3600_000L
    }

    // --- чистая логика (юнит-тесты) ---

    /** Шаг группировки: диапазон/maxPoints, округлённый вверх до «красивого» */
    fun bucketFor(spanMs: Long, maxPoints: Int): String {
        val steps = longArrayOf(
            10_000L, 30_000L, 60_000L, 300_000L, 600_000L, 1_800_000L,
            3_600_000L, 10_800_000L, 21_600_000L, 43_200_000L, 86_400_000L
        )
        val labels = arrayOf("10s", "30s", "1m", "5m", "10m", "30m", "1h", "3h", "6h", "12h", "1d")
        val need = spanMs / maxPoints.coerceAtLeast(1)
        val i = steps.indexOfFirst { it >= need }.let { if (it < 0) steps.lastIndex else it }
        return labels[i]
    }

    /** InfluxQL: среднее по полю за диапазон с группировкой по времени */
    fun influxQuery(
        measurement: String, field: String, tagKey: String, tagValue: String,
        fromMs: Long, toMs: Long, maxPoints: Int
    ): String {
        val m = measurement.replace("\"", "\\\"")
        val f = field.replace("\"", "\\\"")
        val tag = if (tagKey.isNotBlank() && tagValue.isNotBlank())
            " AND \"${tagKey.replace("\"", "\\\"")}\" = '${tagValue.replace("'", "\\'")}'"
        else ""
        return "SELECT mean(\"$f\") AS \"v\" FROM \"$m\" WHERE" +
            " time > ${fromMs}ms AND time <= ${toMs}ms$tag" +
            " GROUP BY time(${bucketFor(toMs - fromMs, maxPoints)}) fill(previous)"
    }

    /**
     * Парсинг ответа /api/ds/query: results.<refId>.frames[].data.values
     * = [[время…], [значение…]]. Время — epoch ms (float) или секунды
     * (эвристика > 1e12), null-значения пропускаются. Точки сортированы,
     * без дублей по времени.
     */
    fun parseFrames(json: String): List<NarodmonApi.NmPoint> {
        return runCatching {
            val root = gson.fromJson(json, com.google.gson.JsonObject::class.java)
                ?: return emptyList()
            val results = root.getAsJsonObject("results") ?: return emptyList()
            val entry = results.entrySet().firstOrNull() ?: return emptyList()
            val res = entry.value.asJsonObject
            val frames = res.getAsJsonArray("frames") ?: return emptyList()
            val pts = mutableListOf<NarodmonApi.NmPoint>()
            for (f in frames) {
                val data = f.asJsonObject.getAsJsonObject("data") ?: continue
                val values = data.getAsJsonArray("values") ?: continue
                if (values.size() < 2) continue
                val times = values[0].asJsonArray
                val vals = values[1].asJsonArray
                for (i in 0 until times.size()) {
                    if (i >= vals.size() || vals[i].isJsonNull) continue
                    val tRaw = times[i].asDouble
                    val t = (if (tRaw > 1e12) tRaw else tRaw * 1000.0).toLong()
                    pts.add(NarodmonApi.NmPoint(t, vals[i].asDouble.toFloat()))
                }
            }
            pts.distinctBy { it.t }.sortedBy { it.t }
        }.getOrDefault(emptyList())
    }

    // --- сеть ---

    /**
     * История за период чипа. Бросает IllegalStateException с понятным
     * текстом (HTTP 401 и т.п.) — вызывающий показывает тост.
     */
    suspend fun history(
        settings: Settings, measurement: String, field: String, tagKey: String,
        tagValue: String, period: String
    ): List<NarodmonApi.NmPoint> = withContext(Dispatchers.IO) {
        val to = System.currentTimeMillis()
        val from = to - periodSpan(period)
        val body = JSONObject().apply {
            put("from", from)
            put("to", to)
            put("queries", JSONArray().put(JSONObject().apply {
                put("refId", "A")
                put("datasource", JSONObject().apply {
                    put("type", "influxdb")
                    put("uid", settings.dsUid)
                })
                put("rawQuery", true)
                put("query", influxQuery(measurement, field, tagKey, tagValue, from, to, 500))
                put("maxDataPoints", 500)
            }))
        }
        parseFrames(post(settings, body.toString(), from, to))
    }

    /** Проверка подключения: GET /api/health с basic auth */
    suspend fun probe(settings: Settings): String = withContext(Dispatchers.IO) {
        val text = request(settings, "GET", "${settings.url}/api/health", null)
        runCatching {
            gson.fromJson(text, com.google.gson.JsonObject::class.java)
                ?.get("version")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull() ?: "ok"
    }

    private fun post(settings: Settings, body: String, fromMs: Long, toMs: Long): String =
        request(settings, "POST", "${settings.url}/api/ds/query", body)

    private fun request(settings: Settings, method: String, url: String, body: String?): String {
        val auth = Base64.encodeToString(
            "${settings.user}:${settings.pass}".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = method
            setRequestProperty("Authorization", "Basic $auth")
            setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        when (code) {
            401 -> throw IllegalStateException("HTTP 401 — неверный логин/пароль Grafana")
            403 -> throw IllegalStateException("HTTP 403 — у пользователя нет доступа к datasource")
            404 -> throw IllegalStateException("HTTP 404 — проверьте адрес и UID datasource")
        }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(150)}")
        return text
    }
}
