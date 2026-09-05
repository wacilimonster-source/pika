package com.pika.network

import java.io.IOException

/**
 * 哔咔业务异常。
 *
 * @param httpCode  HTTP 状态码（传输层可知时填充，如 429 / 401 / 5xx）
 * @param errorCode 服务端业务错误码（响应体 code 字段，如 1023=请求过频）
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
}
