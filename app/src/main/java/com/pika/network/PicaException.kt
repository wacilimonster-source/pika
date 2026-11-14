package com.pika.network

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 哔咔业务异常。
 *
 * @param httpCode  HTTP 状态码（传输层可知时填充，如 429 / 401 / 5xx）
 * @param errorCode 服务端业务错误码（响应体 error 字段，如 1023=请求过频、1004=账号或密码错误）
 *
 * 限流判定优先使用结构化错误码，避免依赖响应文案字符串匹配（文案一改即失效，
 * 且任意正文含 "1023" 的普通报错会被误判为限流）。
 */
class PicaException(
    message: String,
    val httpCode: Int = 0,
    val errorCode: Int = 0,
) : IOException(message) {

    /** 是否为服务端限流（429 / 业务码 1023） */
    val isRateLimit: Boolean
        get() = httpCode == 429 || errorCode == 1023 || errorCode == 429

    companion object {
        /**
         * 按 httpCode/errorCode 映射用户可读文案（S-5.1）。
         * 非域名/响应体细节只允许进 LogStore，UI 一律展示这里的映射结果。
         * [fallback] 用于无结构化信息可映射时的兜底（调用方传入的已有可读文案）。
         */
        fun userMessageFor(httpCode: Int, errorCode: Int, fallback: String): String = when {
            httpCode == 429 || errorCode == 1023 || errorCode == 429 -> "请求过于频繁，请稍后再试"
            errorCode == 1004 -> "账号或密码错误"
            httpCode == 401 -> "登录已失效，请重新登录"
            httpCode == 403 -> "没有访问权限"
            httpCode == 404 -> "内容不存在或已被删除"
            httpCode in 500..599 -> "哔咔服务端暂时不可用（HTTP $httpCode），请稍后重试"
            httpCode in 400..499 -> "请求失败（HTTP $httpCode），请稍后重试"
            else -> fallback.ifBlank { "操作失败，请稍后重试" }
        }
    }
}

/**
 * 哔咔非 2xx 错误体的结构化摘要。
 * 错误体可能是 JSON（`{"code":400,"error":"1004","message":"...","detail":":("}`）
 * 也可能是 CDN 返回的整页 HTML——后者解析失败返回 null，调用方按无结构化信息处理。
 */
internal data class PicaErrorBody(
    val httpCode: Int,
    val errorCode: Int,
    val message: String,
) {
    companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true }

        /** 纯 CPU 的 JSON 解码，可用 runCatching（AGENTS §3.3 允许场景） */
        fun parse(body: String): PicaErrorBody? = runCatching {
            val obj = lenientJson.parseToJsonElement(body).jsonObject
            PicaErrorBody(
                httpCode = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                errorCode = obj["error"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                message = obj["message"]?.jsonPrimitive?.content ?: "",
            )
        }.getOrNull()
    }
}
