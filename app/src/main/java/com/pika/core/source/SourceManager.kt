package com.pika.core.source

import android.util.Log
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.PageResult
import com.pika.data.SourcePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/**
 * 单一活动源管理器，全局生效。
 */
object SourceManager {
    // 初始值给默认源，init() 中从（已内存缓存的）SourcePrefs 回填，避免单例首次触达时阻塞读盘
    private val _activeSource = MutableStateFlow(SourceType.PICACG)

    private val _unauthorizedTick = MutableStateFlow(0)

    val activeSource: StateFlow<SourceType> = _activeSource

    /** 登录态丢失事件计数：每次 401 / 退出登录自增，UI 据此重查登录态并跳登录页 */
    val unauthorizedTick: StateFlow<Int> = _unauthorizedTick

    private val sources: Map<SourceType, Source> = mapOf(
        SourceType.PICACG to StampedSource(PicacgSource(), SourceType.PICACG),
    )

    fun init() {
        // 活动源后台回填：主线程不再做 DataStore 冷读盘（此前 init 同步读，与
        // SourcePrefs"冷启动主线程不同步读盘"的设计声明矛盾，StrictMode 必报警）。
        // 回填读取走 SourcePrefs getter 的 runBlocking 兜底（仅此处后台线程阻塞，可接受）；
        // StateFlow 本身在回填前的极小窗口内是默认源，消费方（UI collectAsState）
        // 都是响应式收集，回填落位后会带着新值自动重载。回填不覆盖窗口期内已做出的切换。
        com.pika.core.AppScope.launch {
            val persisted = SourcePrefs.current().activeSource
            if (_activeSource.value != persisted) {
                _activeSource.value = persisted
            }
        }
        Log.d("SourceManager", "active source: ${_activeSource.value}")
    }

    fun current(): Source = sources.getValue(_activeSource.value)

    fun sourceOf(type: SourceType): Source = sources.getValue(type)

    fun picaToken(): String? = SourcePrefs.current().picaToken

    /**
     * 401 / 会话失效处理：先用已保存凭据静默重登；失败才登出并通知 UI（重入保护）。
     *
     * [type] 必须是**发出该 401 请求的数据源**，不能读全局活动源：
     * 用户在 A 源阅读时切到 B 源，A 的遗留请求（章节 fan-out / 图片预取 / 下载）返回 401，
     * 若按活动源判定就会「用 B 的凭据去登录 B」，既掩盖了 A 的会话失效，又会无谓登出 B。
     */
    // 并发 401 风暴（章节/图片 fan-out 同时失败）下只放行一个重登者：
    // 裸 check-then-act 的 reloginInProgress 无同步，会重复 login（浪费并可能触发风控）
    private val reloginMutex = Mutex()

    suspend fun onUnauthorized(type: SourceType = _activeSource.value) {
        if (!reloginMutex.tryLock()) return
        try {
            val creds = com.pika.data.SecureAccountStore.load(type)
            if (creds != null) {
                try {
                    sources.getValue(type).login(creds.first, creds.second)
                    // 静默恢复成功，无需登出
                    Log.d("SourceManager", "silent re-login ok: $type")
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SourceManager", "silent re-login failed: $type, ${e.message}")
                    // 凭据被服务端判定无效（改密/封号）→ 删除保存的账号；网络问题则保留
                    if (isCredentialRejected(e)) {
                        com.pika.data.SecureAccountStore.clear(type)
                    }
                }
            }
            sources.getValue(type).logout()
            // 仅当失效的正是用户当前所在的源时才通知 UI 跳登录页；
            // update 用 CAS 自增，避免并发 401 时计数丢更新
            if (type == _activeSource.value) _unauthorizedTick.update { it + 1 }
        } finally {
            reloginMutex.unlock()
        }
    }

    /**
     * 判断异常是否代表「凭据被拒绝」。
     *
     * 优先用结构化错误码（401/403）—— 与 [com.pika.network.PicaException.isRateLimit] 同一范式；
     * 文案匹配只作兜底（服务端文案一改即失效）。
     * 注意必须排除限流：【限流不是凭据无效】，误判会把好账号清掉。
     */
    private fun isCredentialRejected(e: Throwable): Boolean {
        val pe = e as? com.pika.network.PicaException
        if (pe != null) {
            if (pe.isRateLimit) return false
            if (pe.httpCode == 401 || pe.httpCode == 403) return true
            if (pe.errorCode == 401 || pe.errorCode == 403) return true
        }
        val msg = e.message.orEmpty()
        return listOf("密码", "password", "账号", "用户名", "username")
            .any { msg.contains(it, ignoreCase = true) }
    }

    /** 用户主动登出：默认保留保存的凭据（下次登录可一键填充/静默恢复） */
    suspend fun logout(deleteSavedCredentials: Boolean = false) {
        val type = _activeSource.value
        if (deleteSavedCredentials) {
            com.pika.data.SecureAccountStore.clear(type)
        }
        sources.getValue(type).logout()
        _unauthorizedTick.update { it + 1 }
    }

    /** 已保存的账号邮箱（无则 null），登录页/设置页展示用 */
    fun savedAccountEmail(): String? =
        com.pika.data.SecureAccountStore.savedEmail(_activeSource.value)
}

/**
 * 给源返回的作品盖上来源标记（见 [ComicRef]）。
 *
 * 集中在这里盖章，而不是要求每个源实现自己填：漏一处就会有一条链路按裸 id 记账，
 * 而这类遗漏在单源下完全看不出来。
 */
private class StampedSource(
    private val delegate: Source,
    private val stampWith: SourceType,
) : Source by delegate {

    private fun ComicSummary.stamp() = if (source == stampWith) this else copy(source = stampWith)
    private fun List<ComicSummary>.stamp() = map { it.stamp() }
    private fun PageResult<ComicSummary>.stamp() = copy(items = items.stamp())

    override suspend fun browse(
        page: Int,
        category: String?,
        sort: ComicSort,
        author: String?,
        tag: String?,
    ): PageResult<ComicSummary> = delegate.browse(page, category, sort, author, tag).stamp()

    override suspend fun search(
        keyword: String,
        page: Int,
        sort: ComicSort,
        categories: List<String>,
        tags: List<String>,
        author: String?,
        chineseTeam: String?,
        uploader: String?,
        finished: Boolean?,
    ): PageResult<ComicSummary> =
        delegate.search(keyword, page, sort, categories, tags, author, chineseTeam, uploader, finished).stamp()

    override suspend fun comicDetail(id: String): ComicDetail {
        val d = delegate.comicDetail(id)
        return if (d.source == stampWith) d else d.copy(source = stampWith)
    }

    override suspend fun favourites(page: Int): PageResult<ComicSummary> =
        delegate.favourites(page).stamp()

    // 参数不能叫 type：与继承自 Source 的 type 属性同名
    override suspend fun rank(rankType: String): List<ComicSummary> = delegate.rank(rankType).stamp()

    override suspend fun randomComics(): List<ComicSummary> = delegate.randomComics().stamp()

    override suspend fun recommendations(id: String): List<ComicSummary> =
        delegate.recommendations(id).stamp()
}
