package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Парсер sensorsHistory: формат сверен с живым api.narodmon.ru (data[], время в сек) */
class NarodmonHistoryTest {

    @Test
    fun `история парсится в точки с временем в мс`() {
        val json = """
            {"sensors":[{"id":48254,"type":1,"name":"Баня","unit":"°"}],
             "data":[
               {"id":48254,"time":1794136916,"value":0.8},
               {"id":48254,"time":1794137215,"value":0.9},
               {"id":48254,"time":1794137516,"value":1.0}
             ]}
        """.trimIndent()
        val points = NarodmonApi.parseHistory(json)
        assertEquals(3, points.size)
        assertEquals(1794136916000L, points[0].t)
        assertEquals(0.8f, points[0].v)
        assertTrue(points[0].t < points[2].t) // отсортировано по времени
    }

    @Test
    fun `пустая история и мусор - пустой список`() {
        assertTrue(NarodmonApi.parseHistory("{\"sensors\":[],\"data\":[]}").isEmpty())
        assertTrue(NarodmonApi.parseHistory("garbage").isEmpty())
    }

    @Test
    fun `ошибка платформы пробрасывается`() {
        try {
            NarodmonApi.parseHistory("{\"error\":\"Авторизуйтесь\",\"errno\":401}")
            throw AssertionError("ожидали исключения")
        } catch (e: NarodmonApi.AuthRequiredException) {
            // ожидаемо
        }
    }
}
