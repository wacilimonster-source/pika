package com.pika.image

import android.util.Log
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult

/**
 * 图片管线错误取证：记录每次最终失败的原因（网络体流中断/磁盘缓存/解码）。
 *
 * 背景：HTTP 响应头层的失败由 RetryInterceptor 记录并重试，但**响应体流的中断
 * （弱网/代理链路下连接半途断开，下载了部分字节后失败）与解码失败发生在
 * 拦截器返回之后**，HTTP 层完全不可见——真机与模拟器实测：几乎所有页面失败
 * 都属此类（RetryInterceptor 零记录而页面大量报错）。本拦截器位于 Coil 管线
 * 末端，对最终 ErrorResult 补全证据链（异常类+消息+URL），logcat 可见。
 */
class ErrorLoggingInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed(chain.request)
        if (result is ErrorResult) {
            val t = result.throwable
            Log.e(
                TAG,
                "图片最终失败(${t.javaClass.simpleName}: ${t.message}) <- ${chain.request.data}",
            )
        }
        return result
    }

    private companion object {
        const val TAG = "CoilErr"
    }
}
