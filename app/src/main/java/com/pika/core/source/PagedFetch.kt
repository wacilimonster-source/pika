package com.pika.core.source

import com.pika.core.runCatchingCancellable
import com.pika.network.PicaException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 并发拉取多页并按页序返回（第 2 页起；首页由调用方单独拉取）。
 *
 * 章节列表与章节图片曾各写一份「并发 3 路 + 限流冷却检查 + 250ms 节拍 +
 * 失败页串行补拉」的循环，任何一处漏改都会让两条阅读主链路行为分叉。
 * 这里收敛成一份可测试的实现：
 * - 并发页失败后按页号顺序串行重试一次，绝不静默丢页；
 * - 冷却中先跳过，串行补拉前等待冷却结束；
 * - 最终仍缺页则显式抛出，消费方不会把截断结果当成完整章节。
 */
internal suspend fun <T> fetchPagesInOrder(
    totalPages: Int,
    concurrency: Int = 3,
    intervalMs: Long = 250L,
    isCoolingDown: () -> Boolean = { false },
    awaitCooldown: suspend () -> Unit = {},
    fetch: suspend (Int) -> T,
): List<T> = coroutineScope {
    if (totalPages <= 1) return@coroutineScope emptyList()
    val sem = Semaphore(concurrency)
    val results = (2..totalPages).map { page ->
        async {
            sem.withPermit {
                if (isCoolingDown()) return@withPermit page to null
                delay(intervalMs)
                page to runCatchingCancellable { fetch(page) }.getOrNull()
            }
        }
    }.awaitAll()
    val byPage = mutableMapOf<Int, T>()
    for ((page, value) in results) {
        if (value != null) byPage[page] = value
    }
    for (page in (2..totalPages).filter { it !in byPage }) {
        awaitCooldown()
        byPage[page] = fetch(page)
    }
    (2..totalPages).map { page ->
        byPage[page] ?: throw PicaException("第 $page 页拉取失败")
    }
}
