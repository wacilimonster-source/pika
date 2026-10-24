package com.pika.image

import android.util.Log
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/**
 * 图片加载的瞬时失败自动重试（OkHttp 应用层）。
 *
 * 背景：漫画图片加载失败绝大多数是瞬时性故障（连接被重置/读写超时/CDN 边缘节点 5xx/限流），
 * 用户手动点「重试」即可恢复。v1.9.2 曾把自动重试放在 UI 层（Compose 状态驱动 painter
 * 换 model 重新请求），真机出现卡顿+闪退回归，已整体回退；改为在 HTTP 层静默重试：
 * 同一次 Coil 请求内部多试两次，成功后 painter 直接 Loading → Success，
 * Coil/Compose 侧的请求流与 v1.9.1 完全一致（一次请求、无重组、无 painter 重启、
 * 无合流重选主），失败面严格小于旧版。
 *
 * 策略：
 * - 只重试**瞬时**失败：IOException（连接失败/读写超时/连接重置等）与
 *   HTTP 408/429/5xx（含 Cloudflare 520-526）。403/404 等确定性失败不重试
 *   （重试无法恢复，只会拖慢出错反馈与浪费流量）。
 * - 最多 2 次重试（共 3 次尝试），退避 500ms/1500ms。
 * - 取消感知：Coil 会在页面滚出屏幕/切章/退出阅读器时取消请求，被取消的调用
 *   必须立即放行，绝不重试（否则僵尸重试会顶着已销毁的协程继续打网络）。
 */
class RetryInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val call = chain.call()
        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(request)
                if (!response.code.isTransientHttpFailure() ||
                    attempt >= MAX_RETRIES ||
                    call.isCanceled()
                ) {
                    // 成功 / 确定性失败 / 重试额度用尽 / 已取消：原样交还调用方（不关闭）
                    return response
                }
                val code = response.code
                // 释放失败响应占用的连接后再重试，避免连接池泄漏
                response.close()
                logRetry(attempt, "HTTP $code", call)
                backoff(attempt)
                attempt++
            } catch (e: IOException) {
                // 取消的调用（页面滚出屏幕/切章）直接放行；Coil 侧以 CancellationException 收场
                if (call.isCanceled() || attempt >= MAX_RETRIES) throw e
                logRetry(attempt, e.javaClass.simpleName, call)
                backoff(attempt)
                attempt++
            }
        }
    }

    /** 退避等待；线程被中断时按取消处理，立即放弃重试 */
    private fun backoff(attempt: Int) {
        try {
            Thread.sleep(BACKOFF_MS[attempt])
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Retry backoff interrupted")
        }
    }

    private fun logRetry(attempt: Int, cause: String, call: Call) {
        Log.i(TAG, "图片请求瞬时失败，${BACKOFF_MS[attempt]}ms 后第 ${attempt + 1} 次自动重试：$cause ${call.request().url}")
    }

    /** 视为瞬时失败、值得重试的 HTTP 状态码：请求超时/限流/5xx（含 Cloudflare 520-526） */
    private fun Int.isTransientHttpFailure(): Boolean =
        this == 408 || this == 429 || this in 500..599

    private companion object {
        const val TAG = "RetryInterceptor"

        /** 最多自动重试次数（共 3 次尝试） */
        const val MAX_RETRIES = 2

        /** 退避间隔：第 1 次重试前 500ms，第 2 次前 1500ms（总额外等待 ≈2s） */
        val BACKOFF_MS = longArrayOf(500L, 1500L)
    }
}
