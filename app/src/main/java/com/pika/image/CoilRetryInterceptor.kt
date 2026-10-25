package com.pika.image

import android.util.Log
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import com.pika.core.log.CrashReporter
import com.pika.core.log.LogStore
import java.io.IOException
import java.util.concurrent.Semaphore
import kotlinx.coroutines.delay

/**
 * 图片管线级自动重试（覆盖 HTTP 头层重试不可见的失败）。
 *
 * 背景（模拟器联调取证，v1.9.7）：页面失败存在两种形态——
 * 1. HTTP 响应头层失败（连接重置/5xx 等）：由 RetryInterceptor 在 OkHttp 层重试，
 *    实测频率极低（冷缓存整章仅 1 次）；
 * 2. **响应体流中途断裂 / 解码失败**：发生在 RetryInterceptor 返回之后（它只见
 *    响应头），HTTP 层任何机制都不可见——弱网/代理突发不稳时页面大量失败、
 *    手动点重试（重新完整请求）即恢复，正是此形态；RetryInterceptor 与失败
 *    记录文件均零记录（真机与模拟器行为一致）。
 *
 * 本拦截器在 Coil 管线外层对最终失败做有限重试：
 * - 仅重试 [IOException] 形态（体流/解码）；HttpException（头层）已由
 *   RetryInterceptor 用尽预算，不重复（避免最坏 5×N 次网络尝试）。
 * - 第 1 次重试用原请求（500ms 后，可能命中健康缓存或合流新首领）；
 *   第 2 次禁用磁盘缓存读（2s 后），绕过可能被半截响应污染的缓存条目、
 *   强制完整新下载（成功后仍写回缓存）。
 * - 全进程并发重试限额（信号量 tryAcquire，拿不到名额直接按失败交还，
 *   行为等同无本拦截器；绝不占满网络通道——v1.9.3 卡死事故教训）。
 * - 取消立即放行（页面滚出屏幕/切章时 proceed 抛 CancellationException，
 *   天然不重试）；delay 期间取消同理，finally 释放名额。
 */
class CoilRetryInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        var attempt = 0
        var held = false
        try {
            while (true) {
                val result = chain.proceed(requestOf(request, attempt))
                val error = (result as? ErrorResult)?.throwable
                val retryable = error is IOException && attempt < MAX_RETRIES
                if (!retryable) {
                    if (error != null && attempt >= MAX_RETRIES) logGiveUp(error, request)
                    return result
                }
                if (!held && !slots.tryAcquire()) {
                    val msg = "管线重试并发已满($MAX_CONCURRENT)，按失败交还（可手动重试）：${error.javaClass.simpleName} ${shortUrl(request)}"
                    Log.w(TAG, msg)
                    LogStore.log(TAG, "W", msg)
                    CrashReporter.appendImageFailure(msg)
                    return result
                }
                held = true
                logRetry(attempt, error, request)
                delay(BACKOFF_MS[attempt])
                attempt++
            }
        } finally {
            if (held) slots.release()
        }
    }

    /** 第 2 次重试绕过磁盘缓存读（防半截响应污染条目），成功仍写回 */
    private fun requestOf(request: ImageRequest, attempt: Int): ImageRequest =
        if (attempt == FRESH_ATTEMPT) {
            request.newBuilder().diskCachePolicy(CachePolicy.WRITE_ONLY).build()
        } else {
            request
        }

    private fun logRetry(attempt: Int, error: Throwable, request: ImageRequest) {
        val msg = "图片管线失败(${error.javaClass.simpleName}: ${error.message})，${BACKOFF_MS[attempt]}ms 后第 ${attempt + 1} 次重试${if (attempt == FRESH_ATTEMPT) "（绕过磁盘缓存）" else ""}：${shortUrl(request)}"
        Log.w(TAG, msg)
        LogStore.log(TAG, "W", msg)
    }

    private fun logGiveUp(error: Throwable, request: ImageRequest) {
        val msg = "管线重试${MAX_RETRIES}次后仍失败(${error.javaClass.simpleName}: ${error.message})，等待手动重试：${shortUrl(request)}"
        Log.w(TAG, msg)
        LogStore.log(TAG, "W", msg)
        CrashReporter.appendImageFailure(msg)
    }

    private fun shortUrl(request: ImageRequest): String =
        request.data.toString().let { if (it.length > 140) "…${it.takeLast(140)}" else it }

    private companion object {
        const val TAG = "CoilRetry"

        /** 最多管线级重试次数（共 3 次尝试） */
        const val MAX_RETRIES = 2

        /** 第 2 次重试（下标 2）绕过磁盘缓存 */
        const val FRESH_ATTEMPT = 2

        /** 退避：第 1 次重试前 500ms，第 2 次前 2000ms */
        val BACKOFF_MS = longArrayOf(500L, 2000L)

        /** 全进程同时处于「管线重试中」的请求上限（弱网故障期防止占满网络通道） */
        const val MAX_CONCURRENT = 4

        val slots = Semaphore(MAX_CONCURRENT)
    }
}
