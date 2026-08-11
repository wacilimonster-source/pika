package com.pika.network

import android.util.Log
import com.pika.BuildConfig
import com.pika.core.source.SourceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.coroutines.delay
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

    /** 429 冷却时长：命中后进程内暂停请求，避免反复重试加重限流 */
    private const val RATE_LIMIT_COOLDOWN_MS = 60_000L

    @Volatile
    private var rateLimitedUntil: Long = 0L

    /** 距 429 冷却结束的毫秒数，>0 表示限流锁定中 */
    fun rateLimitRemaining(): Long =
        (rateLimitedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

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

    /** 统一错误处理：400 解析服务端 message；401 由拦截器回调登出；429 切换线路重试一次后进入冷却；网络错误退避重试两次 */
    suspend fun <T> safeCall(block: suspend () -> ApiResponse<T>): T {
        var attempt = 0
        while (true) {
            val cooldown = rateLimitRemaining()
            if (cooldown > 0) {
                throw PicaException("请求过于频繁(429)，请 ${(cooldown + 999) / 1000} 秒后再试")
            }
            try {
                val response = block()
                if (response.code != 200) {
                    throw PicaException(response.message)
                }
                return response.data
            } catch (e: HttpException) {
                val code = e.code()
                if (code == 429) {
                    if (attempt == 0) {
                        attempt++
                        Log.i("PicaClient", "429 rate limited, switching host and retry")
                        delay(2_000)
                        switchHost()
                        continue
                    }
                    rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    throw PicaException("请求过于频繁(429)，请 ${RATE_LIMIT_COOLDOWN_MS / 1000} 秒后再试")
                }
                val errorBody = e.response()?.errorBody()?.string()
                val message = try {
                    json.parseToJsonElement(errorBody.orEmpty())
                        .jsonObject["message"]?.toString()?.trim('"')
                        ?: throw Exception()
                } catch (ex: Exception) {
                    "服务端错误(${e.code()})"
                }
                throw PicaException(message)
            } catch (e: IOException) {
                if (e is PicaException) throw e
                attempt++
                if (attempt <= 2) {
                    Log.i("PicaClient", "network error, retry $attempt: ${e.message}")
                    delay(1_000L * attempt)
                    continue
                }
                throw PicaException("网络连接失败：${e.message}")
            }
        }
    }

    /** 在主备域名间切换（recreate 内部 API 实例） */
    private fun switchHost() {
        baseUrl = if (baseUrl == PicaApiHosts.PICACOMIC) {
            PicaApiHosts.GO2778
        } else {
            PicaApiHosts.PICACOMIC
        }
        Log.i("PicaClient", "switched host -> $baseUrl")
    }
}