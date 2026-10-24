package com.pika.image

import coil.intercept.Interceptor
import coil.request.ImageRequest
import coil.request.ImageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * 在途请求合流：相同「缓存键 + 解码尺寸」的并发请求只放行首个，其余等待并共享同一结果。
 *
 * 背景：Coil 2.x 对同 URL 的并发请求不做去重。阅读器滚动流把一页长图拆成最多 6 个
 * 切片行，每个切片各自发起请求——首次进入长图页时同一张数 MB 原图会被并发下载/解码
 * 多达 6 次（带宽 ×6、瞬时内存峰值 ×6），这是全管线最大的 OOM/流量风险点；
 * 网格快滚里同一封面出现在多卡也属同类场景。
 *
 * 语义：
 * - 共享的是整条剩余链的结果（含内存缓存/磁盘/网络/解码），共享 [coil.request.SuccessResult]
 *   的 drawable 引用不产生位图拷贝；切片渲染本来就是同一份位图按偏移平移绘制。
 * - 等待者收到的若为异常（首领失败/被取消）则降级为自己执行：失败结果不缓存、
 *   用户点重试的请求永远新开执行，不会拿到陈旧的失败结果（ErrorResult 仍在
 *   合流窗口内共享——同一 URL 的并发请求同失败是合理结果，避免失败风暴放大）。
 * - key 含解码尺寸：尺寸不同的请求（如网格封面按测量值各自定尺寸）互不共享，
 *   避免拿到与请求尺寸不符的位图。
 */
class CoalescingInterceptor : Interceptor {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageResult>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val key = coalesceKey(request, chain)
        while (true) {
            val mine = CompletableDeferred<ImageResult>()
            val leader = inFlight.putIfAbsent(key, mine)
            if (leader == null) {
                // 首领：正常执行，结束时关闭合流窗口（finally 先于 return 求值前的异常路径）
                try {
                    val result = chain.proceed(request)
                    mine.complete(result)
                    return result
                } catch (t: Throwable) {
                    mine.completeExceptionally(t)
                    throw t
                } finally {
                    inFlight.remove(key)
                }
            }
            // 等待者：共享首领结果；首领失败/取消则重新竞选（首领已把自己移出窗口，
            // 本协程大概率成为新首领）。取消必须甄别来源：只有自身被取消才放行——
            // 快滚时首领切片滚出屏幕被 Coil 取消，同图其余切片仍可见，
            // 不能把首领的取消传染给它们（否则可见页集体进错误态）
            try {
                return leader.await()
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
            } catch (e: Exception) {
                // 降级重试：走下一轮循环
            }
        }
    }

    private fun coalesceKey(request: ImageRequest, chain: Interceptor.Chain): String {
        // 与 EngineInterceptor 写内存缓存的键保持同构：显式键 > data 串
        val cacheKey = request.memoryCacheKey ?: request.diskCacheKey ?: request.data.toString()
        val size = chain.size
        return "$cacheKey|${size.width}x${size.height}"
    }
}
