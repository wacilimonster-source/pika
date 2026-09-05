package com.pika.core.source

import android.util.Log
import com.pika.data.SourcePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 单一活动源管理器：设置页切换，全局生效。
 */
object SourceManager {
    // 初始值给默认源，init() 中从（已内存缓存的）SourcePrefs 回填，避免单例首次触达时阻塞读盘
    private val _activeSource = MutableStateFlow(SourceType.PICACG)

    private val _unauthorizedTick = MutableStateFlow(0)

    val activeSource: StateFlow<SourceType> = _activeSource

    /** 登录态丢失事件计数：每次 401 / 退出登录自增，UI 据此重查登录态并跳登录页 */
    val unauthorizedTick: StateFlow<Int> = _unauthorizedTick

    private val sources: Map<SourceType, Source> = mapOf(
        SourceType.PICACG to PicacgSource(),
        SourceType.JMCOMIC to JmcomicSource(),
    )

    fun init() {
        _activeSource.value = SourcePrefs.current().activeSource
        // 禁漫会话过期(401)时走静默重登
        com.pika.network.JmClient.onUnauthorizedHook = { onUnauthorized() }
        Log.d("SourceManager", "active source: ${_activeSource.value}")
    }

    /** 切换数据源（suspend，调用方需在协程中） */
    suspend fun switch(type: SourceType) {
        SourcePrefs.current().setActiveSource(type)
        _activeSource.value = type
    }

    fun current(): Source = sources.getValue(_activeSource.value)

    fun sourceOf(type: SourceType): Source = sources.getValue(type)

    fun picaToken(): String? = SourcePrefs.current().picaToken

    /** 401 / 会话失效处理：先用已保存凭据静默重登；失败才登出并通知 UI（重入保护） */
    private var reloginInProgress = false

    suspend fun onUnauthorized() {
        if (reloginInProgress) return
        reloginInProgress = true
        try {
            val type = _activeSource.value
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
                    val msg = e.message.orEmpty()
                    Log.w("SourceManager", "silent re-login failed: $type, ${e.message}")
                    // 凭据被服务端判定无效（改密/封号）→ 删除保存的账号；网络问题则保留
                    if (msg.contains("密码", ignoreCase = true) ||
                        msg.contains("password", ignoreCase = true) ||
                        msg.contains("账号", ignoreCase = true) ||
                        msg.contains("用户名", ignoreCase = true)
                    ) {
                        com.pika.data.SecureAccountStore.clear(type)
                    }
                }
            }
            sources.getValue(type).logout()
            _unauthorizedTick.value += 1
        } finally {
            reloginInProgress = false
        }
    }

    /** 用户主动登出：默认保留保存的凭据（下次登录可一键填充/静默恢复） */
    suspend fun logout(deleteSavedCredentials: Boolean = false) {
        val type = _activeSource.value
        if (deleteSavedCredentials) {
            com.pika.data.SecureAccountStore.clear(type)
        }
        sources.getValue(type).logout()
        _unauthorizedTick.value += 1
    }

    /** 已保存的账号邮箱（无则 null），登录页/设置页展示用 */
    fun savedAccountEmail(): String? =
        com.pika.data.SecureAccountStore.savedEmail(_activeSource.value)
}
