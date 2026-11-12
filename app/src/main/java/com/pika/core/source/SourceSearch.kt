package com.pika.core.source

import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.PageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 带重试的单页搜索。
 *
 * 关注流和搜索页曾各有一份几乎相同的重试循环；取消处理一旦有一边写漏，
 * 就会在「新搜索接管」时继续发请求。统一到这里，保证：
 * - 取消立即放行，不当作失败继续重试；
 * - 固定 500ms 间隔，最多 [attempts] 次尝试；
 * - 失败时抛出最后一次异常（保留原始错误类型供上层判断 401/限流）。
 */
suspend fun Source.searchWithRetry(
    keyword: String,
    page: Int,
    sort: ComicSort = ComicSort.DD,
    categories: List<String> = emptyList(),
    attempts: Int = 3,
): PageResult<ComicSummary> {
    var last: Exception? = null
    repeat(attempts.coerceAtLeast(1)) { attempt ->
        try {
            return search(keyword, page, sort, categories = categories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
            if (attempt < attempts - 1) delay(500)
        }
    }
    throw last ?: IllegalStateException("search failed")
}
