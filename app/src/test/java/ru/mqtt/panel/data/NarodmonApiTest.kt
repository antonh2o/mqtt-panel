package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Народный Мониторинг: MD5/логин-хэш и парсеры ответов API (без Android-зависимостей).
 * Форматы сверены с живым api.narodmon.ru (2026-11-06):
 * sensorsNearby отдаёт devices[].sensors[], sensorsValues — плоский sensors[],
 * ошибки приходят статусом 200 в конверте {"error","errno"}.
 */
class NarodmonApiTest {

    @Test
    fun `md5 считается корректно`() {
        assertEquals("098f6bcd4621d373cade4e832627b4f6", NarodmonApi.md5("test"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", NarodmonApi.md5(""))
    }

    @Test
    fun `логин-хэш = md5 от uuid и md5 пароля`() {
        val uuid = "c7fad700f99f9cda2598654f522c8d63"
        val hash = NarodmonApi.loginHash(uuid, "секрет")
        assertEquals(32, hash.length)
        assertEquals(hash, NarodmonApi.md5(uuid + NarodmonApi.md5("секрет")))
        assertTrue(hash != NarodmonApi.loginHash(uuid, "другой"))
    }

    @Test
    fun `sensorsNearby парсит датчики, вложенные в устройства`() {
        // реальный формат ответа: devices[].sensors[]
        val json = """
            {"devices":[
              {"id":3658,"name":"MeteoStation","my":0,"sensors":[
                 {"id":11384,"pub":1,"type":1,"name":"Улица","value":0.5,"unit":"°","time":1793965038},
                 {"id":11386,"pub":1,"type":3,"name":"Давление","value":713.81,"unit":"mmHg"}]},
              {"id":4552,"name":"Метеостанция","my":0,"sensors":[
                 {"id":15001,"pub":1,"name":"Дом","value":22.4}]}
            ]}
        """.trimIndent()
        val sensors = NarodmonApi.parseList(json)
        assertEquals(3, sensors.size)
        assertEquals(11384, sensors[0].id)
        assertEquals("Улица", sensors[0].name)
        assertEquals(0.5, sensors[0].value!!, 1e-9)
        assertTrue(sensors.any { it.id == 15001 && it.name == "Дом" })
    }

    @Test
    fun `плоский список sensors тоже парсится`() {
        val json = """
            {"sensors":[
              {"id":11823,"name":"t t столовая","time":1463992693,"value":25.6},
              {"id":12001,"name":"Котёл","time":1463992711,"value":61.0}
            ]}
        """.trimIndent()
        val sensors = NarodmonApi.parseList(json)
        assertEquals(2, sensors.size)
        assertEquals(11823, sensors[0].id)
        assertEquals(25.6, sensors[0].value!!, 1e-9)
    }

    @Test
    fun `sensorsValues парсится в map id-значение`() {
        // реальный ответ sensorsValues?sensors=11384
        val json = """
            {"sensors":[
              {"id":11384,"type":1,"value":0.5,"time":1793965038,"changed":1793965038,"trend":0},
              {"id":12001,"value":61,"time":1463992711},
              {"id":12002,"time":1463992712}
            ]}
        """.trimIndent()
        val values = NarodmonApi.parseValues(json)
        assertEquals(0.5, values[11384]!!, 1e-9)
        assertEquals(61.0, values[12001]!!, 1e-9)
        // датчик без value в map не попадает
        assertTrue(12002 !in values)
    }

    @Test
    fun `ошибочный конверт бросает исключение с текстом платформы`() {
        // 401 «Авторизуйтесь» — датчики, видимые только после userLogon
        val auth = runCatching {
            NarodmonApi.parseList("""{"error":"Авторизуйтесь в приложении для доступа к информации.","errno":401}""")
        }.exceptionOrNull()
        assertTrue("ожидали AuthRequiredException", auth is NarodmonApi.AuthRequiredException)

        // прочие ошибки платформы — обычное исключение с текстом
        val other = runCatching {
            NarodmonApi.parseValues("""{"error":"Участник не найден","errno":402}""")
        }.exceptionOrNull()
        assertTrue(other is IllegalStateException && other !is NarodmonApi.AuthRequiredException)
    }

    @Test
    fun `мусор и пустой ответ не роняют парсер`() {
        assertTrue(NarodmonApi.parseList("not json at all").isEmpty())
        assertTrue(NarodmonApi.parseValues("").isEmpty())
    }

    @Test
    fun `значение форматируется без хвостового нуля`() {
        assertEquals("25", NarodmonApi.fmt(25.0))
        assertEquals("25.6", NarodmonApi.fmt(25.6))
        assertEquals("0", NarodmonApi.fmt(0.0))
    }
}
