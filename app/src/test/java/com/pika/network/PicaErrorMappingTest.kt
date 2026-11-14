package com.pika.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S-5.1：非 2xx 错误体解析 + 用户文案映射。
 * 复现：无效账号登录时 UI 直接显示
 * `400: {"code":400,"error":"1004","message":"invalid email or password","detail":":("}`。
 * 修复后错误体解析出结构化码位，`userMessageFor` 映射为「账号或密码错误」。
 */
class PicaErrorMappingTest {

    @Test
    fun `登录错误体解析出业务码 1004 与服务端 message`() {
        val body = """{"code":400,"error":"1004","message":"invalid email or password","detail":":("}"""
        val parsed = PicaErrorBody.parse(body)
        assertEquals(400, parsed?.httpCode)
        assertEquals(1004, parsed?.errorCode)
        assertEquals("invalid email or password", parsed?.message)
    }

    @Test
    fun `error 字段为数字时同样可解析`() {
        val parsed = PicaErrorBody.parse("""{"code":401,"error":1004,"message":"x"}""")
        assertEquals(1004, parsed?.errorCode)
    }

    @Test
    fun `CDN 整页 HTML 错误体解析返回 null 而不是抛异常`() {
        assertNull(PicaErrorBody.parse("<html><body>502 Bad Gateway</body></html>"))
        assertNull(PicaErrorBody.parse(""))
    }

    @Test
    fun `登录 400 与 1004 映射为账号或密码错误`() {
        val msg = PicaException.userMessageFor(400, 1004, "invalid email or password")
        assertEquals("账号或密码错误", msg)
    }

    @Test
    fun `限流映射不依赖错误体文案`() {
        assertEquals("请求过于频繁，请稍后再试", PicaException.userMessageFor(429, 0, ""))
        assertEquals("请求过于频繁，请稍后再试", PicaException.userMessageFor(400, 1023, ""))
    }

    @Test
    fun `无结构化码位时 4xx 不再透出原始响应体`() {
        // 此前 message 是 `400: {"code":400,...原始 JSON...}`，现在一律映射为可读文案
        val raw = """{"code":400,"error":"1004","message":"invalid email or password"}"""
        val msg = PicaException.userMessageFor(400, 0, raw)
        assertEquals("请求失败（HTTP 400），请稍后重试", msg)
    }

    @Test
    fun `5xx 与 401 403 404 各有稳定文案`() {
        assertEquals("哔咔服务端暂时不可用（HTTP 503），请稍后重试", PicaException.userMessageFor(503, 0, ""))
        assertEquals("登录已失效，请重新登录", PicaException.userMessageFor(401, 0, ""))
        assertEquals("没有访问权限", PicaException.userMessageFor(403, 0, ""))
        assertEquals("内容不存在或已被删除", PicaException.userMessageFor(404, 0, ""))
    }

    @Test
    fun `无任何码位信息时回退到调用方文案`() {
        assertEquals("空响应数据", PicaException.userMessageFor(0, 0, "空响应数据"))
        assertEquals("操作失败，请稍后重试", PicaException.userMessageFor(0, 0, ""))
    }
}
