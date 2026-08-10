package com.pika.network

import android.util.Log
import com.pika.BuildConfig
import com.pika.core.source.SourceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class PicaException(message: String) : IOException(message)

/**
 * 哔咔客户端：OkHttp(签名拦截器) + Retrofit。
 */
object PicaClient {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    var baseUrl: String = PicaApiHosts.default
        set(value) {
            field = value
            recreate()
        }

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                PicaInterceptor(
                    tokenProvider = { SourceManager.picaToken() },
                    onUnauthorized = { SourceManager.onUnauthorized() },
                )
            )
            .addInterceptor(logging)
            .build()
    }

    private var _retrofit: Retrofit? = null
    private var _api: PicaApi? = null

    val api: PicaApi
        get() {
            val existing = _api
            if (existing != null) return existing
            return createApi()
        }

    private fun createApi(): PicaApi {
        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
        _retrofit = retrofit
        _api = retrofit.create(PicaApi::class.java)
        return _api!!
    }

    private fun recreate() {
        _api = null
    }

    /** 统一错误处理：400 时抛出服务端 message；401 由拦截器回调登出 */
    suspend fun <T> safeCall(block: suspend () -> ApiResponse<T>): T {
        try {
            val response = block()
            if (response.code != 200) {
                throw PicaException(response.message)
            }
            return response.data
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            val message = try {
                json.parseToJsonElement(errorBody.orEmpty())
                    .jsonObject["message"]?.toString()?.trim('"')
                    ?: throw Exception()
            } catch (ex: Exception) {
                "服务端错误(${e.code()})"
            }
            throw PicaException(message)
        }
    }
}