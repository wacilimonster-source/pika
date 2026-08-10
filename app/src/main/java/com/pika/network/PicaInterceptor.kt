package com.pika.network

import com.pika.core.pica.PicaConfig
import com.pika.core.pica.defaultPicaHeaders
import com.pika.core.pica.picaSignature
import com.pika.core.pica.picaTimestamp
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 哔咔签名拦截器：为每个请求计算签名头。
 * GET 签名串 = path?query，其余 = path。
 */
class PicaInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: suspend () -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val method = request.method.uppercase()
        val path = url.encodedPath.removePrefix("/")
        val query = url.encodedQuery
        val signedUrl = if (query.isNullOrEmpty()) path else "$path?$query"

        val timestamp = picaTimestamp()
        val signature = picaSignature(signedUrl, timestamp, PicaConfig.NONCE, method)
        val token = tokenProvider()

        val builder = request.newBuilder()
        defaultPicaHeaders().forEach { (k, v) -> builder.header(k, v) }
        builder.header("time", timestamp)
        builder.header("signature", signature)
        builder.header("image-quality", "original")
        token?.let { builder.header("authorization", it) }

        val response = chain.proceed(builder.build())
        if (response.code == 401) {
            runBlocking { onUnauthorized() }
        }
        return response
    }
}