package ru.mqtt.panel.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Обмен конфигурациями дашбордов через MQTT.
 *
 * Конфиг публикуется сообщением на брокер текущего подключения:
 *   mqttpanel/<devId>/share/config  — «per-device» (по умолчанию)
 *   mqttpanel/share/config          — общий топик
 * сообщением retained (QoS 1): брокер хранит последний конфиг, новое
 * устройство получает его сразу при подписке на mqttpanel/+/share/config.
 *
 * Формат конверта:
 *   {app:"mqtt-panel", ver:1, ts, from:<devId>, name, enc:"none"|"aes", data}
 *   enc="none" — data = JSON конфига; enc="aes" — base64(IV + AES/GCM),
 *   ключ = PBKDF2(пароль, соль=devId, 65536 итераций, 128 бит):
 *   SHA-256, а на Android 7, где его нет, — SHA-1 (при чтении пробуются оба).
 *
 * Режим «без секретов» (enc="none" + stripSecrets) удаляет из конфига
 * пароли и API-ключи — такой конфиг безопасно публиковать на публичном
 * брокере. Чистые функции — покрываются JVM-тестами.
 */
@OptIn(ExperimentalEncodingApi::class)
object MqttConfigShare {

    const val TOPIC_COMMON = "mqttpanel/share/config"

    fun topicPerDevice(devId: String): String = "mqttpanel/$devId/share/config"

    /** Топик(и) для подписки при импорте: + ловит и per-device, и общий */
    const val SUBSCRIBE_FILTER = "mqttpanel/+/share/config"

    const val ENC_NONE = "none"
    const val ENC_AES = "aes"

    private val gson = Gson()

    /** Конверт как JSON-строка (это и есть payload MQTT-сообщения) */
    fun buildEnvelope(
        configJson: String,
        devId: String,
        deviceName: String,
        enc: String,
        passphrase: String
    ): String {
        val data = when (enc) {
            ENC_AES -> encrypt(configJson, passphrase, devId)
            else -> configJson
        }
        val env = JsonObject().apply {
            addProperty("app", "mqtt-panel")
            addProperty("ver", 1)
            addProperty("ts", System.currentTimeMillis())
            addProperty("from", devId)
            addProperty("name", deviceName)
            addProperty("enc", enc)
            addProperty("data", data)
        }
        return gson.toJson(env)
    }

    /** Разобрать конверт: возвращает JSON конфига; AES требует пароль */
    fun parseEnvelope(payload: String, passphrase: String): Result<String> = runCatching {
        val env = gson.fromJson(payload, JsonObject::class.java)
        require(env.get("app")?.asString == "mqtt-panel") { "не конверт MQTT+ Panel" }
        val enc = env.get("enc")?.asString ?: ENC_NONE
        val data = env.get("data")?.asString ?: throw IllegalStateException("пустой data")
        when (enc) {
            ENC_AES -> decrypt(data, passphrase, env.get("from")?.asString ?: "")
            else -> data
        }
    }

    /** Имя устройства-источника из конверта (для списка при импорте) */
    fun envelopeFrom(payload: String): Pair<String, Long> = runCatching {
        val env = gson.fromJson(payload, JsonObject::class.java)
        (env.get("from")?.asString ?: "?") to (env.get("ts")?.asLong ?: 0L)
    }.getOrDefault("?" to 0L)

    /** Убрать из конфига секреты: пароли, API-ключи Народмона и креды Grafana */
    fun stripSecrets(configJson: String): String {
        val cfg = gson.fromJson(configJson, JsonObject::class.java)
        cfg.getAsJsonArray("connections")?.forEach { c ->
            val o = c.asJsonObject
            o.addProperty("password", "")
            o.addProperty("apiKey", "")
            o.addProperty("ghPass", "")
        }
        return gson.toJson(cfg)
    }

    // --- AES-GCM ---

    /**
     * PBKDF2WithHmacSHA256 появился только в API 26, а minSdk приложения — 24:
     * на Android 7.0/7.1 доступен лишь SHA-1. Шифруем первым доступным
     * алгоритмом, расшифровываем перебором: конверты 0.2.86+ (SHA-256)
     * читаются на любом устройстве, а конверт с Android 7 — на новом.
     *
     * Base64 берётся из stdlib Kotlin: java.util.Base64 доступен только
     * с API 26 и ронял обмен конфигом на Android 7 (NoClassDefFoundError).
     */
    private val KDF_ALGORITHMS = listOf("PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1")
    private const val KDF_ITERATIONS = 65536
    private const val KDF_KEY_BITS = 128

    private fun keyFor(passphrase: String, salt: String, algorithm: String): SecretKeySpec {
        val spec = PBEKeySpec(
            passphrase.toCharArray(), salt.toByteArray(Charsets.UTF_8),
            KDF_ITERATIONS, KDF_KEY_BITS
        )
        val key = SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    private fun encrypt(plain: String, passphrase: String, salt: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        var missing: NoSuchAlgorithmException? = null
        for (algorithm in KDF_ALGORITHMS) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE, keyFor(passphrase, salt, algorithm),
                    GCMParameterSpec(128, iv)
                )
                val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                return Base64.Default.encode(iv + body)
            } catch (e: NoSuchAlgorithmException) {
                missing = e
            }
        }
        throw IllegalStateException("Нет доступного PBKDF2 для шифрования конфига", missing)
    }

    private fun decrypt(data: String, passphrase: String, salt: String): String {
        val raw = Base64.Default.decode(data)
        require(raw.size > 12) { "повреждённые данные" }
        var last: Throwable? = null
        for (algorithm in KDF_ALGORITHMS) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE, keyFor(passphrase, salt, algorithm),
                    GCMParameterSpec(128, raw.copyOfRange(0, 12))
                )
                return String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("Не удалось расшифровать конфиг (неверный пароль?)", last)
    }

    /**
     * Публикация конфига через короткоживущий MQTT-клиент: не трогает
     * состояние основного сервиса (свой clientId «mqttpanel-share-…»).
     * Сообщение retained — брокер хранит его для новых устройств.
     */
    suspend fun publishViaMqtt(
        conn: MqttConnection,
        topic: String,
        payload: String
    ): Result<Unit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val client = org.eclipse.paho.client.mqttv3.MqttClient(
                "${conn.getScheme()}://${conn.host}:${conn.port}",
                "mqttpanel-share-" + java.util.UUID.randomUUID().toString().take(8),
                org.eclipse.paho.client.mqttv3.persist.MemoryPersistence()
            )
            val opts = org.eclipse.paho.client.mqttv3.MqttConnectOptions().apply {
                if (conn.username.isNotBlank()) userName = conn.username
                if (conn.password.isNotBlank()) password = conn.password.toCharArray()
                keepAliveInterval = 30
                isCleanSession = true
                connectionTimeout = 15
            }
            client.connect(opts)
            val msg = org.eclipse.paho.client.mqttv3.MqttMessage(payload.toByteArray()).apply {
                isRetained = true
                qos = 1
            }
            client.publish(topic, msg)
            client.disconnect()
        }
    }

    /**
     * Сбор конфигов с брокера: подписка mqttpanel/+/share/config на N секунд
     * (ловит и per-device, и общий топик). Возвращает пары «топик — payload».
     */
    suspend fun collectConfigs(
        conn: MqttConnection,
        seconds: Int
    ): List<Pair<String, String>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val client = org.eclipse.paho.client.mqttv3.MqttClient(
                "${conn.getScheme()}://${conn.host}:${conn.port}",
                "mqttpanel-collect-" + java.util.UUID.randomUUID().toString().take(8),
                org.eclipse.paho.client.mqttv3.persist.MemoryPersistence()
            )
            val opts = org.eclipse.paho.client.mqttv3.MqttConnectOptions().apply {
                if (conn.username.isNotBlank()) userName = conn.username
                if (conn.password.isNotBlank()) password = conn.password.toCharArray()
                keepAliveInterval = 30
                isCleanSession = true
                connectionTimeout = 15
            }
            val received = mutableListOf<Pair<String, String>>()
            client.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallback {
                override fun messageArrived(topic: String, message: org.eclipse.paho.client.mqttv3.MqttMessage) {
                    received += topic to String(message.payload)
                }
                override fun connectionLost(cause: Throwable?) {}
                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
            })
            client.connect(opts)
            client.subscribe(SUBSCRIBE_FILTER, 1)
            kotlinx.coroutines.delay(seconds * 1000L)
            client.disconnect()
            received
        }.getOrDefault(emptyList())
    }
}
