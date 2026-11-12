package com.pika.core.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 章节分页 fan-out 的契约：按页序返回、失败页串行补拉、冷却中跳过、缺页显式失败。
 *
 * 章节列表与章节图片共用该实现；这些不变量一旦破坏，阅读器会把缺页当成
 * 真实页数固化，或把乱序页拼成错位章节。
 */
class PagedFetchTest {

    @Test
    fun `按页序返回且首页不由本函数拉取`() = runBlocking {
        val calls = ConcurrentHashMap.newKeySet<Int>()
        val result = fetchPagesInOrder(totalPages = 4, intervalMs = 0) { page ->
            calls.add(page)
            "p$page"
        }
        assertEquals(listOf("p2", "p3", "p4"), result)
        assertEquals(setOf(2, 3, 4), calls)
    }

    @Test
    fun `并发失败页串行补拉且不丢页`() = runBlocking {
        val attempts = ConcurrentHashMap<Int, AtomicInteger>()
        val result = fetchPagesInOrder(totalPages = 3, intervalMs = 0) { page ->
            val n = attempts.getOrPut(page) { AtomicInteger() }.incrementAndGet()
            if (page == 2 && n == 1) error("transient")
            "p$page"
        }
        assertEquals(listOf("p2", "p3"), result)
        assertEquals(2, attempts.getValue(2).get())
    }

    @Test
    fun `冷却中跳过并由串行补拉等待后取回`() = runBlocking {
        var cooldownWaits = 0
        val result = fetchPagesInOrder(
            totalPages = 4,
            intervalMs = 0,
            isCoolingDown = { true },
            awaitCooldown = { cooldownWaits++ },
        ) { page -> "p$page" }
        assertEquals(listOf("p2", "p3", "p4"), result)
        assertEquals(3, cooldownWaits)
    }

    @Test
    fun `补拉仍失败时显式抛错`() = runBlocking {
        val error = runCatching {
            fetchPagesInOrder(totalPages = 3, intervalMs = 0) { page ->
                if (page == 3) error("permanent") else "p$page"
            }
        }.exceptionOrNull()
        assertTrue(error != null)
        assertTrue(error!!.message.orEmpty().contains("permanent"))
    }
}
