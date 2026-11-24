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
}
