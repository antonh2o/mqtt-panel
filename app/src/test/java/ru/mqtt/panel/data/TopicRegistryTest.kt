package ru.mqtt.panel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Реестр observed-топиков для сканера: подсчёт, ограничение размера, выбор непокрытых */
class TopicRegistryTest {

    private fun reg() = TopicRegistry().apply {
        record("home/living/temp", "21.5".toByteArray())
        record("home/living/humidity", "40".toByteArray())
        record("home/kitchen/door", "OPEN".toByteArray())
    }

    @Test
    fun `records unique topics and counts repeats`() {
        val r = reg()
        assertEquals(3, r.size)
        r.record("home/living/temp", "22.0".toByteArray())
        assertEquals(3, r.size)
        val temp = r.snapshot().first { it.topic == "home/living/temp" }
        assertEquals(2, temp.count)
        assertEquals("22.0", temp.lastPayload)
    }

    @Test
    fun `uncovered excludes topics covered by widget subscriptions`() {
        val r = reg()
        val uncovered = r.uncovered(listOf("home/living/#")).map { it.topic }
        assertEquals(listOf("home/kitchen/door"), uncovered)
    }

    @Test
    fun `empty subscription list leaves everything uncovered`() {
        assertEquals(3, reg().uncovered(emptyList()).size)
    }

    @Test
    fun `snapshot sorted by depth then name`() {
        val r = TopicRegistry()
        r.record("a/b/c", "1".toByteArray())
        r.record("z", "1".toByteArray())
        r.record("a/b", "1".toByteArray())
        assertEquals(listOf("z", "a/b", "a/b/c"), r.snapshot().map { it.topic })
    }

    @Test
    fun `prunes oldest when capacity exceeded`() {
        val r = TopicRegistry(maxEntries = 3)
        var ts = 1000L
        listOf("t1", "t2", "t3").forEach {
            r.record(it, "v".toByteArray(), ts++)
        }
        r.record("t4", "v".toByteArray(), ts)
        assertEquals(3, r.size)
        val topics = r.snapshot().map { it.topic }
        assertTrue("самый старый должен вытесняться", "t1" !in topics)
        assertTrue("t4" in topics)
    }

    @Test
    fun `long payloads truncated`() {
        val r = TopicRegistry()
        r.record("big", ByteArray(500) { 'x'.code.toByte() })
        val payload = r.snapshot().first().lastPayload
        assertTrue(payload.length <= 201)
        assertTrue(payload.endsWith("…"))
    }
}
