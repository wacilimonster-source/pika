package com.pika.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.delay

/**
 * 单页图片加载失败的自动重试状态（滚动流 WebtoonSplitPage 与横滑 PagerPage 共用）。
 *
 * 语义：
 * - 进入 Error 态且自动额度未用尽 → 按 1s/2s/4s 退避自动重试，共 3 次：
 *   失败多为瞬时性（网络抖动/CDN 闪断），手动点重试即可恢复的事实说明无需改请求本身。
 * - 3 次用尽后停表，回退为既有「点击重试」手动态（行为与 v1.9.1 一致）；
 *   手动点击立即重试并清零计数、重新计满一轮。
 * - 定时器随组合存在：LazyColumn/Pager 回收屏幕外 item 时挂起的 delay 自动取消，
 *   不存在后台重试；同 URL 的并发重试由 CoalescingInterceptor 合流，失败结果只在
 *   合流窗口内共享，不放大失败风暴。
 *
 * tick 驱动 model 追加 #retryN 强制 Coil 重新请求（与既有手动重试同一手法），
 * 缓存键固定裸 URL，重试成功结果仍可被预取/其他切片/回滚重建的 item 复用。
 */
class PageRetryState internal constructor() {

    /** 请求代次：每次重试（自动/手动）+1，驱动 model 变化触发 Coil 重新请求 */
    var tick: Int by mutableIntStateOf(0)
        private set

    /** 已发生的自动重试次数。
     *  snapshot state：普通 var 变化不触发重组，UI 文案「自动重试中↔点击重试」无法切换 */
    var autoAttempts: Int by mutableIntStateOf(0)
        private set

    /** 自动重试额度是否还有剩余（Error 态文案分支用） */
    val autoRetriesLeft: Boolean get() = autoAttempts < MAX_AUTO_RETRIES

    /** 用户点击：立即重试，并重新计满一轮自动重试 */
    fun retryManually() {
        autoAttempts = 0
        tick++
    }

    internal fun retryAutomatically() {
        autoAttempts++
        tick++
    }

    internal companion object {
        const val MAX_AUTO_RETRIES = 3

        /** 退避基准：第 n 次自动重试延迟 = BASE shl n → 1s / 2s / 4s */
        const val BACKOFF_BASE_MS = 1000L
    }
}

/**
 * 创建页面重试状态。[keys] 变化时整体重置：切章时 LazyColumn item 键（row_p_s）会
 * 复用组合节点，若不重置，上一章用尽的自动额度会传染给新章同位置的页。
 */
@Composable
fun rememberPageRetryState(vararg keys: Any?): PageRetryState =
    remember(*keys) { PageRetryState() }

/**
 * Error 态自动重试调度（须在 painter.state 就绪后调用）。
 *
 * Effect 以 (state, tick) 为键：自动重试 tick++ 后重组出新 model，painter 重新执行，
 * state 经 Loading 再次落到 Error 时键值必然变化，effect 重启进入下一档退避；
 * state 离开 Error（成功/新请求/item 被回收）即取消挂起的 delay。
 */
@Composable
fun PageAutoRetryEffect(state: AsyncImagePainter.State, retry: PageRetryState) {
    LaunchedEffect(state, retry.tick) {
        if (state is AsyncImagePainter.State.Error && retry.autoRetriesLeft) {
            delay(PageRetryState.BACKOFF_BASE_MS shl retry.autoAttempts)
            retry.retryAutomatically()
        }
    }
}
