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
 * - **失败结果不共享（v1.9.11 关键修复）**：首领失败（ErrorResult，含体流中断/解码/
 *   HTTP 错误）时等待者不再直接接受失败，而是重新竞选首领自行下载（上限
 *   [MAX_REELECT] 次后接受失败交给上层）——v1.9.1-1.9.10 的行为是把首领的一次
 *   瞬时失败共享给全部切片，整页瞬间失败且不自愈，而 v1.9.0 的六路独立下载
 *   天然有冗余掩盖网络抖动；本修复在保留合流省流量的同时恢复该冗余。
 * - 首领结束**先摘除窗口条目再唤醒等待者**（v1.9.6 修复：相反顺序在 Main.immediate
 *   内联恢复下会主线程活锁，见 intercept 内注释）。
 * - key 含解码尺寸：尺寸不同的请求（如网格封面按测量值各自定尺寸）互不共享，
 *   避免拿到与请求尺寸不符的位图。
 */
class CoalescingInterceptor : Interceptor {

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ImageResult>>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val key = coalesceKey(request, chain)
        var reelects = 0
        while (true) {
            val mine = CompletableDeferred<ImageResult>()
            val leader = inFlight.putIfAbsent(key, mine)
            if (leader == null) {
                // 首领：正常执行；结束后关闭合流窗口
                val outcome = runCatching { chain.proceed(request) }
                // 关键顺序：**先摘除自己，再唤醒等待者**。
                // 反过来（先 complete 后摘除）会触发主线程活锁：等待者协程跑在
                // Main.immediate 上，complete* 会把它们内联恢复——恢复体在首领的
                // complete 调用内部直接执行，putIfAbsent 拿到的仍是本首领已完成
                // 的 deferred，await 快路径立即再抛 → while 自旋；而首领的摘除
                // 代码位于更深的栈帧，永无执行机会（真机 72s 主线程冻结，
                // v1.9.5 看门狗落盘调用栈 + mapping 反解实证）。
                // 条件摘除（key+value）：只摘自己的条目，绝不误删新一代首领。
                inFlight.remove(key, mine)
                outcome
                    .onSuccess { mine.complete(it) }
                    .onFailure { mine.completeExceptionally(it) }
                return outcome.getOrThrow()
            }
            // 等待者：共享首领成功结果；首领失败（ErrorResult）/异常/取消则重新竞选。
            // 取消必须甄别来源：只有自身被取消才放行——快滚时首领切片滚出屏幕被
            // Coil 取消，同图其余切片仍可见，不能被传染。
            val shared = try {
                leader.await()
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                null
            } catch (e: Exception) {
                // 首领以异常收场（罕见，引擎层通常把失败包装为 ErrorResult 返回）
                null
            }
            if (shared != null) {
                // 失败结果不共享：等待者重新竞选自行下载（恢复 v1.9.0 六路冗余的
                // 抗瞬时故障能力），限额防永久坏链被无限串联重下
                if (shared is coil.request.ErrorResult && reelects < MAX_REELECT) {
                    reelects++
                } else {
                    // **必须以等待者自己的请求重建 result**（v1.9.12 根因修复）：
                    // Coil 的 RealInterceptorChain 会在每个拦截器返回后校验
                    // result.request.target 是否仍是本请求的——直接转交首领的
                    // result 携带首领的 target，必然抛
                    // 「Interceptor cannot modify the request's target」，
                    // 画家进错误态且绕过一切日志（v1.9.1 起全部「页面失败、
                    // 手点秒出」的本体）。copy 只换 request，drawable/缓存键照搬。
                    return when (shared) {
                        is coil.request.SuccessResult -> shared.copy(request = chain.request)
                        is coil.request.ErrorResult -> shared.copy(request = chain.request)
                    }
                }
            }
            // 循环：竞选新首领或等待新首领（循环即重新 putIfAbsent）
        }
    }

    private companion object {
        /** 等待者对「首领失败结果」的最大重新竞选次数（防坏链无限串联重下） */
        const val MAX_REELECT = 3
    }

    private fun coalesceKey(request: ImageRequest, chain: Interceptor.Chain): String {
        // 与 EngineInterceptor 写内存缓存的键保持同构：显式键 > data 串
        val cacheKey = request.memoryCacheKey ?: request.diskCacheKey ?: request.data.toString()
        val size = chain.size
        return "$cacheKey|${size.width}x${size.height}"
    }
}
