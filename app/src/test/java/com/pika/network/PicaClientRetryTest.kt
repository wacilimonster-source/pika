package com.pika.network

import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-5.2：网络重试耗尽后的对外文案。
 * 复现：断网搜索时 UI 显示
 * `搜索失败：网络连接失败：Unable to resolve host "picaapi.go2778.com"...`。
 * 修复后 withRetry 抛出的 PicaException 是统一文案，底层主机名/异常细节不再直达 UI
 * （细节只进 LogStore）。直驱重试循环验证，不发起真实网络请求。
 */
class PicaClientRetryTest {

    /** 固定前 [failures] 次抛 DNS 解析失败，之后返回成功响应 */
    private class CountingIoError(private val failures: Int) {
        var calls = 0
            private set

        suspend fun block(): ApiResponse<String> {
            calls++
            if (calls <= failures) {
                throw UnknownHostException(
                    "Unable to resolve host \"picaapi.go2778.com\": No address associated with hostname",
                )
            }
            return ApiResponse(code = 200, message = "ok", data = "payload")
        }
    }

    @Test
    fun `重试耗尽后抛统一文案且不携带主机名`() {
        val counter = CountingIoError(failures = 99)
        val thrown = runBlocking {
            runCatching {
                PicaClient.withRetry(counter::block, requireData = true)
            }.exceptionOrNull()
        }
        assertTrue(thrown is PicaException)
        assertEquals(PicaClient.NETWORK_ERROR_USER_MESSAGE, thrown?.message)
        // 复现点：底层异常细节（主机名）不允许出现在对外 message 里
        assertTrue(!thrown?.message.orEmpty().contains("picaapi"))
        assertTrue(!thrown?.message.orEmpty().contains("Unable to resolve"))
        // 首次成功前固定 3 次尝试（1 次 + 2 次重试）
        assertEquals(3, counter.calls)
    }

    @Test
    fun `瞬时网络失败重试后成功则正常返回数据`() {
        val counter = CountingIoError(failures = 1)
        val result = runBlocking {
            PicaClient.withRetry(counter::block, requireData = true)
        }
        assertEquals("payload", result)
        assertEquals(2, counter.calls)
    }

    @Test
    fun `业务码非 200 时保留服务端 message 且空 message 有兜底`() {
        val blankMessage = runBlocking {
            runCatching {
                PicaClient.withRetry<String>({ ApiResponse(code = 4010, message = "") }, requireData = true)
            }.exceptionOrNull()
        }
        assertTrue(blankMessage is PicaException)
        // 空 message 不允许空白错误行直达 UI
        assertTrue(blankMessage?.message.orEmpty().isNotBlank())

        val serverMessage = runBlocking {
            runCatching {
                PicaClient.withRetry<String>({ ApiResponse(code = 400, message = "密码错误") }, requireData = true)
            }.exceptionOrNull()
        }
        assertEquals("密码错误", serverMessage?.message)
    }

    @Test
    fun `非 IOException 的意外异常原样抛出不改写`() {
        val thrown = runBlocking {
            runCatching {
                PicaClient.withRetry<String>({ throw IllegalStateException("boom") }, requireData = true)
            }.exceptionOrNull()
        }
        // 只有 IOException（网络层）才走统一文案改写
        assertTrue(thrown is IllegalStateException)
        assertEquals("boom", thrown?.message)
    }
}
