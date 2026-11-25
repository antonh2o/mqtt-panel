package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Конверт «настройки через MQTT»: AES, чистка секретов, раундтрип */
class MqttConfigShareTest {

    private val config = """{"connections":[{"id":1,"name":"Дача","host":"mqtt.iotw.ru",
        "password":"секрет","apiKey":"QFT","ghPass":"gp","topicPrefix":"antonh2o/tuwa3"},
        {"id":2,"name":"Narodmon","narodmon":true,"apiKey":"НАРОД-КЛЮЧ"}],
        "panels":[{"id":1,"name":"Баня","topicSubscribe":"antonh2o/tuwa3/bmet"}]}"""

    @Test
    fun `envelope roundtrip without encryption`() {
        val env = MqttConfigShare.buildEnvelope(config, "dev1", "Планшет", MqttConfigShare.ENC_NONE, "")
        assertTrue(env.contains("\"app\":\"mqtt-panel\""))
        assertTrue(env.contains("\"from\":\"dev1\""))
        val parsed = MqttConfigShare.parseEnvelope(env, "").getOrThrow()
        assertEquals(config, parsed)
    }

    @Test
    fun `aes roundtrip - wrong password fails, right one restores`() {
        val env = MqttConfigShare.buildEnvelope(config, "dev1", "Планшет", MqttConfigShare.ENC_AES, "пароль")
        assertTrue(env.contains("\"enc\":\"aes\""))
        assertFalse(env.contains("секрет"))
        assertTrue(MqttConfigShare.parseEnvelope(env, "пароль").isSuccess)
        assertTrue(MqttConfigShare.parseEnvelope(env, "другой").isFailure)
    }

    @Test
    fun `same config encrypts differently each time - random iv`() {
        val e1 = MqttConfigShare.buildEnvelope(config, "dev1", "n", MqttConfigShare.ENC_AES, "p")
        val e2 = MqttConfigShare.buildEnvelope(config, "dev1", "n", MqttConfigShare.ENC_AES, "p")
        assertNotEquals(e1, e2)
    }

    @Test
    fun `strip secrets removes passwords and keys but keeps layout`() {
        val stripped = MqttConfigShare.stripSecrets(config)
        assertFalse(stripped.contains("секрет"))
        assertFalse(stripped.contains("QFT"))
        assertFalse(stripped.contains("НАРОД-КЛЮЧ"))
        assertFalse(stripped.contains("gp"))
        assertTrue(stripped.contains("antonh2o/tuwa3/bmet"))
        assertTrue(stripped.contains("\"host\":\"mqtt.iotw.ru\""))
    }

    @Test
    fun `topic helpers`() {
        assertEquals("mqttpanel/dev1/share/config", MqttConfigShare.topicPerDevice("dev1"))
        assertEquals("mqttpanel/share/config", MqttConfigShare.TOPIC_COMMON)
    }

    @Test
    fun `garbage payload fails cleanly`() {
        assertTrue(MqttConfigShare.parseEnvelope("не json", "").isFailure)
    }

    // --- совместимость с уже опубликованными конвертами ---
    // Сгенерированы реализацией 0.2.88 (PBKDF2-SHA256, IV 00..0B) и её
    // аналогом на Android 7 (PBKDF2-SHA1): после перехода на Base64 из
    // stdlib и перебора KDF чтение старых конфигов обязано сохраниться.
    private val fixtureConfig =
        """{"connections":[{"id":1,"name":"Дача","host":"mqtt.iotw.ru","password":"секрет"}],"panels":[]}"""

    private fun aesEnvelope(data: String) =
        """{"app":"mqtt-panel","ver":1,"ts":0,"from":"dev1","name":"Планшет","enc":"aes","data":"$data"}"""

    @Test
    fun `decrypts envelope made with pbkdf2 sha256`() {
        val env = aesEnvelope(
            "AAECAwQFBgcICQoLw7ak27OSJnJWENpyy9gkSvZ3zNQs6vA4Trh5zYLciTSC7xaHUGd19/NCGABC3X4j7Q06uhKm9OjN4mXf4xJ4eAEBi+0KAjSS4+epeViq/ENcR3+LO+wOd+Gp6rYMWv9kYaRoAN2BxvCR8VNRCLNG8aGsYtr2CSay"
        )
        assertEquals(fixtureConfig, MqttConfigShare.parseEnvelope(env, "пароль").getOrThrow())
        assertTrue(MqttConfigShare.parseEnvelope(env, "не тот").isFailure)
    }

    @Test
    fun `decrypts envelope made with pbkdf2 sha1`() {
        val env = aesEnvelope(
            "AAECAwQFBgcICQoLeh6ouuyrjh+0CKEIrzAkX/53H9B2XTfGBmAgxw2YFKH/MqtF0F6HvCX8DBIqH1+0OnmCegnsXsI17uDaOuOBtWFl9u+dd7XUhZ/VGqYTe6hboMRNjdAGByyfXZ6zhQpVaqceIu3F+8kbfjdl6gbAbqynfdpNKq/d"
        )
        assertEquals(fixtureConfig, MqttConfigShare.parseEnvelope(env, "пароль").getOrThrow())
    }
}
